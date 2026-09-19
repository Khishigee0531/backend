package dev.manestack.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class MetricsService {

    private final MeterRegistry registry;

    // Counters
    private final Counter gameActions;
    private final Counter depositsCreated;
    private final Counter withdrawalsCreated;
    private final Counter wsConnections;
    private final Counter wsAuthFailures;

    // Timers
    private final Timer gameActionTimer;
    private final Timer dbQueryTimer;

    @Inject
    public MetricsService(MeterRegistry registry) {
        this.registry = registry;

        // Initialize counters
        this.gameActions = Counter.builder("poker.game.actions.total")
                .description("Total game actions processed")
                .register(registry);

        this.depositsCreated = Counter.builder("poker.deposits.created.total")
                .description("Total deposits created")
                .register(registry);

        this.withdrawalsCreated = Counter.builder("poker.withdrawals.created.total")
                .description("Total withdrawals created")
                .register(registry);

        this.wsConnections = Counter.builder("poker.websocket.connections.total")
                .description("Total WebSocket connections")
                .register(registry);

        this.wsAuthFailures = Counter.builder("poker.websocket.auth.failures.total")
                .description("Total WebSocket auth failures")
                .register(registry);

        // Initialize timers
        this.gameActionTimer = Timer.builder("poker.game.action.duration")
                .description("Game action processing time")
                .register(registry);

        this.dbQueryTimer = Timer.builder("poker.db.query.duration")
                .description("Database query time")
                .register(registry);
    }

    // ── Counter methods ──────────────────────────────────────────────────────

    public void incrementGameActions() {
        gameActions.increment();
    }

    public void incrementDepositsCreated() {
        depositsCreated.increment();
    }

    public void incrementWithdrawalsCreated() {
        withdrawalsCreated.increment();
    }

    public void incrementWsConnections() {
        wsConnections.increment();
    }

    public void incrementWsAuthFailures() {
        wsAuthFailures.increment();
    }

    // ── Timer methods ────────────────────────────────────────────────────────

    public void recordGameActionDuration(long durationMs) {
        gameActionTimer.record(durationMs, TimeUnit.MILLISECONDS);
    }

    public void recordDbQueryDuration(long durationMs) {
        dbQueryTimer.record(durationMs, TimeUnit.MILLISECONDS);
    }

    // ── Gauge methods ────────────────────────────────────────────────────────

    public void registerActiveTables(java.util.concurrent.atomic.AtomicInteger activeTables) {
        registry.gauge("poker.tables.active", activeTables);
    }

    public void registerActivePlayers(java.util.concurrent.atomic.AtomicInteger activePlayers) {
        registry.gauge("poker.players.active", activePlayers);
    }
}
