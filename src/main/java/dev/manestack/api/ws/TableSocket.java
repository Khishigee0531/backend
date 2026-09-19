package dev.manestack.api.ws;

import dev.manestack.service.GameService;
import dev.manestack.service.UserService;
import dev.manestack.service.socket.WebsocketEvent;
import io.quarkus.websockets.next.*;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

@WebSocket(path = "/ws/table")
public class TableSocket {

    private static final Logger LOG = Logger.getLogger(TableSocket.class);
    private static final String PING_MESSAGE = "PING";
    private static final String PONG_MESSAGE = "PONG";
    private static final int PING_INTERVAL = 10000; 

    @Inject
    GameService gameService;

    @Inject
    UserService userService;

    @Inject
    Vertx vertx;

    @OnOpen
    public void onOpen(WebSocketConnection connection) {
        try {
            gameService.handleOnConnectEvent(connection.id());
        } catch (Exception e) {
            LOG.error("Error handling onOpen event", e);
            safeClose(connection, "Error during connection setup");
            return;
        }

        schedulePing(connection);
    }

    @OnTextMessage
    public void onTextMessage(WebSocketConnection connection, String message) {
        if (message == null || message.isEmpty()) return;

        try {
            if (PING_MESSAGE.equals(message)) {
                safeSend(connection, PONG_MESSAGE);
                return;
            }

            WebsocketEvent event = parseEvent(message);
            if (event.getType() != null) {
                gameService.addWebsocketEventToQueue(connection.id(), event);
            } else {
                LOG.warn("Received event with null type, closing connection: " + connection.id());
                safeClose(connection, "Received event with null type");
            }
        } catch (Exception e) {
            LOG.errorf(e, "Error processing WebSocket message: %s", connection.id());
            safeClose(connection, "Error processing message");
        }
    }

    @OnClose
    public void onClose(WebSocketConnection connection) {
        LOG.infof("WebSocket connection closed: %s", connection.id());
        try {
            gameService.handleOnCloseEvent(connection.id());
        } catch (Exception e) {
            LOG.error("Error handling onClose event", e);
        }
    }

    private void schedulePing(WebSocketConnection connection) {
        vertx.setPeriodic(PING_INTERVAL, id -> {
            if (connection.isOpen()) {
                safeSend(connection, PING_MESSAGE);
            } else {
                vertx.cancelTimer(id);
            }
        });
    }

    private void safeSend(WebSocketConnection connection, String message) {
        try {
            if (connection != null && connection.isOpen()) {
                connection.sendText(message);
            }
        } catch (Exception e) {
            LOG.warnf(e, "Failed to send message to connection %s", connection.id());
        }
    }

    private void safeClose(WebSocketConnection connection, String reason) {
        try {
            if (connection != null && connection.isOpen()) {
                connection.close(new CloseReason(1000, reason));
            }
        } catch (Exception e) {
            LOG.warnf(e, "Failed to close connection %s", connection.id());
        }
    }


    private WebsocketEvent parseEvent(String message) {
        try {
            JsonObject json = new JsonObject(message);
            String id = json.getString("id");
            String type = json.getString("type");
            JsonObject data = json.getJsonObject("data");

            if (type == null || type.isEmpty()) type = "UNKNOWN";

            return new WebsocketEvent(id, type, data);
        } catch (Exception e) {
            LOG.error("Failed to parse WebSocket event", e);
            return new WebsocketEvent(); 
        }
    }
}
