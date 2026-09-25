package dev.manestack.service;

import dev.manestack.jooq.generated.tables.records.PokerTableRecord;
import dev.manestack.service.poker.card.GameCard;
import dev.manestack.domain.poker.GamePlayer;
import dev.manestack.service.poker.table.GameSession;
import dev.manestack.domain.poker.GameSessionSnapshot;
import dev.manestack.service.poker.table.GameTable;
import dev.manestack.service.socket.WebsocketEvent;
import dev.manestack.service.socket.WebsocketSession;
import dev.manestack.domain.user.UserBalance;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.websockets.next.OpenConnections;
import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.jwt.auth.principal.JWTParser;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.subscription.Cancellable;
import io.smallrye.mutiny.subscription.MultiEmitter;
import io.smallrye.mutiny.tuples.Tuple2;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.UpdateSetFirstStep;
import org.jooq.UpdateSetMoreStep;
import dev.manestack.api.ws.GlobalSocket;
import java.security.SecureRandom;
import dev.manestack.domain.user.User;
import io.vertx.core.json.JsonArray;
import dev.manestack.jooq.generated.Tables;
import dev.manestack.dto.poker.HandHistoryDTO;
import static org.jooq.impl.DSL.max;
import static org.jooq.impl.DSL.field;


import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import org.eclipse.microprofile.context.ManagedExecutor;
import jakarta.enterprise.inject.Instance;


import static dev.manestack.jooq.generated.Tables.POKER_GAME_SESSION;
import static dev.manestack.jooq.generated.Tables.POKER_TABLE;

@ApplicationScoped
public class GameService {
    private static final Logger LOG = Logger.getLogger(UserService.class);
    private volatile boolean shuttingDown = false;
    @Inject
    ManagedExecutor QUERY_THREADS;
    private static final int CPU_CORES = Runtime.getRuntime().availableProcessors();
    private final ExecutorService GAMEPLAY_THREAD = Executors.newFixedThreadPool(Math.max(8, CPU_CORES * 2));
    private final ExecutorService RESPONSE_SENDER_THREAD = Executors.newFixedThreadPool(Math.max(12, CPU_CORES * 3));
    private final Map<Long, GameTable> TABLES = new ConcurrentHashMap<>();
    private final Map<String, WebsocketSession> SOCKET_SESSIONS = new ConcurrentHashMap<>();
    public static final List<String> BOT_USERNAMES = new ArrayList<>();
    public static final Set<String> USED_BOT_USERNAMES = Collections.synchronizedSet(new HashSet<>());


    private MultiEmitter<? super WebsocketEvent> EVENT_HANDLER_EMITTER;
    private MultiEmitter<? super WebsocketEvent> EVENT_NOTIFIER_EMITTER;
    private Cancellable EVENT_HANDLER_TASK;
    private Cancellable EVENT_NOTIFIER_TASK;
    private Tuple2<LocalDateTime, LocalDateTime> MAINTENANCE_SCHEDULE = Tuple2.of(LocalDateTime.MIN, LocalDateTime.MIN);
    private static final AtomicLong BOT_ID_GEN = new AtomicLong(1_000_000);

    @Inject
    DSLContext context;
    @Inject
    JWTParser jwtParser;
    @Inject
    UserService userService;
    @Inject
    BalanceService balanceService;
    @Inject
    DepositService depositService;
    @Inject
    OpenConnections openConnections;
    @Inject
    GlobalSocket globalSocket;
    @Inject
    Instance<GameTable> tableFactory;
    @Inject
    MetricsService metricsService;
    @Inject
    jakarta.enterprise.inject.Instance<dev.manestack.service.tournament.TournamentService> tournamentServiceInstance;

  

    public void init(@Observes StartupEvent ignored) {
        fetchTablesFromDB().invoke(tables -> {
                    for (GameTable table : tables) {
                        table.connectToServer(this, userService);
                        markTournamentTables(table);
                        TABLES.put(table.getTableId(), table);
                    }
                })
                .subscribe().with(unused -> {
                });

        try (BufferedReader reader = new BufferedReader(new FileReader("./config/botusers.txt"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                BOT_USERNAMES.add(line.trim());
            }
        } catch (IOException e) {
        }

        Multi<WebsocketEvent> eventHandlerMulti = Multi.createFrom().emitter(em -> EVENT_HANDLER_EMITTER = em);

        EVENT_HANDLER_TASK = eventHandlerMulti
                .emitOn(GAMEPLAY_THREAD)
                .call(this::handleMessage)
                .onFailure().recoverWithMulti(() -> eventHandlerMulti)
                .subscribe().with(unused -> {
                        }, failure -> LOG.errorv("Socket open failed: {0}", failure.getMessage()),
                        () -> LOG.infov("Socket open completed"));

        Multi<WebsocketEvent> eventNotifierMulti = Multi.createFrom().emitter(em -> EVENT_NOTIFIER_EMITTER = em);

        EVENT_NOTIFIER_TASK = eventNotifierMulti
                .emitOn(RESPONSE_SENDER_THREAD)
                .call(this::sendMessageToConnection)
                .onFailure().recoverWithMulti(() -> eventNotifierMulti)
                .subscribe().with(unused -> {
                        }, failure -> LOG.errorv("Socket notifier failed: {0}", failure.getMessage()),
                        () -> LOG.infov("Socket notifier completed"));

        Multi<Long> tickMulti = Multi.createFrom().ticks().every(Duration.ofSeconds(1))
                .emitOn(GAMEPLAY_THREAD)
                .onItem().invoke(() -> {
                    LOG.debug("Handling auto actions for all tables");
                    for (GameTable table : TABLES.values()) {
                        try {
                            table.handleAutoActions();
                        } catch (Exception e) {
                            LOG.errorv(e, "Error handling auto actions for table {0}: {1}", table.getTableId(), e.getMessage());
                        }
                    }
                });
        tickMulti
                .onFailure().recoverWithMulti(unused -> tickMulti)
                .subscribe().with(unused -> LOG.debug("Auto action handling completed"), throwable -> LOG.errorv(throwable, "Error in auto action handling check: {0}", throwable.getMessage()));
    }

    public void shutdown(@Observes ShutdownEvent ignored) {
        shuttingDown = true;
        if (EVENT_HANDLER_TASK != null) {
            EVENT_HANDLER_TASK.cancel();
        }
        if (EVENT_HANDLER_EMITTER != null) {
            EVENT_HANDLER_EMITTER.complete();
        }
        if (EVENT_NOTIFIER_TASK != null) {
            EVENT_NOTIFIER_TASK.cancel();
        }
        if (EVENT_NOTIFIER_EMITTER != null) {
            EVENT_NOTIFIER_EMITTER.complete();
        }
        GAMEPLAY_THREAD.shutdown();
        LOG.infov("GameService shutdown completed");
    }

    /*
     * Socket Events
     */
    private void handleConnectedEvent(WebsocketSession session, WebsocketEvent event) {
        EVENT_NOTIFIER_EMITTER.emit(event);
    }

    private Uni<Void> handleDisconnectEvent(WebsocketSession session, WebsocketEvent event) {
        if (session.getTable() != null) {
            GameTable gameTable = session.getTable();

            // ✅ If the disconnected user is an admin, don't trigger player disconnect logic
            if (session.getUser() != null && session.getUser().getRole() == User.Role.ADMIN) {
                LOG.infov("Admin {0} disconnected from table {1}. Keeping table and bots intact.",
                        session.getUser().getUsername(), gameTable.getTableName());
                SOCKET_SESSIONS.remove(session.getId());
                return Uni.createFrom().voidItem();
            }

            // Normal players — run disconnect logic
            return gameTable.notifyPlayerDisconnect(session.getUser().getUserId(), session)
                    .invoke(() -> SOCKET_SESSIONS.remove(session.getId()));
        } else {
            SOCKET_SESSIONS.remove(session.getId());
            return Uni.createFrom().voidItem();
        }
    }

    private Uni<Void> handleAuthEvent(WebsocketSession session, WebsocketEvent event) {
        return Uni.createFrom().voidItem()
                .call(() -> {
                    String accessToken = event.getData().getString("accessToken");
                    try {
                        Integer userId = Integer.parseInt(jwtParser.parse(accessToken).getSubject());
                        return userService.fetchUser(userId)
                                .invoke(session::setUser)
                                .chain(() -> balanceService.fetchUserBalance(userId))
                                .invoke(userBalance -> sendAuthResponse(session, userBalance));
                    } catch (Exception e) {
                        LOG.errorv("Invalid token: {0}", e.getMessage());
                        return Uni.createFrom().voidItem();
                    }
                });
    }

    public void sendAuthResponse(WebsocketSession session, UserBalance userBalance) {
        EVENT_NOTIFIER_EMITTER.emit(new WebsocketEvent(
                session.getId(),
                "AUTH",
                new JsonObject()
                        .put("user", session.getUser())
                        .put("balance", userBalance.getBalance())
        ));
    }

    private Uni<Void> handleTableEvent(WebsocketSession session, WebsocketEvent event) {
        Long tableId = event.getData().getLong("tableId");
        GameTable.TableAction action = GameTable.TableAction.valueOf(event.getData().getString("action"));
        GameTable table = TABLES.get(tableId);
        if (table == null) {
            LOG.warnv("Table not found: {0}", tableId);
            EVENT_NOTIFIER_EMITTER.emit(new WebsocketEvent(
                    session != null ? session.getId() : event.getId(),
                    "TABLE_NOT_FOUND",
                    new JsonObject().put("tableId", tableId)
            ));
            return Uni.createFrom().voidItem();
        }
        switch (action) {
            case TAKE_SEAT -> {
            Integer seatNumber = event.getData().getInteger("seatIndex");
            Integer amount = event.getData().getInteger("amount", 0);
            Boolean isBot = event.getData().getBoolean("isBot", false);
            String botName = event.getData().getString("botName");
            Long tournamentId = event.getData().getLong("tournamentId");

            if (Boolean.TRUE.equals(isBot)) {
                Boolean isGoodBot = event.getData().getBoolean("isGoodBot", false);
                String botAvatar = event.getData().getString("botAvatar");
                int seatAmount = amount;

                if (table.isTournamentTable() || tournamentId != null) {
                    // Tournament bots always start with the tournament stack — never cash buy-in limits.
                    seatAmount = resolveTournamentBotChips(tournamentId != null ? tournamentId : table.getTournamentId());
                } else if (amount < table.getMinBuyIn() || amount > table.getMaxBuyIn()) {
                    LOG.warnv("Invalid buy-in amount {0} for table {1}", amount, tableId);
                    EVENT_NOTIFIER_EMITTER.emit(new WebsocketEvent(
                            event.getId(),
                            "ERROR",
                            new JsonObject().put("error", "Invalid buy-in amount")
                    ));
                    return Uni.createFrom().voidItem();
                }

                User botUser = new User();
                botUser.setUserId((int) BOT_ID_GEN.getAndIncrement());
                botUser.setUsername(botName != null ? botName : (Boolean.TRUE.equals(isGoodBot) ? "GoodBot" : "Bot"));
                botUser.setRole(User.Role.BOT);
                if (botAvatar != null && !botAvatar.isEmpty()) {
                    botUser.setAvatar(botAvatar);
                }

                GamePlayer botPlayer = new GamePlayer(botUser, seatAmount);
                botPlayer.setGoodBot(Boolean.TRUE.equals(isGoodBot));

                return table.takeSeat(seatNumber, botPlayer, null, true)
                        .replaceWithVoid();
            }

            if (tournamentId != null || table.isTournamentTable()) {
                final Long effectiveTournamentId =
                        tournamentId != null ? tournamentId : table.getTournamentId();
                return Uni.createFrom().voidItem()
                        .emitOn(QUERY_THREADS)
                        .chain(unused -> {
                            int chips = tournamentServiceInstance.get()
                                    .validateTournamentTakeSeat(
                                            session.getUser().getUserId(),
                                            effectiveTournamentId,
                                            tableId,
                                            seatNumber);
                            GamePlayer gamePlayer = new GamePlayer(session.getUser(), chips);
                            session.setTable(table);
                            return table.takeSeat(seatNumber, gamePlayer, session, false);
                        })
                        .replaceWithVoid()
                        .onFailure().invoke(err -> {
                            LOG.warnv("Tournament take-seat failed for user {0}: {1}",
                                    session.getUser().getUserId(), err.getMessage());
                            EVENT_NOTIFIER_EMITTER.emit(new WebsocketEvent(
                                    event.getId(),
                                    "ERROR",
                                    new JsonObject().put("error", err.getMessage() != null ? err.getMessage() : "Cannot join tournament table")
                            ));
                        });
            }

            if (amount < table.getMinBuyIn() || amount > table.getMaxBuyIn()) {
                LOG.warnv("Invalid buy-in amount {0} for table {1}", amount, tableId);
                EVENT_NOTIFIER_EMITTER.emit(new WebsocketEvent(
                        event.getId(),
                        "ERROR",
                        new JsonObject().put("error", "Invalid buy-in amount")
                ));
                return Uni.createFrom().voidItem();
            }

            return balanceService.fetchUserBalance(session.getUser().getUserId())
                    .call(userBalance -> {
                        if (userBalance.getBalance() < amount) {
                            LOG.errorv("Insufficient balance for user {0} at table {1}", session.getUser().getUserId(), tableId);
                            EVENT_NOTIFIER_EMITTER.emit(new WebsocketEvent(
                                    event.getId(),
                                    "ERROR",
                                    new JsonObject().put("error", "Insufficient balance")
                            ));
                            return Uni.createFrom().voidItem();
                        }
                        return depositService.createOutcomeRecord(session.getUser().getUserId(), -amount, "BUY_IN")
                                .call(() -> depositService.approveOutcomeRecords(session.getUser().getUserId()))
                                .call(() -> {
                                    GamePlayer gamePlayer = new GamePlayer(session.getUser(), amount);
                                    session.setTable(table);
                                    return table.takeSeat(seatNumber, gamePlayer, session, false);
                                });
                    })
                    .replaceWithVoid();
        }

            case LEAVE_SEAT -> {
                Integer seatNumber = event.getData().getInteger("seatIndex");
                return table.leaveSeat(session.getUser().getUserId(), session)
                        .invoke(() -> {
                            session.setTable(null);
                            LOG.infov("User {0} left seat {1} at table {2}", event.getId(), seatNumber, tableId);
                            EVENT_NOTIFIER_EMITTER.emit(new WebsocketEvent(
                                    event.getId(),
                                    "TABLE",
                                    new JsonObject()
                                            .put("action", "LEAVE_SEAT")
                                            .put("tableId", tableId)
                                            .put("table", table)
                            ));
                        });
            }
            case RECONNECT -> {
                LOG.infov("User {0} rejoined to table {1}", session.getUser(), tableId);
                GameTable previous = session.getTable();
                if (previous != null && previous != table) {
                    previous.unsubscribe(session);
                }
                session.setTable(table);
                table.subscribe(session, true);
            }
            case SUBSCRIBE -> {
                LOG.infov("User {0} subscribed to table {1}", session.getUser(), tableId);
                GameTable previous = session.getTable();
                if (previous != null && previous != table) {
                    previous.unsubscribe(session);
                }
                session.setTable(table);
                table.subscribe(session, false);
            }
            case RECHARGE -> {
                if (tournamentServiceInstance.get().isTournamentTable(tableId)) {
                    LOG.warnv("Cash recharge blocked on tournament table {0} for user {1}",
                            tableId, session.getUser().getUserId());
                    EVENT_NOTIFIER_EMITTER.emit(new WebsocketEvent(
                            event.getId(),
                            "ERROR",
                            new JsonObject().put("error", "Recharge is not allowed in tournaments")
                    ));
                    return Uni.createFrom().voidItem();
                }
                GamePlayer gamePlayer = table.getSeats().values().stream().filter(Objects::nonNull)
                        .filter(player -> player.getUser().getUserId() == session.getUser().getUserId())
                        .findFirst()
                        .orElse(null);
                Integer amount = event.getData().getInteger("amount", 0);
                if (gamePlayer != null) {
                    return balanceService.fetchUserBalance(session.getUser().getUserId())
                            .call(userBalance -> {
                                if (userBalance.getBalance() < amount) {
                                    LOG.errorv("Insufficient balance for user {0} at table {1}", session.getUser().getUserId(), tableId);
                                    EVENT_NOTIFIER_EMITTER.emit(new WebsocketEvent(
                                            event.getId(),
                                            "ERROR",
                                            new JsonObject().put("error", "Insufficient balance")
                                    ));
                                    return Uni.createFrom().voidItem();
                                }
                                return depositService.createOutcomeRecord(session.getUser().getUserId(), -amount, "RECHARGE")
                                        .invoke(() -> table.notifyPlayerRecharge(session.getUser().getUserId(), amount))
                                        .call(() -> depositService.approveOutcomeRecords(session.getUser().getUserId()));
                            })
                            .call(() -> {
                                LOG.infov("User {0} recharged at table {1}", session.getUser().getUserId(), tableId);
                                return Uni.createFrom().voidItem();
                            })
                            .replaceWithVoid();
                } else {
                    LOG.errorv("User {0} is not seated at table {1}", session.getUser().getUserId(), tableId);
                    EVENT_NOTIFIER_EMITTER.emit(new WebsocketEvent(
                            event.getId(),
                            "ERROR",
                            new JsonObject().put("error", "User not seated at table")
                    ));
                }
            }
        }
        return Uni.createFrom().voidItem();
    }

    private Uni<Void> handleGameEvent(WebsocketSession session, WebsocketEvent event) {
        long startTime = System.nanoTime();
        Long tableId = event.getData().getLong("tableId");
        GameSession.ActionType action = GameSession.ActionType.valueOf(event.getData().getString("action"));
        Integer amount = event.getData().getInteger("amount", 0);
        
        LOG.infov("🎮 [GAME ACTION RECEIVED] action={0}, userId={1}, tableId={2}, amount={3}, timestamp={4}",
                action, session != null && session.getUser() != null ? session.getUser().getUserId() : -1,
                tableId, amount, java.time.LocalDateTime.now());
        
        if (session == null) {
            LOG.errorv("Session not found for {0}", event.getId());
            return Uni.createFrom().voidItem();
        }
        GameTable table = TABLES.get(tableId);
        if (table == null) {
            LOG.warnv("Game event: table not found: {0}", tableId);
            return Uni.createFrom().voidItem();
        }
        try {
            long beforeAction = System.nanoTime();
            table.receivePlayerAction(session.getUser().getUserId(), action, amount);
            long actionDuration = (System.nanoTime() - beforeAction) / 1_000_000;
            long totalDuration = (System.nanoTime() - startTime) / 1_000_000;
            
            // Record metrics
            metricsService.incrementGameActions();
            metricsService.recordGameActionDuration(totalDuration);
            
            LOG.infov("✅ [ACTION PROCESSED] action={0}, actionTime={1}ms, totalTime={2}ms",
                    action, actionDuration, totalDuration);
        } catch (Exception e) {
            LOG.errorv(e, "Failed to process game action {0} for user {1} at table {2}: {3}", action, session.getUser().getUserId(), tableId, e.getMessage());
            EVENT_NOTIFIER_EMITTER.emit(new WebsocketEvent(
                    event.getId(),
                    "ERROR",
                    new JsonObject().put("error", e.getMessage())
            ));
        }
        return Uni.createFrom().voidItem();
    }

    private Uni<Void> handleChatEvent(WebsocketSession session, WebsocketEvent event) {
        LOG.infov("Received chat event for {0}: {1}", event.getId(), event.getData());
        Long tableId = event.getData().getLong("tableId");
        String message = event.getData().getString("message", "");
        if (session == null) {
            LOG.errorv("Session not found for {0}", event.getId());
            return Uni.createFrom().voidItem();
        }
        GameTable table = TABLES.get(tableId);
        if (table == null) {
            LOG.warnv("Chat event: table not found: {0}", tableId);
            return Uni.createFrom().voidItem();
        } else if (session.getUser() == null) {
            LOG.errorv("User not found for session {0}", session.getId());
            return Uni.createFrom().voidItem();
        }
        return Uni.createFrom().voidItem()
                .emitOn(GAMEPLAY_THREAD)
                .invoke(unused -> table.sendChatToParticipants(session.getUser().getUsername(), message));
    }

    private Uni<Void> handleMessage(WebsocketEvent event) {
        return Uni.createFrom().voidItem()
                .call(() -> {
                    WebsocketSession session = SOCKET_SESSIONS.get(event.getId());
                    if (session == null) {
                        LOG.errorv("Session not found for {0}", event.getId());
                        return Uni.createFrom().voidItem();
                    }
                    switch (event.getType()) {
                        case "CONNECTED" -> handleConnectedEvent(session, event);
                        case "DISCONNECTED" -> {
                            return handleDisconnectEvent(session, event);
                        }
                        case "TABLE" -> {
                            return handleTableEvent(session, event);
                        }
                        case "GAME" -> {
                            return handleGameEvent(session, event);
                        }
                        case "AUTH" -> {
                            return handleAuthEvent(session, event);
                        }
                        case "CHAT" -> {
                            return handleChatEvent(session, event);
                        }
                        default -> LOG.infov("Received unknown event for {0}: {1}", event.getId(), event.getData());
                    }
                    return Uni.createFrom().voidItem();
                })
                .onFailure().recoverWithUni(throwable -> {
                    LOG.errorv(throwable, "Error handling event {0}: {1}", event.getId(), throwable.getMessage());
                    EVENT_NOTIFIER_EMITTER.emit(new WebsocketEvent(
                            event.getId(),
                            "ERROR",
                            new JsonObject().put("error", throwable.getMessage())
                    ));
                    return Uni.createFrom().voidItem();
                });
    }

    public void sendWebsocketEvent(WebsocketEvent event) {
        EVENT_NOTIFIER_EMITTER.emit(event);
    }

    private Uni<Void> sendMessageToConnection(WebsocketEvent event) {
        try {
            Optional<WebSocketConnection> optionalConnection = openConnections.findByConnectionId(event.getId());
            if (optionalConnection.isPresent()) {
                WebSocketConnection connection = optionalConnection.get();
                return connection.sendText(event)
                        .ifNoItem().after(Duration.ofSeconds(2)).fail()
                        .onFailure().recoverWithUni(throwable -> Uni.createFrom().voidItem());
            } else {
                return Uni.createFrom().voidItem();
            }
        } catch (Exception e) {
            LOG.errorv(e, "Failed to send event {0} to connection {1}: {2}", event.getType(), event.getId(), e.getMessage());
            return Uni.createFrom().voidItem();
        }
    }

    public void notifyBalanceUpdate(Integer userId, UserBalance userBalance) {
        for (GameTable table : TABLES.values()) {
            table.notifyBalanceUpdate(userId, userBalance);
        }
        GlobalSocket.sendBalanceUpdate(userId, userBalance);
    }

    /*
     * Websocket Event Emitters
     */

    public void handleOnConnectEvent(String id) {
        SOCKET_SESSIONS.put(id, new WebsocketSession(id));
        addWebsocketEventToQueue(id, new WebsocketEvent(
                id,
                "CONNECTED",
                new JsonObject()
        ));
    }

    public void handleOnCloseEvent(String id) {
        addWebsocketEventToQueue(id, new WebsocketEvent(
                id,
                "DISCONNECTED",
                new JsonObject()
        ));
    }

    public void addWebsocketEventToQueue(String id, WebsocketEvent event) {
        event.setId(id);
        EVENT_HANDLER_EMITTER.emit(event);
    }

    /*
     * CRUD Operations
     */

    public Uni<Collection<GameTable>> fetchTablesFromDB() {
        return Uni.createFrom().voidItem()
                .emitOn(QUERY_THREADS)
                .map(unused -> {
                    if (shuttingDown) return List.<GameTable>of();
                    return context.selectFrom(POKER_TABLE)
                            .fetch()
                            .stream()
                            .map(record -> {
                                GameTable table = tableFactory.get();
                                table.loadFromRecord(record);
                                return table;
                            })
                            .toList();
                });
    }

    
    public Uni<Collection<GameTable>> fetchTables() {
        if (shuttingDown) return Uni.createFrom().item(List.of());

        Collection<GameTable> memoryTables = TABLES.values();

        if (!memoryTables.isEmpty()) {
            LOG.infov("Fetching tables from memory, count={0}", memoryTables.size());
            return Uni.createFrom().item(memoryTables.stream()
                    .filter(t -> !t.isTournamentTable())
                    .toList());
        }

        LOG.info("Memory empty, fetching tables from DB");
        return fetchTablesFromDB()
                .onItem().invoke(dbTables -> {
                    if (shuttingDown) return;
                    LOG.infov("Fetched {0} tables from DB, populating memory", dbTables.size());
                    dbTables.forEach(table -> {
                        markTournamentTables(table);
                        TABLES.put(table.getTableId(), table);
                    });
                })
                .map(dbTables -> dbTables.stream()
                        .filter(t -> !t.isTournamentTable())
                        .toList());
    }

    private void markTournamentTables(GameTable table) {
        try {
            var row = context.select(dev.manestack.jooq.generated.Tables.POKER_TOURNAMENT_TABLE.TOURNAMENT_ID)
                    .from(dev.manestack.jooq.generated.Tables.POKER_TOURNAMENT_TABLE)
                    .where(dev.manestack.jooq.generated.Tables.POKER_TOURNAMENT_TABLE.TABLE_ID.eq(table.getTableId()))
                    .fetchOne();
            if (row != null) {
                table.setTournamentId(row.value1());
            }
        } catch (Exception e) {
            LOG.debugv("No tournament mapping for table {0}: {1}", table.getTableId(), e.getMessage());
        }
    }

    /** Remove a tournament player from a virtual table live seat (no cash unlock). */
    public Uni<Void> removeTournamentPlayer(Long tableId, Integer userId) {
        return Uni.createFrom().voidItem()
                .emitOn(GAMEPLAY_THREAD)
                .chain(unused -> {
                    GameTable table = TABLES.get(tableId);
                    if (table == null) return Uni.createFrom().voidItem();
                    return table.removeTournamentPlayer(userId);
                });
    }

    /** Starting chips for a tournament bot seat; falls back to table max buy-in. */
    private int resolveTournamentBotChips(Long tournamentId) {
        try {
            if (tournamentId != null) {
                var t = context.select(dev.manestack.jooq.generated.Tables.POKER_TOURNAMENT.STARTING_CHIPS)
                        .from(dev.manestack.jooq.generated.Tables.POKER_TOURNAMENT)
                        .where(dev.manestack.jooq.generated.Tables.POKER_TOURNAMENT.TOURNAMENT_ID.eq(tournamentId))
                        .fetchOne(dev.manestack.jooq.generated.Tables.POKER_TOURNAMENT.STARTING_CHIPS);
                if (t != null && t > 0) return t.intValue();
            }
        } catch (Exception e) {
            LOG.debugv("Could not load tournament starting chips for {0}: {1}", tournamentId, e.getMessage());
        }
        return 10_000;
    }

    /** Called when a tournament player's stack hits 0 after a hand. */
    public void notifyTournamentBust(Long tournamentId, Integer userId, Long tableId) {
        try {
            var ts = tournamentServiceInstance.get();
            long remaining = context.fetchCount(dev.manestack.jooq.generated.Tables.POKER_TOURNAMENT_ENTRY,
                    dev.manestack.jooq.generated.Tables.POKER_TOURNAMENT_ENTRY.TOURNAMENT_ID.eq(tournamentId)
                            .and(dev.manestack.jooq.generated.Tables.POKER_TOURNAMENT_ENTRY.STATUS
                                    .ne("ELIMINATED"))
                            .and(dev.manestack.jooq.generated.Tables.POKER_TOURNAMENT_ENTRY.STATUS.ne("CANCELLED")));
            Integer finalPosition = remaining > 0 ? (int) remaining : null;
            ts.eliminate(tournamentId, userId, finalPosition)
                    .chain(() -> ts.rebalanceTables(tournamentId))
                    .subscribe().with(
                            entry -> LOG.infov("Tournament bust eliminated user {0} in T{1}", userId, tournamentId),
                            err -> LOG.errorv("Tournament bust failed for user {0}: {1}", userId, err.getMessage()));
        } catch (Exception e) {
            LOG.errorv(e, "notifyTournamentBust failed for user {0}", userId);
        }
    }

    /** Remove a tournament virtual table from memory (and optionally delete the cash-table row). */
    public Uni<Void> destroyTournamentTable(Long tableId, boolean deleteRow) {
        return Uni.createFrom().voidItem()
                .emitOn(QUERY_THREADS)
                .invoke(unused -> {
                    GameTable table = TABLES.get(tableId);
                    if (table != null && table.isTournamentTable()) {
                        try {
                            table.removeAllTournamentPlayers().await().indefinitely();
                        } catch (Exception e) {
                            LOG.warnv("Failed clearing players on tournament table {0}: {1}", tableId, e.getMessage());
                        }
                    }
                    TABLES.remove(tableId);
                    if (deleteRow) {
                        try {
                            context.deleteFrom(Tables.POKER_HAND_HISTORY)
                                    .where(Tables.POKER_HAND_HISTORY.TABLE_ID.eq(tableId.intValue()))
                                    .execute();
                        } catch (Exception ignored) {
                        }
                        context.deleteFrom(POKER_TABLE)
                                .where(POKER_TABLE.TABLE_ID.eq(tableId))
                                .execute();
                    }
                    LOG.infov("Destroyed tournament table {0} (deleteRow={1})", tableId, deleteRow);
                });
    }

    /** Move a tournament player from one virtual table to another (physical seat move). */
    public Uni<Void> moveTournamentPlayer(Long fromTableId, Long toTableId, Integer userId, Integer seatNumber, Integer chips) {
        return Uni.createFrom().voidItem()
                .emitOn(GAMEPLAY_THREAD)
                .chain(unused -> {
                    GameTable from = TABLES.get(fromTableId);
                    GameTable to = TABLES.get(toTableId);
                    if (to == null) {
                        return Uni.createFrom().failure(new IllegalStateException("Destination table not found: " + toTableId));
                    }
                    WebsocketSession found = null;
                    if (from != null) {
                        found = from.sessionsFor(userId).stream().findFirst().orElse(null);
                        from.removeTournamentPlayer(userId).await().indefinitely();
                    }
                    final WebsocketSession session = found;
                    GamePlayer player = session != null && session.getUser() != null
                            ? new GamePlayer(session.getUser(), chips)
                            : null;
                    if (player == null) {
                        LOG.warnv("Cannot physically move user {0} — no session; DB assignment only", userId);
                        return Uni.createFrom().voidItem();
                    }
                    final GameTable dest = to;
                    final int seat = seatNumber != null ? seatNumber : findFreeSeat(to);
                    return dest.takeSeat(seat, player, session, false)
                            .invoke(() -> {
                                if (session != null) session.setTable(dest);
                                dest.subscribe(session, false);
                                gameServiceNotifyTableMoved(session, dest, seat, chips);
                            });
                });
    }

    private int findFreeSeat(GameTable table) {
        java.util.Set<Integer> used = table.occupiedSeats();
        int max = table.getMaxPlayers() != null ? table.getMaxPlayers() : 9;
        for (int i = 0; i < max; i++) {
            if (!used.contains(i)) return i;
        }
        return 0;
    }

    private void gameServiceNotifyTableMoved(WebsocketSession session, GameTable to, int seat, Integer chips) {
        if (session == null) return;
        sendWebsocketEvent(new WebsocketEvent(
                session.getId(),
                "TOURNAMENT_TABLE_MOVED",
                new JsonObject()
                        .put("tableId", to.getTableId())
                        .put("secureId", to.getSecureId())
                        .put("tableName", to.getTableName())
                        .put("seatNumber", seat)
                        .put("chips", chips)
                        .put("tournamentId", to.getTournamentId())
        ));
    }
   
    public Uni<GameTable> fetchTableBySecureId(String secureId) {
        return TABLES.values().stream()
                    .filter(t -> t.getSecureId().equals(secureId))
                    .findFirst()
                    .map(table -> Uni.createFrom().item(table))
                    .orElse(Uni.createFrom().nullItem());
    }

    public Uni<GameTable> fetchTableById(Long tableId) {
        GameTable table = TABLES.get(tableId);
        if (table != null) {
            return Uni.createFrom().item(table);
        }
        return Uni.createFrom().nullItem();
    }

    public Uni<JsonObject> fetchTableSessionState(Long tableId, Integer userId) {
        GameTable table = TABLES.get(tableId);
        if (table == null) {
            return Uni.createFrom().nullItem();
        }
        return Uni.createFrom().item(table.getMySessionState(userId));
    }

    public Uni<GameTable> createTable(Integer userId, GameTable input) {

        LOG.infov("Creating table '{0}' with rakePercent={1}", input.getTableName(), input.getRakePercent());

        return Uni.createFrom().voidItem()
                .emitOn(QUERY_THREADS)
                .map(unused -> {
                    if (shuttingDown) throw new IllegalStateException("Application is shutting down");
                    input.validateCreate();

                    GameTable table = tableFactory.get();
                    table.setTableName(input.getTableName());
                    table.setMaxPlayers(input.getMaxPlayers());
                    table.setBigBlind(input.getBigBlind());
                    table.setSmallBlind(input.getSmallBlind());
                    table.setMinBuyIn(input.getMinBuyIn());
                    table.setMaxBuyIn(input.getMaxBuyIn());
                    table.setGameVariant(input.getGameVariant());
                    table.setRakePercent(input.getRakePercent());

                    PokerTableRecord pokerTableRecord = context.insertInto(POKER_TABLE)
                            .set(POKER_TABLE.TABLE_NAME, table.getTableName())
                            .set(POKER_TABLE.MAX_PLAYERS, table.getMaxPlayers())
                            .set(POKER_TABLE.BIG_BLIND, table.getBigBlind())
                            .set(POKER_TABLE.SMALL_BLIND, table.getSmallBlind())
                            .set(POKER_TABLE.MIN_BUY_IN, table.getMinBuyIn())
                            .set(POKER_TABLE.MAX_BUY_IN, table.getMaxBuyIn())
                            .set(POKER_TABLE.VARIANT, table.getGameVariant())
                            .set(POKER_TABLE.RAKE_PERCENT, BigDecimal.valueOf(table.getRakePercent()))
                            .set(POKER_TABLE.CREATED_AT, OffsetDateTime.now())
                            .set(POKER_TABLE.CREATED_BY, userId)
                            .set(field("card_bg_color", String.class), table.getCardBgColor())
                            .returning(POKER_TABLE.TABLE_ID)
                            .fetchOne();


                    if (pokerTableRecord != null) {
                        table.setTableId(pokerTableRecord.getTableId());
                        table.setCreatedAt(pokerTableRecord.getCreatedAt());
                        table.setCreatedBy(pokerTableRecord.getCreatedBy());

                        // Generate an 8-character secure ID
                        UUID secureId = UUID.randomUUID();
                        table.setSecureId(secureId.toString());
                        context.update(POKER_TABLE)
                            .set(POKER_TABLE.SECURE_ID, secureId.toString())
                            .where(POKER_TABLE.TABLE_ID.eq(table.getTableId()))
                            .execute();

                        TABLES.put(table.getTableId(), table);
                        table.connectToServer(this, userService);

                        return table;
                    } else {
                        LOG.errorv("Failed to create table {0}", table.getTableName());
                        throw new RuntimeException("Failed to create table");
                    }
                });
    }

    public Uni<GameTable> updateTable(GameTable table) {
        return Uni.createFrom().voidItem()
                .emitOn(QUERY_THREADS)
                .map(unused -> {
                    if (shuttingDown) throw new IllegalStateException("Application is shutting down");
                    UpdateSetFirstStep<?> update = context.update(POKER_TABLE);
                    UpdateSetMoreStep<?> updateSetMoreStep = null;
                    if (table.getTableName() != null)
                        updateSetMoreStep = update.set(POKER_TABLE.TABLE_NAME, table.getTableName());
                    if (table.getMaxPlayers() != null)
                        updateSetMoreStep = updateSetMoreStep != null ? updateSetMoreStep.set(POKER_TABLE.MAX_PLAYERS, table.getMaxPlayers()) : update.set(POKER_TABLE.MAX_PLAYERS, table.getMaxPlayers());
                    if (table.getBigBlind() != null)
                        updateSetMoreStep = updateSetMoreStep != null ? updateSetMoreStep.set(POKER_TABLE.BIG_BLIND, table.getBigBlind()) : update.set(POKER_TABLE.BIG_BLIND, table.getBigBlind());
                    if (table.getSmallBlind() != null)
                        updateSetMoreStep = updateSetMoreStep != null ? updateSetMoreStep.set(POKER_TABLE.SMALL_BLIND, table.getSmallBlind()) : update.set(POKER_TABLE.SMALL_BLIND, table.getSmallBlind());
                    if (table.getMinBuyIn() != null)
                        updateSetMoreStep = updateSetMoreStep != null ? updateSetMoreStep.set(POKER_TABLE.MIN_BUY_IN, table.getMinBuyIn()) : update.set(POKER_TABLE.MIN_BUY_IN, table.getMinBuyIn());
                    if (table.getMaxBuyIn() != null)
                        updateSetMoreStep = updateSetMoreStep != null ? updateSetMoreStep.set(POKER_TABLE.MAX_BUY_IN, table.getMaxBuyIn()) : update.set(POKER_TABLE.MAX_BUY_IN, table.getMaxBuyIn());
                    if (table.getGameVariant() != null)
                        updateSetMoreStep = updateSetMoreStep != null ? updateSetMoreStep.set(POKER_TABLE.VARIANT, table.getGameVariant()): update.set(POKER_TABLE.VARIANT, table.getGameVariant());
                    if (table.getRakePercent() > 0)
                        updateSetMoreStep = updateSetMoreStep != null ? updateSetMoreStep.set(POKER_TABLE.RAKE_PERCENT, BigDecimal.valueOf(table.getRakePercent())) : update.set(POKER_TABLE.RAKE_PERCENT, BigDecimal.valueOf(table.getRakePercent()));
                    if (table.getCardBgColor() != null)
                        updateSetMoreStep = updateSetMoreStep != null ? updateSetMoreStep.set(field("card_bg_color", String.class), table.getCardBgColor()) : update.set(field("card_bg_color", String.class), table.getCardBgColor());
                    if (updateSetMoreStep == null) {
                        LOG.errorv("No fields to update for table {0}", table.getTableName());
                        throw new RuntimeException("No fields to update");
                    }
                    GameTable updatedTable = updateSetMoreStep
                            .where(POKER_TABLE.TABLE_ID.eq(table.getTableId()))
                            .returning()
                            .fetchOneInto(GameTable.class);

                    if (updatedTable != null) {
                        LOG.infov("Updated table {0}", table.getTableName());
                        GameTable existing = TABLES.get(table.getTableId());
                        if (existing != null) {
                            if (table.getTableName() != null) existing.setTableName(table.getTableName());
                            if (table.getMaxPlayers() != null) existing.setMaxPlayers(table.getMaxPlayers());
                            if (table.getBigBlind() != null) existing.setBigBlind(table.getBigBlind());
                            if (table.getSmallBlind() != null) existing.setSmallBlind(table.getSmallBlind());
                            if (table.getMinBuyIn() != null) existing.setMinBuyIn(table.getMinBuyIn());
                            if (table.getMaxBuyIn() != null) existing.setMaxBuyIn(table.getMaxBuyIn());
                            if (table.getGameVariant() != null) existing.setGameVariant(table.getGameVariant());
                            if (table.getRakePercent() > 0) existing.setRakePercent(table.getRakePercent());
                            if (table.getCardBgColor() != null) existing.setCardBgColor(table.getCardBgColor());
                            return existing;
                        }
                        if (table.getCardBgColor() != null) updatedTable.setCardBgColor(table.getCardBgColor());
                        TABLES.put(table.getTableId(), updatedTable);
                        updatedTable.connectToServer(this, userService);
                        return updatedTable;
                    } else {
                        LOG.errorv("Failed to update table {0}", table.getTableName());
                        throw new RuntimeException("Failed to update table");
                    }
                });
    }

    public Uni<Void> deleteTable(Long tableId, Integer userId) {
        return Uni.createFrom().voidItem()
                .emitOn(QUERY_THREADS)
                .map(unused -> {
                    if (shuttingDown) throw new IllegalStateException("Application is shutting down");
                    LOG.infov("User {0} is deleting table {1}", userId, tableId);
                    // Delete poker hand history first to avoid foreign key constraint violation
                    context.deleteFrom(Tables.POKER_HAND_HISTORY)
                            .where(Tables.POKER_HAND_HISTORY.TABLE_ID.eq(tableId.intValue()))
                            .execute();
                    context.deleteFrom(POKER_TABLE)
                            .where(POKER_TABLE.TABLE_ID.eq(tableId))
                            .execute();
                    TABLES.remove(tableId);
                    LOG.infov("Deleted table {0}", tableId);
                    return null;
                });
    }
    public Uni<List<GameSessionSnapshot>> fetchGameSessionSnapshots(Integer tableId) {
        return Uni.createFrom().voidItem()
                .emitOn(QUERY_THREADS)
                .chain(() -> {
                    if (shuttingDown) return Uni.createFrom().item(List.<GameSessionSnapshot>of());
                    GameTable table = TABLES.get(Long.valueOf(tableId));
                    if (table == null) {
                        LOG.errorv("Table not found for ID: {0}", tableId);
                        return Uni.createFrom().failure(new RuntimeException("Table not found"));
                    }
                    List<GameSessionSnapshot> snapshots = new ArrayList<>();
                    context.selectFrom(POKER_GAME_SESSION)
                            .where(POKER_GAME_SESSION.TABLE_ID.eq(tableId))
                            .orderBy(POKER_GAME_SESSION.CREATE_DATE.desc())
                            .fetch()
                            .forEach(session -> {
                                LOG.infov("Added game session {0} to table {1}", session.getSessionId(), tableId);
                                GameSessionSnapshot snapshot = new GameSessionSnapshot();
                                snapshot.setSessionId(session.getSessionId());
                                snapshot.setTableId(session.getTableId());
                                snapshot.setDetails(new JsonObject(session.getDetails().data()));
                                snapshot.setCreateDate(session.getCreateDate());
                                snapshots.add(snapshot);
                            });
                    return Uni.createFrom().item(snapshots);
                });
    }

    public Uni<Void> kickPlayerFromTable(Long tableId, Integer userId) {
        return Uni.createFrom().voidItem()
                .emitOn(GAMEPLAY_THREAD)
                .call(() -> {
                    LOG.infov("Kicking player {0} from table {1}", userId, tableId);
                    GameTable table = TABLES.get(tableId);
                    if (table != null) {
                        return table.kickPlayerForce(userId);
                    }
                    return Uni.createFrom().voidItem();
                })
                .onFailure().invoke(throwable -> {
                    LOG.errorv(throwable, "Failed to kick");
                });
    }

    public Uni<Void> cancelPendingKick(Long tableId, Integer userId) {
        return Uni.createFrom().voidItem()
                .emitOn(GAMEPLAY_THREAD)
                .call(() -> {
                    LOG.infov("Cancelling pending kick for user {0} from table {1}", userId, tableId);
                    GameTable table = TABLES.get(tableId);
                    if (table != null) {
                        table.cancelPendingKick(userId);
                    }
                    return Uni.createFrom().voidItem();
                });
    }

    public Uni<GameSessionSnapshot> createGameSessionSnapshot(String sessionId, Integer tableId, JsonObject details) {
        return Uni.createFrom().voidItem()
                .emitOn(QUERY_THREADS)
                .map(unused -> {
                    if (shuttingDown) throw new IllegalStateException("Application is shutting down");
                    GameTable table = TABLES.get(Long.valueOf(tableId));
                    if (table == null) {
                        LOG.errorv("Table not found for ID: {0}", tableId);
                        throw new RuntimeException("Table not found");
                    }
                    GameSessionSnapshot gameSession = new GameSessionSnapshot();
                    gameSession.setSessionId(sessionId);
                    gameSession.setTableId(tableId);
                    gameSession.setDetails(details);
                    gameSession.setCreateDate(OffsetDateTime.now());
                    context.insertInto(POKER_GAME_SESSION)
                            .set(POKER_GAME_SESSION.SESSION_ID, sessionId)
                            .set(POKER_GAME_SESSION.TABLE_ID, tableId)
                            .set(POKER_GAME_SESSION.CREATE_DATE, OffsetDateTime.now())
                            .set(POKER_GAME_SESSION.DETAILS, JSONB.valueOf(gameSession.getDetails().encode()))
                            .returning(POKER_GAME_SESSION.SESSION_ID)
                            .fetchOne();
                    LOG.infov("Created game session for table {0}", tableId);
                    return gameSession;
                });
    }

    public Tuple2<LocalDateTime, LocalDateTime> getMaintenanceSchedule() {
        return MAINTENANCE_SCHEDULE;
    }

    public Uni<Void> scheduleMaintenanceSchedule(Integer userId, LocalDateTime start, LocalDateTime end) {
        return userService.fetchUser(userId)
                .emitOn(QUERY_THREADS)
                .map(user -> {
                    if (shuttingDown) throw new IllegalStateException("Application is shutting down");
                    MAINTENANCE_SCHEDULE = Tuple2.of(start, end);
                    LOG.infov("Maintenance schedule set from {0} to {1} by {2}", start, end, user.getUsername());
                    return null;
                });
    }

    public void notifyJackpotWin(Integer userId, BigDecimal jackpotAmount) {
        JsonObject payload = new JsonObject()
                .put("type", "JACKPOT_WIN")
                .put("userId", userId)
                .put("amount", jackpotAmount);

        GlobalSocket.sendToUser(userId, payload.encode());

        JsonObject announce = new JsonObject()
                .put("type", "JACKPOT_ANNOUNCEMENT")
                .put("winnerUserId", userId)
                .put("amount", jackpotAmount);

        GlobalSocket.broadcast(announce.encode());
    }

    public Uni<Void> saveHandHistory(GameSession gameSession) {
        return Uni.createFrom().voidItem()
                .emitOn(QUERY_THREADS)
                .map(unused -> {
                    if (shuttingDown) return null;
                    GameTable table = gameSession.getTable();

                    Long lastHand = context
                            .select(max(Tables.POKER_HAND_HISTORY.HAND_NUMBER))
                            .from(Tables.POKER_HAND_HISTORY)
                            .where(Tables.POKER_HAND_HISTORY.TABLE_ID.eq(table.getTableId().intValue()))
                            .fetchOneInto(Long.class);

                    long handNumber = (lastHand == null ? 1 : lastHand + 1);

                    List<GamePlayer> winners = gameSession.getOriginalPlayerList().stream()
                            .filter(GamePlayer::isWinner)
                            .toList();

                    if (winners.isEmpty()) {
                        LOG.infov("No winners to save for hand {0} at table {1}", handNumber, table.getTableId());
                        return null;
                    }

                    // Batch insert all winners in a single query
                    var batch = context.batch(
                        winners.stream().map(winner -> {
                            JsonArray holeCardsJson = GameCard.toJsonArray(winner.getHoleCards(), false);
                            List<GameCard> revealedCards = gameSession.getCommunityCards().stream()
                                    .filter(card -> !card.isSecret())
                                    .toList();
                            JsonArray communityCardsJson = GameCard.toJsonArray(revealedCards, false);

                            return context.insertInto(Tables.POKER_HAND_HISTORY)
                                    .set(Tables.POKER_HAND_HISTORY.TABLE_ID, table.getTableId().intValue())
                                    .set(Tables.POKER_HAND_HISTORY.HAND_NUMBER, handNumber)
                                    .set(Tables.POKER_HAND_HISTORY.WINNER_USER_ID, winner.getUser().getUserId())
                                    .set(Tables.POKER_HAND_HISTORY.WINNER_USERNAME, winner.getUser().getUsername())
                                    .set(Tables.POKER_HAND_HISTORY.WINNING_HOLE_CARDS, JSONB.valueOf(holeCardsJson.encode()))
                                    .set(Tables.POKER_HAND_HISTORY.WINNING_COMMUNITY_CARDS, JSONB.valueOf(communityCardsJson.encode()))
                                    .set(Tables.POKER_HAND_HISTORY.WINNINGS, winner.getWinnings())
                                    .set(Tables.POKER_HAND_HISTORY.CREATED_AT, LocalDateTime.now());
                        }).toList()
                    );
                    batch.execute();

                    saveHandSettlement(gameSession, handNumber, table.getTableId().intValue());

                    return null;
                });
    }

    private void saveHandSettlement(GameSession gameSession, long handNumber, int tableId) {
        OffsetDateTime now = OffsetDateTime.now();
        int rake = (int) Math.round(gameSession.getTotalRake());

        context.insertInto(Tables.POKER_HAND_SETTLEMENT)
                .set(Tables.POKER_HAND_SETTLEMENT.TABLE_ID, tableId)
                .set(Tables.POKER_HAND_SETTLEMENT.HAND_NUMBER, handNumber)
                .set(Tables.POKER_HAND_SETTLEMENT.RAKE_AMOUNT, rake)
                .set(Tables.POKER_HAND_SETTLEMENT.CREATED_AT, now)
                .execute();

        // Batch insert all player results in a single query
        var batch = context.batch(
            gameSession.getOriginalPlayerList().stream().map(player -> {
                int netResult = player.getWinnings() - player.getTotalContribution();
                return context.insertInto(Tables.POKER_HAND_PLAYER_RESULT)
                        .set(Tables.POKER_HAND_PLAYER_RESULT.TABLE_ID, tableId)
                        .set(Tables.POKER_HAND_PLAYER_RESULT.HAND_NUMBER, handNumber)
                        .set(Tables.POKER_HAND_PLAYER_RESULT.USER_ID, player.getUser().getUserId())
                        .set(Tables.POKER_HAND_PLAYER_RESULT.USERNAME, player.getUser().getUsername())
                        .set(Tables.POKER_HAND_PLAYER_RESULT.IS_BOT, player.isBot())
                        .set(Tables.POKER_HAND_PLAYER_RESULT.NET_RESULT, netResult)
                        .set(Tables.POKER_HAND_PLAYER_RESULT.CREATED_AT, now);
            }).toList()
        );
        batch.execute();
    }

    public Uni<List<HandHistoryDTO>> getHandHistory(Long tableId, int limit, int offset) {
        if (limit <= 0) limit = 10;
        if (offset < 0) offset = 0;

        return Uni.createFrom().item(
                context.selectFrom(Tables.POKER_HAND_HISTORY)
                    .where(Tables.POKER_HAND_HISTORY.TABLE_ID.eq(tableId.intValue()))
                    .orderBy(Tables.POKER_HAND_HISTORY.CREATED_AT.desc())
                    .limit(limit)
                    .offset(offset)
                    .fetch()
        ).map(records -> records.stream().map(r -> {
            List<GameCard> holeCards = GameCard.fromJsonArray(new JsonArray(r.getWinningHoleCards().data()));
            List<GameCard> communityCards = GameCard.fromJsonArray(new JsonArray(r.getWinningCommunityCards().data()));

            return new HandHistoryDTO(
                r.getHandNumber(),
                r.getWinnerUserId(),
                r.getWinnerUsername(),
                holeCards,
                communityCards,
                r.getWinnings().doubleValue()
            );
        }).toList());
    }



}
