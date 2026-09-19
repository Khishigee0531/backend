package dev.manestack.api.ws;

import dev.manestack.domain.user.ChatSettings;
import dev.manestack.dto.chat.ChatMessageDTO;
import dev.manestack.service.ChatService;
import dev.manestack.service.MetricsService;
import dev.manestack.service.UserService;
import dev.manestack.domain.user.UserBalance;
import io.quarkus.websockets.next.*;
import io.smallrye.jwt.auth.principal.JWTParser;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jboss.logging.Logger;
import io.vertx.core.json.JsonObject;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@WebSocket(path = "/ws/global")
@Singleton
public class GlobalSocket {

    private static final Logger LOG = Logger.getLogger(GlobalSocket.class);

    private final Map<String, WebSocketConnection> sessions = new ConcurrentHashMap<>();
    private final Map<String, Integer> userIds = new ConcurrentHashMap<>();
    private static final Map<Integer, WebSocketConnection> userConnections = new ConcurrentHashMap<>();

    // NEW — store user roles (ADMIN or USER)
    private final Map<Integer, String> userRoles = new ConcurrentHashMap<>();

    // Cached homepage chat permission settings
    private volatile ChatSettings currentSettings = new ChatSettings(true, true);

    @Inject
    JWTParser jwtParser;

    @Inject
    ChatService chatService;

    @Inject
    UserService userService;

    @Inject
    MetricsService metricsService;

    @PostConstruct
    void loadSettings() {
        try {
            currentSettings = chatService.getChatSettings();
        } catch (Exception e) {
            LOG.warnf("Failed to load chat settings: %s", e.getMessage());
        }
    }

    @OnOpen
    public void onOpen(WebSocketConnection connection) {
        LOG.infof("Connection opened: %s", connection.id());
        sessions.put(connection.id(), connection);
        allSessions.put(connection.id(), connection);
        metricsService.incrementWsConnections();
        connection.sendText("{\"type\":\"CONNECTED\"}");
    }

    @OnClose
    public void onClose(WebSocketConnection connection) {
        LOG.infof("Connection closed: %s", connection.id());
        sessions.remove(connection.id());
        allSessions.remove(connection.id());
        Integer userId = userIds.remove(connection.id());
        if (userId != null) {
            userConnections.remove(userId);
            userRoles.remove(userId);
        }
    }

    @OnTextMessage
    public void onTextMessage(WebSocketConnection connection, String message) {
        try {
            JsonObject json = new JsonObject(message);
            String type = json.getString("type").toUpperCase();

            switch (type) {
                case "AUTH" -> handleAuth(connection, json);
                case "CHAT_MESSAGE" -> handleChatMessage(connection, json);
                case "PING" -> connection.sendText("{\"type\":\"PONG\"}");
                default -> connection.sendText("{\"type\":\"UNKNOWN_EVENT\"}");
            }
        } catch (Exception e) {
            LOG.error("Error processing message", e);
            connection.close();
        }
    }

    // ---------- AUTH + ROLE LOGIC ----------
    private void handleAuth(WebSocketConnection connection, JsonObject json) {
        String token = json.getString("token");
        try {
            var jwt = jwtParser.parse(token);
            String sub = jwt.getClaim("sub");
            int userId = Integer.parseInt(sub);

            // Role is read from the DB (authoritative) rather than the token, so
            // existing sessions keep the correct role even with tokens lacking a role claim.
            // Use async version to avoid blocking the Vert.x event loop
            userService.fetchUserRole(userId)
                .subscribe().with(
                    role -> {
                        if (role == null) role = "USER";

                        userIds.put(connection.id(), userId);
                        userConnections.put(userId, connection);
                        userRoles.put(userId, role);

                        connection.sendText("{\"type\":\"AUTH_SUCCESS\"}");

                        // Send current homepage chat permissions so the client knows whether it may chat
                        sendSettings(connection);

                        LOG.infof("User %d authenticated (role=%s) on connection %s",
                                userId, role, connection.id());

                        // Send recent global chat history now that the session is fully ready
                        try {
                            List<ChatMessageDTO> recentMessages = chatService.getRecentMessages(20);
                            for (ChatMessageDTO msg : recentMessages) {
                                sendJson(connection, "CHAT_MESSAGE", new JsonObject()
                                        .put("id", msg.getId())
                                        .put("userId", msg.getUserId())
                                        .put("username", msg.getUsername())
                                        .put("message", msg.getMessage())
                                        .put("createdAt", msg.getCreatedAt() != null ? msg.getCreatedAt().toString() : "")
                                );
                            }
                        } catch (Exception e) {
                            LOG.warnf("Failed to send chat history to user %d: %s", userId, e.getMessage());
                        }
                    },
                    failure -> {
                        LOG.warnf("Failed to fetch role for user %d: %s", userId, failure.getMessage());
                        // Default to USER role on failure
                        userIds.put(connection.id(), userId);
                        userConnections.put(userId, connection);
                        userRoles.put(userId, "USER");
                        connection.sendText("{\"type\":\"AUTH_SUCCESS\"}");
                        sendSettings(connection);
                    }
                );

        } catch (Exception e) {
            connection.sendText("{\"type\":\"AUTH_FAILED\"}");
            LOG.warnf("Authentication failed for connection %s", connection.id());
            metricsService.incrementWsAuthFailures();
            connection.close();
        }
    }

    private boolean isAdmin(Integer userId) {
        String role = userRoles.get(userId);
        return role != null && role.equalsIgnoreCase("ADMIN");
    }

    // ---------- CHAT ----------
    private void handleChatMessage(WebSocketConnection connection, JsonObject json) {
        Integer userId = userIds.get(connection.id());
        if (userId == null) {
            connection.sendText("{\"type\":\"AUTH_REQUIRED\"}");
            return;
        }

        // Role-based permission check: only admins can write chat
        boolean isAdmin = isAdmin(userId);
        boolean allowed = isAdmin && currentSettings.isAdminsCanChat();
        if (!allowed) {
            connection.sendText("{\"type\":\"CHAT_DENIED\"}");
            return;
        }

        JsonObject data = json.getJsonObject("data");
        ChatMessageDTO chatMessage = new ChatMessageDTO();
        chatMessage.setUserId(userId);
        chatMessage.setUsername(data.getString("username"));
        chatMessage.setMessage(data.getString("message"));
        chatMessage.setId((int) System.currentTimeMillis());
        chatMessage.setCreatedAt(java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Ulaanbaatar")));

        chatService.saveMessage(chatMessage);

        broadcastJson("CHAT_MESSAGE", new JsonObject()
                .put("id", chatMessage.getId())
                .put("userId", chatMessage.getUserId())
                .put("username", chatMessage.getUsername())
                .put("message", chatMessage.getMessage())
                .put("createdAt", chatMessage.getCreatedAt().toString())
        );
    }

    // ---------- CHAT SETTINGS (admin panel) ----------
    private void sendSettings(WebSocketConnection connection) {
        sendJson(connection, "CHAT_SETTINGS_UPDATE", settingsJson());
    }

    public void updateChatSettings(ChatSettings settings) {
        currentSettings = settings;
        broadcastJson("CHAT_SETTINGS_UPDATE", settingsJson());
    }

    private JsonObject settingsJson() {
        return new JsonObject()
                .put("usersCanChat", currentSettings.isUsersCanChat())
                .put("adminsCanChat", currentSettings.isAdminsCanChat());
    }

    // ---------- BALANCE UPDATE ----------
    public static void sendBalanceUpdate(Integer userId, UserBalance userBalance) {
        JsonObject msg = new JsonObject()
                .put("type", "BALANCE_UPDATE")
                .put("data", new JsonObject()
                        .put("userId", userId)
                        .put("balance", userBalance.getBalance())
                );

        sendToUser(userId, msg.toString());
    }

    // ---------- ADMIN BROADCAST ----------
    public void broadcastToAdmins(String message) {
        for (Map.Entry<Integer, WebSocketConnection> entry : userConnections.entrySet()) {
            Integer id = entry.getKey();
            WebSocketConnection conn = entry.getValue();

            if (conn.isOpen() && isAdmin(id)) {
                try {
                    conn.sendTextAndAwait(message);
                } catch (Exception e) {
                    LOG.error("Error sending admin message to user " + id, e);
                }
            }
        }
    }

    // ---------- DEPOSIT NOTIFICATION ----------
    public void sendDepositNotification(Integer userId, Integer amount, String type) {
        JsonObject msg = new JsonObject()
                .put("type", "ADMIN_DEPOSIT_NOTIFICATION")
                .put("data", new JsonObject()
                        .put("userId", userId)
                        .put("amount", amount)
                        .put("depositType", type)
                );
        broadcastToAdmins(msg.toString());
    }

    // ---------- WITHDRAWAL NOTIFICATION ----------
    public void sendWithdrawalNotification(Integer userId, Integer amount) {
        JsonObject msg = new JsonObject()
                .put("type", "ADMIN_WITHDRAWAL_NOTIFICATION")
                .put("data", new JsonObject()
                        .put("userId", userId)
                        .put("amount", amount)
                );
        broadcastToAdmins(msg.toString());
    }

    // ---------- UTILS ----------
    public static void sendToUser(Integer userId, String message) {
        WebSocketConnection conn = userConnections.get(userId);
        if (conn != null && conn.isOpen()) {
            try {
                conn.sendTextAndAwait(message);
            } catch (Exception e) {
                Logger.getLogger(GlobalSocket.class)
                        .error("Error sending message to user " + userId, e);
            }
        }
    }

    private void sendJson(WebSocketConnection connection, String type, JsonObject data) {
        JsonObject msg = new JsonObject().put("type", type).put("data", data);
        try {
            connection.sendTextAndAwait(msg.toString());
        } catch (Exception e) {
            LOG.error("Error sending message", e);
        }
    }

    public void broadcastJson(String type, JsonObject data) {
        JsonObject msg = new JsonObject().put("type", type).put("data", data);
        for (WebSocketConnection conn : userConnections.values()) {
            if (conn.isOpen()) {
                try {
                    conn.sendText(msg.toString()).subscribe().with(
                            v -> {},
                            err -> LOG.error("Error broadcasting message", err)
                    );
                } catch (Exception e) {
                    LOG.error("Error broadcasting message", e);
                }
            }
        }
    }

    public static void broadcast(String message) {
        for (WebSocketConnection conn : userConnections.values()) {
            if (conn.isOpen()) {
                try {
                    conn.sendTextAndAwait(message);
                } catch (Exception e) {
                    LOG.error("Error broadcasting message", e);
                }
            }
        }
    }

    // ---------- SYSTEM MESSAGE BROADCAST (admin → all players) ----------
    // Uses allSessions to reach every open connection, including multiple tabs per user.
    private static final Map<String, WebSocketConnection> allSessions = new ConcurrentHashMap<>();

    public static void broadcastSystemMessage(String message) {
        JsonObject msg = new JsonObject()
                .put("type", "SYSTEM_MESSAGE")
                .put("data", new JsonObject().put("message", message));
        String json = msg.toString();
        for (WebSocketConnection conn : allSessions.values()) {
            if (conn.isOpen()) {
                // sendText is non-blocking (returns Uni); subscribe fires it without blocking the caller.
                conn.sendText(json).subscribe().with(
                        ignored -> {},
                        error -> LOG.errorf("Error sending system message to %s: %s", conn.id(), error.getMessage())
                );
            }
        }
    }
}
