package dev.manestack.service.tournament;

import dev.manestack.dto.tournament.*;
import dev.manestack.service.BalanceService;
import dev.manestack.service.DepositService;
import dev.manestack.service.poker.table.GameTable;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

import static dev.manestack.jooq.generated.Tables.*;

@ApplicationScoped
public class TournamentService {
    private static final Logger LOG = Logger.getLogger(TournamentService.class);
    private final java.util.concurrent.ExecutorService QUERY_THREADS =
            java.util.concurrent.Executors.newFixedThreadPool(4);

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_REGISTERING = "REGISTERING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_PAUSED = "PAUSED";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    public static final String ENTRY_REGISTERED = "REGISTERED";
    public static final String ENTRY_ELIMINATED = "ELIMINATED";
    public static final String ENTRY_SEATED = "SEATED";

    public static final String SETTINGS_BLOCK = "tournament_settings";

    @Inject
    DSLContext context;

    @Inject
    BalanceService balanceService;

    @Inject
    DepositService depositService;

    @Inject
    dev.manestack.service.GameService gameService;

    @ConfigProperty(name = "tournament.default-seats-per-table", defaultValue = "9")
    int defaultSeatsPerTable;

    // ── Site-wide tournament enable/disable ──────────────────────────────────

    public TournamentSettings getTournamentSettings() {
        var record = context.selectFrom(POKER_DATA_BLOCK)
                .where(POKER_DATA_BLOCK.NAME.eq(SETTINGS_BLOCK))
                .fetchOne();
        if (record == null) {
            return new TournamentSettings(true);
        }
        try {
            JsonObject json = new JsonObject(record.get(POKER_DATA_BLOCK.VALUE));
            return new TournamentSettings(json.getBoolean("enabled", true));
        } catch (Exception e) {
            return new TournamentSettings(true);
        }
    }

    public TournamentSettings saveTournamentSettings(TournamentSettings settings) {
        TournamentSettings normalized = new TournamentSettings(
                settings == null || settings.isEnabled());
        String value = new JsonObject()
                .put("enabled", normalized.isEnabled())
                .toString();
        boolean exists = context.fetchExists(
                context.selectFrom(POKER_DATA_BLOCK)
                        .where(POKER_DATA_BLOCK.NAME.eq(SETTINGS_BLOCK)));
        if (exists) {
            context.update(POKER_DATA_BLOCK)
                    .set(POKER_DATA_BLOCK.VALUE, value)
                    .where(POKER_DATA_BLOCK.NAME.eq(SETTINGS_BLOCK))
                    .execute();
        } else {
            context.insertInto(POKER_DATA_BLOCK)
                    .set(POKER_DATA_BLOCK.NAME, SETTINGS_BLOCK)
                    .set(POKER_DATA_BLOCK.VALUE, value)
                    .execute();
        }
        return normalized;
    }

    public boolean isTournamentEnabled() {
        return getTournamentSettings().isEnabled();
    }

    private void requireTournamentEnabled() {
        if (!isTournamentEnabled()) {
            throw badRequest("Tournaments are currently disabled");
        }
    }

    public boolean isTournamentTable(Long tableId) {
        if (tableId == null) return false;
        return context.fetchExists(POKER_TOURNAMENT_TABLE,
                POKER_TOURNAMENT_TABLE.TABLE_ID.eq(tableId));
    }

    public Long findTournamentIdForTable(Long tableId) {
        if (tableId == null) return null;
        return context.select(POKER_TOURNAMENT_TABLE.TOURNAMENT_ID)
                .from(POKER_TOURNAMENT_TABLE)
                .where(POKER_TOURNAMENT_TABLE.TABLE_ID.eq(tableId))
                .fetchOne(POKER_TOURNAMENT_TABLE.TOURNAMENT_ID);
    }

    // ── Admin: create / update / status ──────────────────────────────────────

    public Uni<TournamentDTO> createTournament(Integer adminId, TournamentCreateRequest req) {
        return Uni.createFrom().voidItem()
                .map(unused -> {
                    validateCreateRequest(req);

                    OffsetDateTime now = OffsetDateTime.now();
                    String status = resolveInitialStatus(req, now);
                    Integer prizePool = estimatePrizePool(req);

                    Long tournamentId = context.insertInto(POKER_TOURNAMENT)
                            .set(POKER_TOURNAMENT.TOURNAMENT_NAME, req.tournamentName())
                            .set(POKER_TOURNAMENT.DESCRIPTION, req.description())
                            .set(POKER_TOURNAMENT.TOURNAMENT_TYPE, req.tournamentType() != null ? req.tournamentType() : "FREEZEOUT")
                            .set(POKER_TOURNAMENT.GAME_VARIANT, req.gameVariant() != null ? req.gameVariant() : "TEXAS_HOLDEM")
                            .set(POKER_TOURNAMENT.REGISTRATION_START, req.registrationStart())
                            .set(POKER_TOURNAMENT.REGISTRATION_END, req.registrationEnd())
                            .set(POKER_TOURNAMENT.START_TIME, req.startTime())
                            .set(POKER_TOURNAMENT.STATUS, status)
                            .set(POKER_TOURNAMENT.PRIZE_POOL, prizePool)
                            .set(POKER_TOURNAMENT.GUARANTEED_PRIZE_POOL, req.guaranteedPrizePool())
                            .set(POKER_TOURNAMENT.PLACES_PAID, req.placesPaid() != null ? req.placesPaid() : 0)
                            .set(POKER_TOURNAMENT.PRIZE_DISTRIBUTION, jsonbOrNull(req.prizeDistributionJson()))
                            .set(POKER_TOURNAMENT.BUY_IN, req.buyIn())
                            .set(POKER_TOURNAMENT.ENTRY_FEE, req.entryFee() != null ? req.entryFee() : 0)
                            .set(POKER_TOURNAMENT.REBUY_AMOUNT, req.rebuyAmount())
                            .set(POKER_TOURNAMENT.REBUY_FEE, req.rebuyFee())
                            .set(POKER_TOURNAMENT.ADDON_AMOUNT, req.addonAmount())
                            .set(POKER_TOURNAMENT.ADDON_FEE, req.addonFee())
                            .set(POKER_TOURNAMENT.MAX_REBUYS, req.maxRebuys() != null ? req.maxRebuys() : 0)
                            .set(POKER_TOURNAMENT.ADDON_ALLOWED, Boolean.TRUE.equals(req.addonAllowed()))
                            .set(POKER_TOURNAMENT.STARTING_CHIPS, req.startingChips())
                            .set(POKER_TOURNAMENT.REBUY_CHIPS, req.rebuyChips())
                            .set(POKER_TOURNAMENT.ADDON_CHIPS, req.addonChips())
                            .set(POKER_TOURNAMENT.BLIND_STRUCTURE, jsonbOrNull(req.blindStructureJson()))
                            .set(POKER_TOURNAMENT.MAX_PLAYERS, req.maxPlayers())
                            .set(POKER_TOURNAMENT.MIN_PLAYERS, req.minPlayers() != null ? req.minPlayers() : 2)
                            .set(POKER_TOURNAMENT.SEATS_PER_TABLE, req.seatsPerTable() != null ? req.seatsPerTable() : defaultSeatsPerTable)
                            .set(POKER_TOURNAMENT.LATE_REGISTRATION_ALLOWED, Boolean.TRUE.equals(req.lateRegistrationAllowed()))
                            .set(POKER_TOURNAMENT.LATE_REGISTRATION_END_LEVEL, req.lateRegistrationEndLevel())
                            .set(POKER_TOURNAMENT.CREATED_BY, adminId)
                            .set(POKER_TOURNAMENT.CREATED_AT, now)
                            .returning(POKER_TOURNAMENT.TOURNAMENT_ID)
                            .fetchOne(POKER_TOURNAMENT.TOURNAMENT_ID);

                    insertBlindLevels(tournamentId, req.blindStructureJson());
                    insertPrizes(tournamentId, req);
                    return fetchTournamentInternal(tournamentId);
                });
    }

    public Uni<TournamentDTO> updateTournament(Long tournamentId, TournamentUpdateRequest req) {
        return Uni.createFrom().voidItem()
                .map(unused -> {
                    var tournament = context.selectFrom(POKER_TOURNAMENT)
                            .where(POKER_TOURNAMENT.TOURNAMENT_ID.eq(tournamentId))
                            .fetchOne();
                    if (tournament == null) throw notFound(tournamentId);
                    String status = tournament.getStatus();
                    if (STATUS_RUNNING.equals(status) || STATUS_COMPLETED.equals(status) || STATUS_CANCELLED.equals(status)) {
                        throw badRequest("Tournament cannot be updated in status " + status);
                    }

                    var update = context.update(POKER_TOURNAMENT)
                            .set(POKER_TOURNAMENT.UPDATED_AT, OffsetDateTime.now());
                    if (req.tournamentName() != null && !req.tournamentName().isBlank()) {
                        update = update.set(POKER_TOURNAMENT.TOURNAMENT_NAME, req.tournamentName());
                    }
                    if (req.description() != null) {
                        update = update.set(POKER_TOURNAMENT.DESCRIPTION, req.description());
                    }
                    if (req.registrationStart() != null) {
                        update = update.set(POKER_TOURNAMENT.REGISTRATION_START, req.registrationStart());
                    }
                    if (req.registrationEnd() != null) {
                        update = update.set(POKER_TOURNAMENT.REGISTRATION_END, req.registrationEnd());
                    }
                    if (req.startTime() != null) {
                        update = update.set(POKER_TOURNAMENT.START_TIME, req.startTime());
                    }
                    if (req.buyIn() != null) {
                        update = update.set(POKER_TOURNAMENT.BUY_IN, req.buyIn());
                    }
                    if (req.entryFee() != null) {
                        update = update.set(POKER_TOURNAMENT.ENTRY_FEE, req.entryFee());
                    }
                    if (req.maxPlayers() != null) {
                        update = update.set(POKER_TOURNAMENT.MAX_PLAYERS, req.maxPlayers());
                    }
                    if (req.guaranteedPrizePool() != null) {
                        update = update.set(POKER_TOURNAMENT.GUARANTEED_PRIZE_POOL, req.guaranteedPrizePool());
                    }
                    if (req.blindStructureJson() != null) {
                        update = update.set(POKER_TOURNAMENT.BLIND_STRUCTURE, jsonbOrNull(req.blindStructureJson()));
                    }
                    if (req.prizeDistributionJson() != null) {
                        update = update.set(POKER_TOURNAMENT.PRIZE_DISTRIBUTION, jsonbOrNull(req.prizeDistributionJson()));
                    }
                    update.where(POKER_TOURNAMENT.TOURNAMENT_ID.eq(tournamentId)).execute();
                    if (req.blindStructureJson() != null) {
                        context.delete(POKER_TOURNAMENT_BLIND_LEVEL)
                                .where(POKER_TOURNAMENT_BLIND_LEVEL.TOURNAMENT_ID.eq(tournamentId))
                                .execute();
                        insertBlindLevels(tournamentId, req.blindStructureJson());
                    }
                    if (req.prizeDistributionJson() != null) {
                        context.delete(POKER_TOURNAMENT_PRIZE)
                                .where(POKER_TOURNAMENT_PRIZE.TOURNAMENT_ID.eq(tournamentId))
                                .execute();
                        insertPrizeDistribution(tournamentId, req.prizeDistributionJson());
                    }
                    recalculatePrizePool(tournamentId);
                    return fetchTournamentInternal(tournamentId);
                });
    }

    public Uni<TournamentDTO> updateStatus(Long tournamentId, TournamentStatusUpdateRequest req) {
        return Uni.createFrom().voidItem()
                .emitOn(QUERY_THREADS)
                .map(unused -> {
                    if (req == null || req.status() == null) throw badRequest("status is required");
                    String target = req.status().trim().toUpperCase(Locale.ROOT);
                    var tournament = context.selectFrom(POKER_TOURNAMENT)
                            .where(POKER_TOURNAMENT.TOURNAMENT_ID.eq(tournamentId))
                            .fetchOne();
                    if (tournament == null) throw notFound(tournamentId);
                    String current = tournament.getStatus();

                    switch (target) {
                        case STATUS_REGISTERING -> {
                            if (!STATUS_PENDING.equals(current) && !STATUS_REGISTERING.equals(current)) {
                                throw badRequest("Cannot open registration from status " + current);
                            }
                        }
                        case STATUS_RUNNING -> {
                            if (!STATUS_PENDING.equals(current) && !STATUS_REGISTERING.equals(current) && !STATUS_PAUSED.equals(current)) {
                                throw badRequest("Cannot start tournament from status " + current);
                            }
                            long entries = context.fetchCount(POKER_TOURNAMENT_ENTRY,
                                    POKER_TOURNAMENT_ENTRY.TOURNAMENT_ID.eq(tournamentId)
                                            .and(POKER_TOURNAMENT_ENTRY.STATUS.ne(ENTRY_ELIMINATED)));
                            int minPlayers = tournament.getMinPlayers() != null ? tournament.getMinPlayers() : 2;
                            if (entries < minPlayers) {
                                throw badRequest("Need at least " + minPlayers + " registered players to start (have " + entries + ")");
                            }
                        }
                        case STATUS_PAUSED -> {
                            if (!STATUS_RUNNING.equals(current)) throw badRequest("Only a running tournament can be paused");
                        }
                        case STATUS_COMPLETED -> {
                            if (!STATUS_RUNNING.equals(current) && !STATUS_PAUSED.equals(current)) {
                                throw badRequest("Only a running/paused tournament can be completed");
                            }
                        }
                        case STATUS_CANCELLED -> {
                            if (STATUS_COMPLETED.equals(current) || STATUS_CANCELLED.equals(current)) {
                                throw badRequest("Tournament already " + current);
                            }
                        }
                        case STATUS_PENDING -> {
                            if (!STATUS_PENDING.equals(current)) throw badRequest("Cannot reset to PENDING from " + current);
                        }
                        default -> throw badRequest("Unknown status: " + target);
                    }

                    OffsetDateTime now = OffsetDateTime.now();
                    var update = context.update(POKER_TOURNAMENT)
                            .set(POKER_TOURNAMENT.STATUS, target)
                            .set(POKER_TOURNAMENT.UPDATED_AT, now);

                    if (STATUS_RUNNING.equals(target) && (STATUS_PENDING.equals(current) || STATUS_REGISTERING.equals(current))) {
                        update = update.set(POKER_TOURNAMENT.END_TIME, (OffsetDateTime) null);
                        if (STATUS_PENDING.equals(current)) {
                            update = update.set(POKER_TOURNAMENT.REGISTRATION_END, now);
                        }
                    }
                    if (STATUS_COMPLETED.equals(target)) {
                        update = update.set(POKER_TOURNAMENT.END_TIME, now)
                                .set(POKER_TOURNAMENT.COMPLETED_AT, now);
                    }
                    if (STATUS_CANCELLED.equals(target)) {
                        update = update.set(POKER_TOURNAMENT.END_TIME, now)
                                .set(POKER_TOURNAMENT.CANCELLED_REASON, req.cancelledReason());
                    }
                    update.where(POKER_TOURNAMENT.TOURNAMENT_ID.eq(tournamentId)).execute();
                    return new Object[]{target, current};
                })
                .onItem().transformToUni(pair -> {
                    String target = (String) pair[0];
                    String previous = (String) pair[1];
                    if (STATUS_CANCELLED.equals(target)) {
                        return refundAllEntries(tournamentId)
                                .chain(() -> destroyTournamentTables(tournamentId));
                    }
                    if (STATUS_COMPLETED.equals(target)) {
                        return distributePrizes(tournamentId)
                                .chain(() -> destroyTournamentTables(tournamentId));
                    }
                    if (STATUS_RUNNING.equals(target)) {
                        return ensureTablesAndSeat(tournamentId);
                    }
                    return Uni.createFrom().voidItem();
                })
                .map(unused -> fetchTournamentInternal(tournamentId));
    }

    // ── Queries ──────────────────────────────────────────────────────────────

    public Uni<List<TournamentDTO>> listTournaments(String status, boolean includeFinished) {
        return Uni.createFrom().voidItem()
                .map(unused -> {
                    List<Long> ids;
                    if (status != null && !status.isBlank()) {
                        ids = context.select(POKER_TOURNAMENT.TOURNAMENT_ID)
                                .from(POKER_TOURNAMENT)
                                .where(POKER_TOURNAMENT.STATUS.eq(status.trim().toUpperCase(Locale.ROOT)))
                                .orderBy(POKER_TOURNAMENT.START_TIME.asc())
                                .fetch(POKER_TOURNAMENT.TOURNAMENT_ID);
                    } else if (!includeFinished) {
                        // Public/homepage list: hide finished + cancelled + paused/stopped.
                        ids = context.select(POKER_TOURNAMENT.TOURNAMENT_ID)
                                .from(POKER_TOURNAMENT)
                                .where(POKER_TOURNAMENT.STATUS.notIn(
                                        STATUS_COMPLETED, STATUS_CANCELLED, STATUS_PAUSED))
                                .orderBy(POKER_TOURNAMENT.START_TIME.asc())
                                .fetch(POKER_TOURNAMENT.TOURNAMENT_ID);
                    } else {
                        ids = context.select(POKER_TOURNAMENT.TOURNAMENT_ID)
                                .from(POKER_TOURNAMENT)
                                .orderBy(POKER_TOURNAMENT.START_TIME.asc())
                                .fetch(POKER_TOURNAMENT.TOURNAMENT_ID);
                    }
                    return ids.stream().map(this::fetchTournamentInternal).toList();
                });
    }

    public Uni<TournamentDTO> getTournament(Long tournamentId) {
        return Uni.createFrom().item(fetchTournamentInternal(tournamentId));
    }

    public Uni<List<TournamentEntryDTO>> listEntries(Long tournamentId) {
        return Uni.createFrom().voidItem()
                .map(unused -> {
                    var full = context.selectFrom(POKER_TOURNAMENT_ENTRY)
                            .where(POKER_TOURNAMENT_ENTRY.TOURNAMENT_ID.eq(tournamentId))
                            .fetch();
                    Map<Integer, String> usernames = context.select(POKER_USER.USER_ID, POKER_USER.USERNAME)
                            .from(POKER_USER)
                            .where(POKER_USER.USER_ID.in(
                                    context.select(POKER_TOURNAMENT_ENTRY.USER_ID).from(POKER_TOURNAMENT_ENTRY)
                                            .where(POKER_TOURNAMENT_ENTRY.TOURNAMENT_ID.eq(tournamentId))))
                            .fetchMap(POKER_USER.USER_ID, POKER_USER.USERNAME);

                    return full.stream()
                            .map(r -> new TournamentEntryDTO(
                                    r.getEntryId(),
                                    r.getTournamentId(),
                                    r.getUserId(),
                                    usernames.get(r.getUserId()),
                                    r.getEntryType(),
                                    r.getAmountPaid(),
                                    r.getChipsReceived(),
                                    r.getSeatNumber(),
                                    r.getTableId(),
                                    r.getStatus(),
                                    r.getRegisteredAt(),
                                    r.getSeatedAt(),
                                    r.getEliminatedAt(),
                                    r.getEliminatedLevel(),
                                    r.getFinalPosition(),
                                    r.getPrizeWon(),
                                    r.getRebuyCount(),
                                    r.getAddonTaken()))
                            .toList();
                });
    }

    public Uni<List<TournamentTableDTO>> listTournamentTables(Long tournamentId) {
        return Uni.createFrom().voidItem()
                .map(unused -> {
                    var records = context.select(
                                    POKER_TOURNAMENT_TABLE.TOURNAMENT_TABLE_ID,
                                    POKER_TOURNAMENT_TABLE.TOURNAMENT_ID,
                                    POKER_TOURNAMENT_TABLE.TABLE_ID,
                                    POKER_TABLE.TABLE_NAME,
                                    POKER_TABLE.SECURE_ID,
                                    POKER_TOURNAMENT_TABLE.TABLE_NUMBER,
                                    POKER_TOURNAMENT_TABLE.STATUS,
                                    POKER_TOURNAMENT_TABLE.ASSIGNED_AT,
                                    POKER_TOURNAMENT_TABLE.COMPLETED_AT)
                                    .from(POKER_TOURNAMENT_TABLE)
                                    .leftJoin(POKER_TABLE).on(POKER_TABLE.TABLE_ID.eq(POKER_TOURNAMENT_TABLE.TABLE_ID))
                                    .where(POKER_TOURNAMENT_TABLE.TOURNAMENT_ID.eq(tournamentId))
                                    .fetch();

                        return records.stream().map(r -> {
                        Integer players = context.fetchCount(POKER_TOURNAMENT_ENTRY,
                                POKER_TOURNAMENT_ENTRY.TOURNAMENT_ID.eq(tournamentId)
                                        .and(POKER_TOURNAMENT_ENTRY.TABLE_ID.eq(r.get(POKER_TOURNAMENT_TABLE.TABLE_ID)))
                                        .and(POKER_TOURNAMENT_ENTRY.STATUS.in(ENTRY_REGISTERED, ENTRY_SEATED)));
                        return new TournamentTableDTO(
                                r.get(POKER_TOURNAMENT_TABLE.TOURNAMENT_TABLE_ID),
                                r.get(POKER_TOURNAMENT_TABLE.TOURNAMENT_ID),
                                r.get(POKER_TOURNAMENT_TABLE.TABLE_ID),
                                r.get(POKER_TABLE.TABLE_NAME),
                                r.get(POKER_TOURNAMENT_TABLE.TABLE_NUMBER),
                                r.get(POKER_TOURNAMENT_TABLE.STATUS),
                                r.get(POKER_TOURNAMENT_TABLE.ASSIGNED_AT),
                                r.get(POKER_TOURNAMENT_TABLE.COMPLETED_AT),
                                players,
                                tableSecureId(r.get(POKER_TABLE.SECURE_ID), r.get(POKER_TOURNAMENT_TABLE.TABLE_ID)));
                    }).toList();
                });
    }

    private static String tableSecureId(String secureId, Long tableId) {
        if (secureId != null && !secureId.isBlank()) return secureId;
        if (tableId == null) return null;
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(String.valueOf(tableId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    // ── Registration lifecycle ───────────────────────────────────────────────

    public Uni<TournamentEntryDTO> register(Integer userId, Long tournamentId) {
        return Uni.createFrom().voidItem()
                .emitOn(QUERY_THREADS)
                .map(unused -> {
                    requireTournamentEnabled();
                    var tournament = requireTournament(tournamentId);
                    OffsetDateTime now = OffsetDateTime.now();
                    String status = effectiveStatus(tournament, now);
                    if (!STATUS_REGISTERING.equals(status)) {
                        throw badRequest("Registration is not open (status: " + status + ")");
                    }
                    if (now.isBefore(tournament.getRegistrationStart())) {
                        throw badRequest("Registration has not started yet");
                    }
                    if (now.isAfter(tournament.getRegistrationEnd())) {
                        throw badRequest("Registration has closed");
                    }

                    long entryCount = context.fetchCount(POKER_TOURNAMENT_ENTRY,
                            POKER_TOURNAMENT_ENTRY.TOURNAMENT_ID.eq(tournamentId)
                                    .and(POKER_TOURNAMENT_ENTRY.STATUS.ne("CANCELLED")));
                    if (tournament.getMaxPlayers() != null && entryCount >= tournament.getMaxPlayers()) {
                        throw badRequest("Tournament is full");
                    }

                    boolean already = context.fetchExists(POKER_TOURNAMENT_ENTRY,
                            POKER_TOURNAMENT_ENTRY.TOURNAMENT_ID.eq(tournamentId)
                                    .and(POKER_TOURNAMENT_ENTRY.USER_ID.eq(userId)));
                    if (already) throw badRequest("Already registered for this tournament");

                    int buyIn = tournament.getBuyIn() != null ? tournament.getBuyIn() : 0;
                    int fee = tournament.getEntryFee() != null ? tournament.getEntryFee() : 0;
                    int total = buyIn + fee;
                    long chips = tournament.getStartingChips() != null ? tournament.getStartingChips() : 0L;
                    return new Object[]{total, chips};
                })
                .onItem().transformToUni(pending -> {
                    int total = (int) pending[0];
                    return chargeUser(userId, total, "TOURNAMENT_BUYIN").replaceWith(pending);
                })
                .map(pending -> {
                    long chips = (Long) pending[1];
                    int total = (Integer) pending[0];
                    Integer seat = nextSeatNumber(tournamentId);
                    Long entryId = context.insertInto(POKER_TOURNAMENT_ENTRY)
                            .set(POKER_TOURNAMENT_ENTRY.TOURNAMENT_ID, tournamentId)
                            .set(POKER_TOURNAMENT_ENTRY.USER_ID, userId)
                            .set(POKER_TOURNAMENT_ENTRY.ENTRY_TYPE, "BUYIN")
                            .set(POKER_TOURNAMENT_ENTRY.AMOUNT_PAID, total)
                            .set(POKER_TOURNAMENT_ENTRY.CHIPS_RECEIVED, chips)
                            .set(POKER_TOURNAMENT_ENTRY.SEAT_NUMBER, seat)
                            .set(POKER_TOURNAMENT_ENTRY.STATUS, ENTRY_REGISTERED)
                            .set(POKER_TOURNAMENT_ENTRY.REGISTERED_AT, OffsetDateTime.now())
                            .returning(POKER_TOURNAMENT_ENTRY.ENTRY_ID)
                            .fetchOne(POKER_TOURNAMENT_ENTRY.ENTRY_ID);

                    recalculatePrizePool(tournamentId);
                    return toEntryDto(entryId);
                });
    }

    public Uni<Void> unregister(Integer userId, Long tournamentId) {
        return Uni.createFrom().voidItem()
                .emitOn(QUERY_THREADS)
                .map(unused -> {
                    var tournament = requireTournament(tournamentId);
                    OffsetDateTime now = OffsetDateTime.now();
                    if (STATUS_RUNNING.equals(tournament.getStatus()) || STATUS_PAUSED.equals(tournament.getStatus())) {
                        throw badRequest("Cannot unregister after tournament has started");
                    }
                    if (STATUS_COMPLETED.equals(tournament.getStatus()) || STATUS_CANCELLED.equals(tournament.getStatus())) {
                        throw badRequest("Tournament is already " + tournament.getStatus());
                    }

                    var entry = context.selectFrom(POKER_TOURNAMENT_ENTRY)
                            .where(POKER_TOURNAMENT_ENTRY.TOURNAMENT_ID.eq(tournamentId)
                                    .and(POKER_TOURNAMENT_ENTRY.USER_ID.eq(userId)))
                            .fetchOne();
                    if (entry == null) throw badRequest("Not registered for this tournament");
                    if (!ENTRY_REGISTERED.equals(entry.getStatus())) {
                        throw badRequest("Entry status does not allow unregister");
                    }

                    context.delete(POKER_TOURNAMENT_ENTRY)
                            .where(POKER_TOURNAMENT_ENTRY.ENTRY_ID.eq(entry.getEntryId()))
                            .execute();

                    int refund = entry.getAmountPaid() != null ? entry.getAmountPaid() : 0;
                    recalculatePrizePool(tournamentId);
                    return refund;
                })
                .onItem().transformToUni(refund -> creditUser(userId, refund, "TOURNAMENT_REFUND"));
    }

    public Uni<TournamentEntryDTO> rebuy(Integer userId, Long tournamentId) {
        return Uni.createFrom().voidItem()
                .emitOn(QUERY_THREADS)
                .map(unused -> {
                    requireTournamentEnabled();
                    var tournament = requireTournament(tournamentId);
                    if (!STATUS_RUNNING.equals(tournament.getStatus())) {
                        throw badRequest("Rebuys are only allowed while the tournament is running");
                    }
                    if (tournament.getRebuyAmount() == null || tournament.getRebuyAmount() <= 0) {
                        throw badRequest("Rebuys are not enabled for this tournament");
                    }
                    int maxRebuys = tournament.getMaxRebuys() != null ? tournament.getMaxRebuys() : 0;
                    if (maxRebuys <= 0) throw badRequest("Rebuys are not enabled for this tournament");

                    var entry = context.selectFrom(POKER_TOURNAMENT_ENTRY)
                            .where(POKER_TOURNAMENT_ENTRY.TOURNAMENT_ID.eq(tournamentId)
                                    .and(POKER_TOURNAMENT_ENTRY.USER_ID.eq(userId)))
                            .fetchOne();
                    if (entry == null) throw badRequest("Not in this tournament");
                    int rebuyCount = entry.getRebuyCount() != null ? entry.getRebuyCount() : 0;
                    if (rebuyCount >= maxRebuys) throw badRequest("Maximum rebuys reached");

                    int amount = tournament.getRebuyAmount();
                    int fee = tournament.getRebuyFee() != null ? tournament.getRebuyFee() : 0;
                    long chips = tournament.getRebuyChips() != null ? tournament.getRebuyChips() : 0L;
                    return new Object[]{entry.getEntryId(), amount + fee, rebuyCount, entry.getAmountPaid(), chips};
                })
                .onItem().transformToUni(pending -> {
                    int charge = (Integer) pending[1];
                    return chargeUser(userId, charge, "TOURNAMENT_REBUY").replaceWith(pending);
                })
                .map(pending -> {
                    Long entryId = (Long) pending[0];
                    int rebuyCount = (Integer) pending[2];
                    int amountPaid = (Integer) pending[3];
                    long chips = (Long) pending[4];
                    int charge = (Integer) pending[1];

                    context.update(POKER_TOURNAMENT_ENTRY)
                            .set(POKER_TOURNAMENT_ENTRY.REBUY_COUNT, rebuyCount + 1)
                            .set(POKER_TOURNAMENT_ENTRY.AMOUNT_PAID, amountPaid + charge)
                            .set(POKER_TOURNAMENT_ENTRY.CHIPS_RECEIVED, chips)
                            .set(POKER_TOURNAMENT_ENTRY.STATUS, ENTRY_REGISTERED)
                            .set(POKER_TOURNAMENT_ENTRY.ELIMINATED_AT, (OffsetDateTime) null)
                            .set(POKER_TOURNAMENT_ENTRY.ELIMINATED_LEVEL, (Integer) null)
                            .set(POKER_TOURNAMENT_ENTRY.FINAL_POSITION, (Integer) null)
                            .where(POKER_TOURNAMENT_ENTRY.ENTRY_ID.eq(entryId))
                            .execute();

                    recalculatePrizePool(tournamentId);
                    return toEntryDto(entryId);
                });
    }

    public Uni<TournamentEntryDTO> addon(Integer userId, Long tournamentId) {
        return Uni.createFrom().voidItem()
                .emitOn(QUERY_THREADS)
                .map(unused -> {
                    requireTournamentEnabled();
                    var tournament = requireTournament(tournamentId);
                    if (!STATUS_RUNNING.equals(tournament.getStatus())) {
                        throw badRequest("Addons are only allowed while the tournament is running");
                    }
                    if (!Boolean.TRUE.equals(tournament.getAddonAllowed()) || tournament.getAddonAmount() == null) {
                        throw badRequest("Addons are not enabled for this tournament");
                    }
                    var entry = context.selectFrom(POKER_TOURNAMENT_ENTRY)
                            .where(POKER_TOURNAMENT_ENTRY.TOURNAMENT_ID.eq(tournamentId)
                                    .and(POKER_TOURNAMENT_ENTRY.USER_ID.eq(userId)))
                            .fetchOne();
                    if (entry == null) throw badRequest("Not in this tournament");
                    if (Boolean.TRUE.equals(entry.getAddonTaken())) throw badRequest("Addon already taken");

                    int amount = tournament.getAddonAmount();
                    int fee = tournament.getAddonFee() != null ? tournament.getAddonFee() : 0;
                    long chips = tournament.getAddonChips() != null ? tournament.getAddonChips() : 0L;
                    return new Object[]{entry.getEntryId(), amount + fee, entry.getAmountPaid(), chips};
                })
                .onItem().transformToUni(pending -> {
                    int charge = (Integer) pending[1];
                    return chargeUser(userId, charge, "TOURNAMENT_ADDON").replaceWith(pending);
                })
                .map(pending -> {
                    Long entryId = (Long) pending[0];
                    int charge = (Integer) pending[1];
                    int amountPaid = (Integer) pending[2];
                    long chips = (Long) pending[3];

                    context.update(POKER_TOURNAMENT_ENTRY)
                            .set(POKER_TOURNAMENT_ENTRY.ADDON_TAKEN, true)
                            .set(POKER_TOURNAMENT_ENTRY.AMOUNT_PAID, amountPaid + charge)
                            .set(POKER_TOURNAMENT_ENTRY.CHIPS_RECEIVED, chips)
                            .where(POKER_TOURNAMENT_ENTRY.ENTRY_ID.eq(entryId))
                            .execute();

                    recalculatePrizePool(tournamentId);
                    return toEntryDto(entryId);
                });
    }

    public Uni<TournamentEntryDTO> eliminate(Long tournamentId, Integer userId, Integer finalPosition) {
        return Uni.createFrom().voidItem()
                .emitOn(QUERY_THREADS)
                .chain(unused -> {
                    var entry = context.selectFrom(POKER_TOURNAMENT_ENTRY)
                            .where(POKER_TOURNAMENT_ENTRY.TOURNAMENT_ID.eq(tournamentId)
                                    .and(POKER_TOURNAMENT_ENTRY.USER_ID.eq(userId)))
                            .fetchOne();
                    if (entry == null) throw badRequest("Not in this tournament");
                    if (ENTRY_ELIMINATED.equals(entry.getStatus())) return Uni.createFrom().item(toEntryDto(entry.getEntryId()));

                    Long tableId = entry.getTableId();
                    context.update(POKER_TOURNAMENT_ENTRY)
                            .set(POKER_TOURNAMENT_ENTRY.STATUS, ENTRY_ELIMINATED)
                            .set(POKER_TOURNAMENT_ENTRY.ELIMINATED_AT, OffsetDateTime.now())
                            .set(POKER_TOURNAMENT_ENTRY.FINAL_POSITION, finalPosition)
                            .where(POKER_TOURNAMENT_ENTRY.ENTRY_ID.eq(entry.getEntryId()))
                            .execute();

                    // Bust → remove from physical virtual-table seat (no cash unlock).
                    Uni<Void> seat = tableId != null
                            ? gameService.removeTournamentPlayer(tableId, userId)
                            : Uni.createFrom().voidItem();
                    return seat.replaceWith(toEntryDto(entry.getEntryId()));
                });
    }

    /**
     * MTT rebalance after eliminations:
     * 1) Break empty tables.
     * 2) If more active tables than needed for remaining players, move players off the
     *    emptiest surplus tables and break those tables.
     */
    public Uni<Void> rebalanceTables(Long tournamentId) {
        return Uni.createFrom().voidItem()
                .emitOn(QUERY_THREADS)
                .chain(unused -> {
                    var tournament = requireTournament(tournamentId);
                    if (!STATUS_RUNNING.equals(tournament.getStatus()) && !STATUS_PAUSED.equals(tournament.getStatus())) {
                        return Uni.createFrom().voidItem();
                    }
                    int seatsPerTable = tournament.getSeatsPerTable() != null
                            ? tournament.getSeatsPerTable()
                            : defaultSeatsPerTable;

                    var activeTables = context.selectFrom(POKER_TOURNAMENT_TABLE)
                            .where(POKER_TOURNAMENT_TABLE.TOURNAMENT_ID.eq(tournamentId)
                                    .and(POKER_TOURNAMENT_TABLE.STATUS.ne("BROKEN"))
                                    .and(POKER_TOURNAMENT_TABLE.STATUS.ne("COMPLETED")))
                            .orderBy(POKER_TOURNAMENT_TABLE.TABLE_NUMBER.asc())
                            .fetch();
                    if (activeTables.isEmpty()) return Uni.createFrom().voidItem();

                    var liveEntries = context.selectFrom(POKER_TOURNAMENT_ENTRY)
                            .where(POKER_TOURNAMENT_ENTRY.TOURNAMENT_ID.eq(tournamentId)
                                    .and(POKER_TOURNAMENT_ENTRY.STATUS.ne(ENTRY_ELIMINATED))
                                    .and(POKER_TOURNAMENT_ENTRY.STATUS.ne("CANCELLED"))
                                    .and(POKER_TOURNAMENT_ENTRY.TABLE_ID.isNotNull()))
                            .fetch();

                    Map<Long, List<dev.manestack.jooq.generated.tables.records.PokerTournamentEntryRecord>> byTable =
                            new HashMap<>();
                    for (var e : liveEntries) {
                        byTable.computeIfAbsent(e.getTableId(), k -> new ArrayList<>()).add(e);
                    }

                    long totalPlayers = liveEntries.size();
                    if (totalPlayers == 0) {
                        // Everyone gone — break all tables.
                        Uni<Void> chain = Uni.createFrom().voidItem();
                        for (var t : activeTables) {
                            chain = chain.chain(() -> breakTournamentTable(tournamentId, t.getTournamentTableId(), t.getTableId()));
                        }
                        return chain;
                    }

                    int tablesNeeded = (int) Math.max(1, Math.ceil(totalPlayers / (double) seatsPerTable));

                    // Break empty active tables first.
                    Uni<Void> chain = Uni.createFrom().voidItem();
                    for (var t : activeTables) {
                        List<?> players = byTable.get(t.getTableId());
                        if (players == null || players.isEmpty()) {
                            chain = chain.chain(() -> breakTournamentTable(tournamentId, t.getTournamentTableId(), t.getTableId()));
                        }
                    }

                    return chain.chain(() -> {
                        // Re-read after empty breaks.
                        var stillActive = context.selectFrom(POKER_TOURNAMENT_TABLE)
                                .where(POKER_TOURNAMENT_TABLE.TOURNAMENT_ID.eq(tournamentId)
                                        .and(POKER_TOURNAMENT_TABLE.STATUS.ne("BROKEN"))
                                        .and(POKER_TOURNAMENT_TABLE.STATUS.ne("COMPLETED")))
                                .orderBy(POKER_TOURNAMENT_TABLE.TABLE_NUMBER.asc())
                                .fetch();
                        if (stillActive.size() <= tablesNeeded) {
                            return Uni.createFrom().voidItem();
                        }

                        // Count live per table again.
                        var live2 = context.selectFrom(POKER_TOURNAMENT_ENTRY)
                                .where(POKER_TOURNAMENT_ENTRY.TOURNAMENT_ID.eq(tournamentId)
                                        .and(POKER_TOURNAMENT_ENTRY.STATUS.ne(ENTRY_ELIMINATED))
                                        .and(POKER_TOURNAMENT_ENTRY.STATUS.ne("CANCELLED"))
                                        .and(POKER_TOURNAMENT_ENTRY.TABLE_ID.isNotNull()))
                                .fetch();
                        Map<Long, List<dev.manestack.jooq.generated.tables.records.PokerTournamentEntryRecord>> byTable2 =
                                new HashMap<>();
                        for (var e : live2) {
                            byTable2.computeIfAbsent(e.getTableId(), k -> new ArrayList<>()).add(e);
                        }

                        // Sort tables by player count ascending (emptiest to break first).
                        var sorted = new ArrayList<>(stillActive);
                        sorted.sort((a, b) -> {
                            int ca = byTable2.getOrDefault(a.getTableId(), List.of()).size();
                            int cb = byTable2.getOrDefault(b.getTableId(), List.of()).size();
                            return Integer.compare(ca, cb);
                        });

                        Uni<Void> rebalance = Uni.createFrom().voidItem();
                        int surplus = stillActive.size() - tablesNeeded;
                        int remainingTables = stillActive.size();
                        for (int i = 0; i < surplus && i < sorted.size(); i++) {
                            var breakMe = sorted.get(i);
                            // Don't break if it's the only table left.
                            if (remainingTables <= 1) break;
                            List<dev.manestack.jooq.generated.tables.records.PokerTournamentEntryRecord> movers =
                                    byTable2.getOrDefault(breakMe.getTableId(), List.of());
                            // Destination: table with most free seats among remaining.
                            Long destTableId = pickDestination(stillActive, breakMe.getTableId(), byTable2, seatsPerTable);
                            if (destTableId == null) break;
                            final Long dest = destTableId;
                            final var breakRecord = breakMe;
                            rebalance = rebalance.chain(() -> {
                                Uni<Void> moveChain = Uni.createFrom().voidItem();
                                int used = byTable2.getOrDefault(dest, List.of()).size();
                                for (var mover : movers) {
                                    int seat = nextFreeSeat(used, seatsPerTable, byTable2, dest);
                                    used++;
                                    final int seatNum = seat;
                                    final Integer uid = mover.getUserId();
                                    final Integer chips = mover.getChipsReceived() != null
                                            ? mover.getChipsReceived().intValue()
                                            : (int) (tournament.getStartingChips() != null
                                                    ? tournament.getStartingChips() : 10_000L);
                                    context.update(POKER_TOURNAMENT_ENTRY)
                                            .set(POKER_TOURNAMENT_ENTRY.TABLE_ID, dest)
                                            .set(POKER_TOURNAMENT_ENTRY.SEAT_NUMBER, seatNum)
                                            .where(POKER_TOURNAMENT_ENTRY.ENTRY_ID.eq(mover.getEntryId()))
                                            .execute();
                                    moveChain = moveChain.chain(() ->
                                            gameService.moveTournamentPlayer(
                                                    breakRecord.getTableId(), dest, uid, seatNum, chips)
                                                    .onFailure().recoverWithUni(u -> Uni.createFrom().voidItem()));
                                    // Track assignment for subsequent seat picks on same dest.
                                    byTable2.computeIfAbsent(dest, k -> new ArrayList<>()).add(mover);
                                }
                                return moveChain.chain(() ->
                                        breakTournamentTable(tournamentId, breakRecord.getTournamentTableId(), breakRecord.getTableId()));
                            });
                            remainingTables--;
                        }
                        return rebalance;
                    });
                });
    }

    private Long pickDestination(
            List<dev.manestack.jooq.generated.tables.records.PokerTournamentTableRecord> tables,
            Long excludeTableId,
            Map<Long, List<dev.manestack.jooq.generated.tables.records.PokerTournamentEntryRecord>> byTable,
            int seatsPerTable) {
        Long best = null;
        int bestFree = -1;
        for (var t : tables) {
            if (t.getTableId().equals(excludeTableId)) continue;
            int used = byTable.getOrDefault(t.getTableId(), List.of()).size();
            int free = seatsPerTable - used;
            if (free > bestFree) {
                bestFree = free;
                best = t.getTableId();
            }
        }
        return best;
    }

    private int nextFreeSeat(int hint, int seatsPerTable,
                              Map<Long, List<dev.manestack.jooq.generated.tables.records.PokerTournamentEntryRecord>> byTable,
                              Long tableId) {
        java.util.Set<Integer> used = new java.util.HashSet<>();
        for (var e : byTable.getOrDefault(tableId, List.of())) {
            if (e.getSeatNumber() != null) used.add(e.getSeatNumber());
        }
        for (int i = 0; i < seatsPerTable; i++) {
            if (!used.contains(i)) return i;
        }
        return Math.min(hint, seatsPerTable - 1);
    }

    /** Mark tournament table BROKEN and remove the virtual cash-table row from memory/DB. */
    public Uni<Void> breakTournamentTable(Long tournamentId, Long tournamentTableId, Long tableId) {
        return Uni.createFrom().voidItem()
                .emitOn(QUERY_THREADS)
                .invoke(unused -> {
                    context.update(POKER_TOURNAMENT_TABLE)
                            .set(POKER_TOURNAMENT_TABLE.STATUS, "BROKEN")
                            .set(POKER_TOURNAMENT_TABLE.COMPLETED_AT, OffsetDateTime.now())
                            .where(POKER_TOURNAMENT_TABLE.TOURNAMENT_TABLE_ID.eq(tournamentTableId))
                            .execute();
                    Integer tablesCount = context.select(POKER_TOURNAMENT.TABLES_COUNT)
                            .from(POKER_TOURNAMENT)
                            .where(POKER_TOURNAMENT.TOURNAMENT_ID.eq(tournamentId))
                            .fetchOne(POKER_TOURNAMENT.TABLES_COUNT);
                    int nextCount = Math.max((tablesCount == null ? 0 : tablesCount) - 1, 0);
                    context.update(POKER_TOURNAMENT)
                            .set(POKER_TOURNAMENT.TABLES_COUNT, nextCount)
                            .where(POKER_TOURNAMENT.TOURNAMENT_ID.eq(tournamentId))
                            .execute();
                    LOG.infov("Broke tournament table T{0} table#{1} (tableId={2})", tournamentId, tournamentTableId, tableId);
                })
                .chain(() -> gameService.destroyTournamentTable(tableId, true));
    }

    /** Destroy all remaining virtual tables for a finished/cancelled tournament. */
    public Uni<Void> destroyTournamentTables(Long tournamentId) {
        return Uni.createFrom().voidItem()
                .emitOn(QUERY_THREADS)
                .chain(unused -> {
                    var rows = context.selectFrom(POKER_TOURNAMENT_TABLE)
                            .where(POKER_TOURNAMENT_TABLE.TOURNAMENT_ID.eq(tournamentId)
                                    .and(POKER_TOURNAMENT_TABLE.STATUS.ne("BROKEN")))
                            .fetch();
                    Uni<Void> chain = Uni.createFrom().voidItem();
                    for (var row : rows) {
                        chain = chain.chain(() -> breakTournamentTable(
                                tournamentId, row.getTournamentTableId(), row.getTableId()));
                    }
                    return chain;
                });
    }

    // ── Tables ───────────────────────────────────────────────────────────────

    public Uni<TournamentTableDTO> assignTable(Long tournamentId, Long tableId, Integer tableNumber) {
        return Uni.createFrom().voidItem()
                .map(unused -> {
                    requireTournament(tournamentId);
                    boolean tableExists = context.fetchExists(POKER_TABLE, POKER_TABLE.TABLE_ID.eq(tableId));
                    if (!tableExists) throw badRequest("Table not found: " + tableId);

                    Long id = context.insertInto(POKER_TOURNAMENT_TABLE)
                            .set(POKER_TOURNAMENT_TABLE.TOURNAMENT_ID, tournamentId)
                            .set(POKER_TOURNAMENT_TABLE.TABLE_ID, tableId)
                            .set(POKER_TOURNAMENT_TABLE.TABLE_NUMBER, tableNumber)
                            .set(POKER_TOURNAMENT_TABLE.STATUS, "WAITING")
                            .set(POKER_TOURNAMENT_TABLE.ASSIGNED_AT, OffsetDateTime.now())
                            .returning(POKER_TOURNAMENT_TABLE.TOURNAMENT_TABLE_ID)
                            .fetchOne(POKER_TOURNAMENT_TABLE.TOURNAMENT_TABLE_ID);

                    context.update(POKER_TOURNAMENT)
                            .set(POKER_TOURNAMENT.TABLES_COUNT, DSL.coalesce(POKER_TOURNAMENT.TABLES_COUNT, 0).plus(1))
                            .set(POKER_TOURNAMENT.UPDATED_AT, OffsetDateTime.now())
                            .where(POKER_TOURNAMENT.TOURNAMENT_ID.eq(tournamentId))
                            .execute();

                    var record = context.select(
                                    POKER_TOURNAMENT_TABLE.TOURNAMENT_TABLE_ID,
                                    POKER_TOURNAMENT_TABLE.TOURNAMENT_ID,
                                    POKER_TOURNAMENT_TABLE.TABLE_ID,
                                    POKER_TABLE.TABLE_NAME,
                                    POKER_TABLE.SECURE_ID,
                                    POKER_TOURNAMENT_TABLE.TABLE_NUMBER,
                                    POKER_TOURNAMENT_TABLE.STATUS,
                                    POKER_TOURNAMENT_TABLE.ASSIGNED_AT,
                                    POKER_TOURNAMENT_TABLE.COMPLETED_AT)
                                    .from(POKER_TOURNAMENT_TABLE)
                                    .leftJoin(POKER_TABLE).on(POKER_TABLE.TABLE_ID.eq(POKER_TOURNAMENT_TABLE.TABLE_ID))
                                    .where(POKER_TOURNAMENT_TABLE.TOURNAMENT_TABLE_ID.eq(id))
                                    .fetchOne();
                    return new TournamentTableDTO(
                            record.get(POKER_TOURNAMENT_TABLE.TOURNAMENT_TABLE_ID),
                            record.get(POKER_TOURNAMENT_TABLE.TOURNAMENT_ID),
                            record.get(POKER_TOURNAMENT_TABLE.TABLE_ID),
                            record.get(POKER_TABLE.TABLE_NAME),
                            record.get(POKER_TOURNAMENT_TABLE.TABLE_NUMBER),
                            record.get(POKER_TOURNAMENT_TABLE.STATUS),
                            record.get(POKER_TOURNAMENT_TABLE.ASSIGNED_AT),
                            record.get(POKER_TOURNAMENT_TABLE.COMPLETED_AT),
                            0,
                            tableSecureId(record.get(POKER_TABLE.SECURE_ID), record.get(POKER_TOURNAMENT_TABLE.TABLE_ID)));
                });
    }

    public Uni<Void> unassignTable(Long tournamentId, Long tournamentTableId) {
        return Uni.createFrom().voidItem()
                .invoke(unused -> {
                    var row = context.selectFrom(POKER_TOURNAMENT_TABLE)
                            .where(POKER_TOURNAMENT_TABLE.TOURNAMENT_TABLE_ID.eq(tournamentTableId)
                                    .and(POKER_TOURNAMENT_TABLE.TOURNAMENT_ID.eq(tournamentId)))
                            .fetchOne();
                    if (row == null) throw notFound(tournamentTableId);
                    context.delete(POKER_TOURNAMENT_TABLE)
                            .where(POKER_TOURNAMENT_TABLE.TOURNAMENT_TABLE_ID.eq(tournamentTableId))
                            .execute();
                    Integer tablesCount = context.select(POKER_TOURNAMENT.TABLES_COUNT)
                            .from(POKER_TOURNAMENT)
                            .where(POKER_TOURNAMENT.TOURNAMENT_ID.eq(tournamentId))
                            .fetchOne(POKER_TOURNAMENT.TABLES_COUNT);
                    int nextCount = Math.max((tablesCount == null ? 0 : tablesCount) - 1, 0);
                    context.update(POKER_TOURNAMENT)
                            .set(POKER_TOURNAMENT.TABLES_COUNT, nextCount)
                            .where(POKER_TOURNAMENT.TOURNAMENT_ID.eq(tournamentId))
                            .execute();
                });
    }

    // ── Seating on start / player discovery ─────────────────────────────────

    private Uni<Void> ensureTablesAndSeat(Long tournamentId) {
        return Uni.createFrom().voidItem()
                .emitOn(QUERY_THREADS)
                .onItem().transformToUni(unused -> {
                    long unseated = context.fetchCount(POKER_TOURNAMENT_ENTRY,
                            POKER_TOURNAMENT_ENTRY.TOURNAMENT_ID.eq(tournamentId)
                                    .and(POKER_TOURNAMENT_ENTRY.TABLE_ID.isNull())
                                    .and(POKER_TOURNAMENT_ENTRY.STATUS.ne(ENTRY_ELIMINATED))
                                    .and(POKER_TOURNAMENT_ENTRY.STATUS.ne("CANCELLED")));
                    if (unseated == 0) return Uni.createFrom().voidItem();

                    var tournament = requireTournament(tournamentId);
                    int seatsPerTable = tournament.getSeatsPerTable() != null
                            ? tournament.getSeatsPerTable()
                            : defaultSeatsPerTable;
                    long entryCount = context.fetchCount(POKER_TOURNAMENT_ENTRY,
                            POKER_TOURNAMENT_ENTRY.TOURNAMENT_ID.eq(tournamentId)
                                    .and(POKER_TOURNAMENT_ENTRY.STATUS.ne(ENTRY_ELIMINATED))
                                    .and(POKER_TOURNAMENT_ENTRY.STATUS.ne("CANCELLED")));
                    if (entryCount == 0) return Uni.createFrom().voidItem();

                    int tablesNeeded = (int) Math.ceil(entryCount / (double) seatsPerTable);
                    var assigned = context.selectFrom(POKER_TOURNAMENT_TABLE)
                            .where(POKER_TOURNAMENT_TABLE.TOURNAMENT_ID.eq(tournamentId)
                                    .and(POKER_TOURNAMENT_TABLE.STATUS.ne("BROKEN"))
                                    .and(POKER_TOURNAMENT_TABLE.STATUS.ne("COMPLETED")))
                            .orderBy(POKER_TOURNAMENT_TABLE.TABLE_NUMBER.asc())
                            .fetch();

                    Uni<Void> ensure = Uni.createFrom().voidItem();
                    int missing = tablesNeeded - assigned.size();
                    for (int i = 0; i < missing; i++) {
                        final int tableNumber = assigned.size() + i + 1;
                        ensure = ensure.chain(() -> createTournamentTable(tournament, tableNumber));
                    }

                    return ensure.chain(() -> seatEntries(tournamentId, seatsPerTable));
                });
    }

    private Uni<Void> createTournamentTable(
            dev.manestack.jooq.generated.tables.records.PokerTournamentRecord tournament,
            int tableNumber) {
        Integer createdBy = tournament.getCreatedBy() != null ? tournament.getCreatedBy() : 1;
        long chips = tournament.getStartingChips() != null ? tournament.getStartingChips() : 10_000L;
        int seats = tournament.getSeatsPerTable() != null ? tournament.getSeatsPerTable() : defaultSeatsPerTable;

        Integer sb = 10;
        Integer bb = 20;
        var blind = context.selectFrom(POKER_TOURNAMENT_BLIND_LEVEL)
                .where(POKER_TOURNAMENT_BLIND_LEVEL.TOURNAMENT_ID.eq(tournament.getTournamentId()))
                .orderBy(POKER_TOURNAMENT_BLIND_LEVEL.LEVEL_NUMBER.asc())
                .limit(1)
                .fetchOne();
        if (blind != null) {
            sb = blind.getSmallBlind() != null ? blind.getSmallBlind() : sb;
            bb = blind.getBigBlind() != null ? blind.getBigBlind() : bb;
        }

        String variant = "TEXAS_HOLDEM".equalsIgnoreCase(tournament.getGameVariant())
                ? "TEXAS"
                : (tournament.getGameVariant() != null ? tournament.getGameVariant() : "TEXAS");

        GameTable input = new GameTable();
        input.setTableName("T" + tournament.getTournamentId() + "-Table" + tableNumber);
        input.setMaxPlayers(seats);
        input.setSmallBlind(sb);
        input.setBigBlind(bb);
        input.setMinBuyIn((int) Math.max(1, chips / 20));
        input.setMaxBuyIn((int) Math.max(chips, chips * 2));
        input.setGameVariant(variant);
        input.setRakePercent(0.0);

        return gameService.createTable(createdBy, input)
                .map(created -> {
                    created.setTournamentId(tournament.getTournamentId());
                    return created;
                })
                .chain(created -> assignTable(
                        tournament.getTournamentId(),
                        created.getTableId(),
                        tableNumber))
                .replaceWithVoid();
    }

    private Uni<Void> seatEntries(Long tournamentId, int seatsPerTable) {
        return Uni.createFrom().voidItem()
                .map(unused -> {
                    var tables = context.selectFrom(POKER_TOURNAMENT_TABLE)
                            .where(POKER_TOURNAMENT_TABLE.TOURNAMENT_ID.eq(tournamentId))
                            .orderBy(POKER_TOURNAMENT_TABLE.TABLE_NUMBER.asc())
                            .fetch();
                    if (tables.isEmpty()) {
                        throw badRequest("No tables assigned for tournament " + tournamentId);
                    }

                    var entries = context.selectFrom(POKER_TOURNAMENT_ENTRY)
                            .where(POKER_TOURNAMENT_ENTRY.TOURNAMENT_ID.eq(tournamentId)
                                    .and(POKER_TOURNAMENT_ENTRY.TABLE_ID.isNull())
                                    .and(POKER_TOURNAMENT_ENTRY.STATUS.ne(ENTRY_ELIMINATED))
                                    .and(POKER_TOURNAMENT_ENTRY.STATUS.ne("CANCELLED")))
                            .orderBy(POKER_TOURNAMENT_ENTRY.SEAT_NUMBER.asc(), POKER_TOURNAMENT_ENTRY.ENTRY_ID.asc())
                            .fetch();

                    int[] usedPerTable = new int[tables.size()];
                    var alreadySeated = context.selectFrom(POKER_TOURNAMENT_ENTRY)
                            .where(POKER_TOURNAMENT_ENTRY.TOURNAMENT_ID.eq(tournamentId)
                                    .and(POKER_TOURNAMENT_ENTRY.TABLE_ID.isNotNull())
                                    .and(POKER_TOURNAMENT_ENTRY.STATUS.ne(ENTRY_ELIMINATED))
                                    .and(POKER_TOURNAMENT_ENTRY.STATUS.ne("CANCELLED")))
                            .fetch();
                    for (var seated : alreadySeated) {
                        for (int i = 0; i < tables.size(); i++) {
                            if (tables.get(i).getTableId().equals(seated.getTableId())) {
                                Integer seat = seated.getSeatNumber();
                                usedPerTable[i] = Math.max(usedPerTable[i],
                                        (seat == null ? 0 : seat) + 1);
                                break;
                            }
                        }
                    }

                    OffsetDateTime now = OffsetDateTime.now();
                    int seated = 0;

                    for (var entry : entries) {
                        int tableIdx = -1;
                        for (int i = 0; i < tables.size(); i++) {
                            if (usedPerTable[i] < seatsPerTable) {
                                tableIdx = i;
                                break;
                            }
                        }
                        if (tableIdx < 0) break;

                        int seatOnTable = usedPerTable[tableIdx];
                        usedPerTable[tableIdx]++;
                        context.update(POKER_TOURNAMENT_ENTRY)
                                .set(POKER_TOURNAMENT_ENTRY.TABLE_ID, tables.get(tableIdx).getTableId())
                                .set(POKER_TOURNAMENT_ENTRY.SEAT_NUMBER, seatOnTable)
                                .set(POKER_TOURNAMENT_ENTRY.STATUS, ENTRY_SEATED)
                                .set(POKER_TOURNAMENT_ENTRY.SEATED_AT, now)
                                .where(POKER_TOURNAMENT_ENTRY.ENTRY_ID.eq(entry.getEntryId()))
                                .execute();
                        seated++;
                    }

                    LOG.infov("Seated {0} entries across {1} table(s) for tournament {2}",
                            seated, tables.size(), tournamentId);
                    return null;
                })
                .replaceWithVoid();
    }

    public Uni<TournamentMySeatDTO> getMySeat(Integer userId, Long tournamentId) {
        return Uni.createFrom().voidItem()
                .emitOn(QUERY_THREADS)
                .chain(unused -> {
                    var entry = context.selectFrom(POKER_TOURNAMENT_ENTRY)
                            .where(POKER_TOURNAMENT_ENTRY.TOURNAMENT_ID.eq(tournamentId)
                                    .and(POKER_TOURNAMENT_ENTRY.USER_ID.eq(userId)))
                            .fetchOne();
                    if (entry == null) throw badRequest("Not in this tournament");
                    if (ENTRY_ELIMINATED.equals(entry.getStatus())) {
                        throw badRequest("You have been eliminated");
                    }

                    var tournament = requireTournament(tournamentId);
                    boolean running = STATUS_RUNNING.equals(tournament.getStatus())
                            || STATUS_PAUSED.equals(tournament.getStatus());
                    if (entry.getTableId() == null) {
                        if (!running) {
                            throw badRequest("You are not seated yet. Wait for the tournament to start.");
                        }
                        return ensureTablesAndSeat(tournamentId)
                                .emitOn(QUERY_THREADS)
                                .map(unused2 -> loadMySeat(userId, tournamentId));
                    }
                    return Uni.createFrom().item(loadMySeat(userId, tournamentId));
                });
    }

    private TournamentMySeatDTO loadMySeat(Integer userId, Long tournamentId) {
        var entry = context.selectFrom(POKER_TOURNAMENT_ENTRY)
                .where(POKER_TOURNAMENT_ENTRY.TOURNAMENT_ID.eq(tournamentId)
                        .and(POKER_TOURNAMENT_ENTRY.USER_ID.eq(userId)))
                .fetchOne();
        if (entry == null) throw badRequest("Not in this tournament");
        if (ENTRY_ELIMINATED.equals(entry.getStatus())) {
            throw badRequest("You have been eliminated");
        }
        if (entry.getTableId() == null) {
            throw badRequest("You are not seated yet. Wait for the tournament to start.");
        }

        var table = context.selectFrom(POKER_TABLE)
                .where(POKER_TABLE.TABLE_ID.eq(entry.getTableId()))
                .fetchOne();
        if (table == null) throw badRequest("Tournament table not found");

        var tt = context.selectFrom(POKER_TOURNAMENT_TABLE)
                .where(POKER_TOURNAMENT_TABLE.TOURNAMENT_ID.eq(tournamentId)
                        .and(POKER_TOURNAMENT_TABLE.TABLE_ID.eq(entry.getTableId())))
                .fetchOne();

        var tournament = requireTournament(tournamentId);
        long chips = entry.getChipsReceived() != null
                ? entry.getChipsReceived()
                : (tournament.getStartingChips() != null ? tournament.getStartingChips() : 0L);

        String secureId = table.getSecureId();
        if (secureId == null || secureId.isBlank()) {
            secureId = java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(String.valueOf(entry.getTableId()).getBytes());
        }

        return new TournamentMySeatDTO(
                tournamentId,
                entry.getTableId(),
                secureId,
                table.getTableName(),
                tt != null ? tt.getTableNumber() : null,
                entry.getSeatNumber(),
                chips,
                entry.getStatus(),
                tournament.getStatus());
    }

    public Uni<List<TournamentEntryDTO>> listMyEntries(Integer userId) {
        return Uni.createFrom().voidItem()
                .emitOn(QUERY_THREADS)
                .map(unused -> {
                    var rows = context.selectFrom(POKER_TOURNAMENT_ENTRY)
                            .where(POKER_TOURNAMENT_ENTRY.USER_ID.eq(userId))
                            .orderBy(POKER_TOURNAMENT_ENTRY.REGISTERED_AT.desc())
                            .fetch();
                    return rows.stream().map(r -> toEntryDto(r.getEntryId())).toList();
                });
    }

    public int validateTournamentTakeSeat(Integer userId, Long tournamentId, Long tableId, Integer seatNumber) {
        var tournament = requireTournament(tournamentId);
        String status = tournament.getStatus();
        if (!STATUS_RUNNING.equals(status) && !STATUS_PAUSED.equals(status)) {
            throw badRequest("Tournament is not running");
        }
        var entry = context.selectFrom(POKER_TOURNAMENT_ENTRY)
                .where(POKER_TOURNAMENT_ENTRY.TOURNAMENT_ID.eq(tournamentId)
                        .and(POKER_TOURNAMENT_ENTRY.USER_ID.eq(userId)))
                .fetchOne();
        if (entry == null) throw badRequest("Not in this tournament");
        if (ENTRY_ELIMINATED.equals(entry.getStatus())) throw badRequest("You have been eliminated");
        if (entry.getTableId() == null) {
            throw badRequest("You are not seated yet. Wait for the tournament to start.");
        }
        if (!entry.getTableId().equals(tableId)) {
            throw badRequest("You are not assigned to this table");
        }
        if (entry.getSeatNumber() != null && !entry.getSeatNumber().equals(seatNumber)) {
            throw badRequest("You are not assigned to seat " + seatNumber);
        }
        long chips = entry.getChipsReceived() != null
                ? entry.getChipsReceived()
                : (tournament.getStartingChips() != null ? tournament.getStartingChips() : 0L);
        if (chips <= 0) throw badRequest("No tournament chips for seat");
        return (int) chips;
    }

    // ── Prizes / payouts ─────────────────────────────────────────────────────

    public Uni<List<TournamentPayoutDTO>> listPayouts(Long tournamentId) {
        return Uni.createFrom().voidItem()
                .map(unused -> {
                    var records = context.select(
                                    POKER_TOURNAMENT_PAYOUT.PAYOUT_ID,
                                    POKER_TOURNAMENT_PAYOUT.TOURNAMENT_ID,
                                    POKER_TOURNAMENT_PAYOUT.ENTRY_ID,
                                    POKER_TOURNAMENT_PAYOUT.USER_ID,
                                    POKER_USER.USERNAME,
                                    POKER_TOURNAMENT_PAYOUT.AMOUNT,
                                    POKER_TOURNAMENT_PAYOUT.PAYOUT_TYPE,
                                    POKER_TOURNAMENT_PAYOUT.STATUS,
                                    POKER_TOURNAMENT_PAYOUT.PROCESSED_AT,
                                    POKER_TOURNAMENT_PAYOUT.CREATED_AT)
                                    .from(POKER_TOURNAMENT_PAYOUT)
                                    .leftJoin(POKER_USER).on(POKER_USER.USER_ID.eq(POKER_TOURNAMENT_PAYOUT.USER_ID))
                                    .where(POKER_TOURNAMENT_PAYOUT.TOURNAMENT_ID.eq(tournamentId))
                                    .orderBy(POKER_TOURNAMENT_PAYOUT.CREATED_AT.desc())
                                    .fetch();
                    return records.stream()
                            .map(r -> new TournamentPayoutDTO(
                                    r.get(POKER_TOURNAMENT_PAYOUT.PAYOUT_ID),
                                    r.get(POKER_TOURNAMENT_PAYOUT.TOURNAMENT_ID),
                                    r.get(POKER_TOURNAMENT_PAYOUT.ENTRY_ID),
                                    r.get(POKER_TOURNAMENT_PAYOUT.USER_ID),
                                    r.get(POKER_USER.USERNAME),
                                    r.get(POKER_TOURNAMENT_PAYOUT.AMOUNT),
                                    r.get(POKER_TOURNAMENT_PAYOUT.PAYOUT_TYPE),
                                    r.get(POKER_TOURNAMENT_PAYOUT.STATUS),
                                    r.get(POKER_TOURNAMENT_PAYOUT.PROCESSED_AT),
                                    r.get(POKER_TOURNAMENT_PAYOUT.CREATED_AT)))
                            .toList();
                });
    }

    public Uni<Void> distributePrizes(Long tournamentId) {
        return Uni.createFrom().voidItem()
                .emitOn(QUERY_THREADS)
                .map(unused -> {
                    var tournament = requireTournament(tournamentId);
                    int prizePool = tournament.getPrizePool() != null ? tournament.getPrizePool() : 0;
                    if (prizePool <= 0) return List.<long[]>of();

                    finalizeRemainingPositions(tournamentId);

                    var prizes = context.selectFrom(POKER_TOURNAMENT_PRIZE)
                            .where(POKER_TOURNAMENT_PRIZE.TOURNAMENT_ID.eq(tournamentId))
                            .orderBy(POKER_TOURNAMENT_PRIZE.POSITION.asc())
                            .fetch();
                    if (prizes.isEmpty()) return List.<long[]>of();

                    var winners = context.selectFrom(POKER_TOURNAMENT_ENTRY)
                            .where(POKER_TOURNAMENT_ENTRY.TOURNAMENT_ID.eq(tournamentId)
                                    .and(POKER_TOURNAMENT_ENTRY.FINAL_POSITION.isNotNull()))
                            .orderBy(POKER_TOURNAMENT_ENTRY.FINAL_POSITION.asc())
                            .fetch();

                    Map<Integer, dev.manestack.jooq.generated.tables.records.PokerTournamentEntryRecord> byPosition = new HashMap<>();
                    for (var w : winners) {
                        byPosition.put(w.getFinalPosition(), w);
                    }

                    int allocated = 0;
                    List<long[]> sharePlan = new ArrayList<>();
                    for (var prize : prizes) {
                        var entry = byPosition.get(prize.getPosition());
                        if (entry == null) continue;
                        int amount;
                        if (prize.getFixedAmount() != null) {
                            amount = prize.getFixedAmount();
                        } else if (prize.getPercentage() != null) {
                            amount = prize.getPercentage()
                                    .multiply(BigDecimal.valueOf(prizePool))
                                    .setScale(0, RoundingMode.DOWN)
                                    .intValue();
                        } else {
                            continue;
                        }
                        amount = Math.min(amount, prizePool - allocated);
                        if (amount <= 0) continue;
                        allocated += amount;
                        sharePlan.add(new long[]{entry.getEntryId(), entry.getUserId(), amount});
                    }

                    OffsetDateTime now = OffsetDateTime.now();
                    for (long[] row : sharePlan) {
                        context.insertInto(POKER_TOURNAMENT_PAYOUT)
                                .set(POKER_TOURNAMENT_PAYOUT.TOURNAMENT_ID, tournamentId)
                                .set(POKER_TOURNAMENT_PAYOUT.ENTRY_ID, row[0])
                                .set(POKER_TOURNAMENT_PAYOUT.USER_ID, (int) row[1])
                                .set(POKER_TOURNAMENT_PAYOUT.AMOUNT, (int) row[2])
                                .set(POKER_TOURNAMENT_PAYOUT.PAYOUT_TYPE, "PRIZE")
                                .set(POKER_TOURNAMENT_PAYOUT.STATUS, "COMPLETED")
                                .set(POKER_TOURNAMENT_PAYOUT.PROCESSED_AT, now)
                                .set(POKER_TOURNAMENT_PAYOUT.CREATED_AT, now)
                                .execute();

                        context.update(POKER_TOURNAMENT_ENTRY)
                                .set(POKER_TOURNAMENT_ENTRY.PRIZE_WON, (int) row[2])
                                .where(POKER_TOURNAMENT_ENTRY.ENTRY_ID.eq(row[0]))
                                .execute();
                    }
                    return sharePlan;
                })
                .onItem().transformToUni(sharePlan -> {
                    Uni<Void> chain = Uni.createFrom().voidItem();
                    for (long[] row : sharePlan) {
                        int userId = (int) row[1];
                        int amount = (int) row[2];
                        chain = chain.chain(() -> creditUser(userId, amount, "TOURNAMENT_PRIZE"));
                    }
                    return chain;
                });
    }

    private void finalizeRemainingPositions(Long tournamentId) {
        var remaining = context.selectFrom(POKER_TOURNAMENT_ENTRY)
                .where(POKER_TOURNAMENT_ENTRY.TOURNAMENT_ID.eq(tournamentId)
                        .and(POKER_TOURNAMENT_ENTRY.FINAL_POSITION.isNull())
                        .and(POKER_TOURNAMENT_ENTRY.STATUS.eq("REGISTERED")))
                .orderBy(POKER_TOURNAMENT_ENTRY.ENTRY_ID.asc())
                .fetch();
        if (remaining.isEmpty()) return;

        var assigned = context.select(POKER_TOURNAMENT_ENTRY.FINAL_POSITION)
                .from(POKER_TOURNAMENT_ENTRY)
                .where(POKER_TOURNAMENT_ENTRY.TOURNAMENT_ID.eq(tournamentId)
                        .and(POKER_TOURNAMENT_ENTRY.FINAL_POSITION.isNotNull()))
                .fetchSet(POKER_TOURNAMENT_ENTRY.FINAL_POSITION);

        int totalEntries = context.fetchCount(POKER_TOURNAMENT_ENTRY,
                POKER_TOURNAMENT_ENTRY.TOURNAMENT_ID.eq(tournamentId));
        int maxPosition = Math.max(totalEntries, assigned.stream().mapToInt(Integer::intValue).max().orElse(0));

        List<Integer> freePositions = new ArrayList<>();
        for (int pos = 1; pos <= maxPosition; pos++) {
            if (!assigned.contains(pos)) freePositions.add(pos);
        }
        freePositions.sort(Integer::compareTo);

        int idx = 0;
        for (var entry : remaining) {
            if (idx >= freePositions.size()) break;
            int pos = freePositions.get(idx++);
            context.update(POKER_TOURNAMENT_ENTRY)
                    .set(POKER_TOURNAMENT_ENTRY.FINAL_POSITION, pos)
                    .set(POKER_TOURNAMENT_ENTRY.STATUS, ENTRY_ELIMINATED)
                    .set(POKER_TOURNAMENT_ENTRY.ELIMINATED_AT, OffsetDateTime.now())
                    .where(POKER_TOURNAMENT_ENTRY.ENTRY_ID.eq(entry.getEntryId()))
                    .execute();
        }
    }

    public Uni<Void> refundAllEntries(Long tournamentId) {
        return Uni.createFrom().voidItem()
                .emitOn(QUERY_THREADS)
                .map(unused -> {
                    var entries = context.selectFrom(POKER_TOURNAMENT_ENTRY)
                            .where(POKER_TOURNAMENT_ENTRY.TOURNAMENT_ID.eq(tournamentId)
                                    .and(POKER_TOURNAMENT_ENTRY.STATUS.ne("CANCELLED")))
                            .fetch();
                    OffsetDateTime now = OffsetDateTime.now();
                    List<long[]> refunds = new ArrayList<>();
                    for (var entry : entries) {
                        int amount = entry.getAmountPaid() != null ? entry.getAmountPaid() : 0;
                        if (amount > 0) {
                            context.insertInto(POKER_TOURNAMENT_PAYOUT)
                                    .set(POKER_TOURNAMENT_PAYOUT.TOURNAMENT_ID, tournamentId)
                                    .set(POKER_TOURNAMENT_PAYOUT.ENTRY_ID, entry.getEntryId())
                                    .set(POKER_TOURNAMENT_PAYOUT.USER_ID, entry.getUserId())
                                    .set(POKER_TOURNAMENT_PAYOUT.AMOUNT, amount)
                                    .set(POKER_TOURNAMENT_PAYOUT.PAYOUT_TYPE, "CANCELLATION_REFUND")
                                    .set(POKER_TOURNAMENT_PAYOUT.STATUS, "COMPLETED")
                                    .set(POKER_TOURNAMENT_PAYOUT.PROCESSED_AT, now)
                                    .set(POKER_TOURNAMENT_PAYOUT.CREATED_AT, now)
                                    .execute();
                            refunds.add(new long[]{entry.getUserId(), amount});
                        }
                        context.update(POKER_TOURNAMENT_ENTRY)
                                .set(POKER_TOURNAMENT_ENTRY.STATUS, "CANCELLED")
                                .where(POKER_TOURNAMENT_ENTRY.ENTRY_ID.eq(entry.getEntryId()))
                                .execute();
                    }
                    return refunds;
                })
                .onItem().transformToUni(refunds -> {
                    Uni<Void> chain = Uni.createFrom().voidItem();
                    for (long[] row : refunds) {
                        int userId = (int) row[0];
                        int amount = (int) row[1];
                        chain = chain.chain(() -> creditUser(userId, amount, "TOURNAMENT_REFUND"));
                    }
                    return chain;
                });
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private TournamentDTO fetchTournamentInternal(Long tournamentId) {
        var t = context.selectFrom(POKER_TOURNAMENT)
                .where(POKER_TOURNAMENT.TOURNAMENT_ID.eq(tournamentId))
                .fetchOne();
        if (t == null) throw notFound(tournamentId);

        List<BlindLevelDTO> blinds = context.selectFrom(POKER_TOURNAMENT_BLIND_LEVEL)
                .where(POKER_TOURNAMENT_BLIND_LEVEL.TOURNAMENT_ID.eq(tournamentId))
                .orderBy(POKER_TOURNAMENT_BLIND_LEVEL.LEVEL_NUMBER.asc())
                .fetch()
                .stream()
                .map(r -> new BlindLevelDTO(
                        r.getBlindId(),
                        r.getLevelNumber(),
                        r.getSmallBlind(),
                        r.getBigBlind(),
                        r.getAnte(),
                        r.getDurationMinutes(),
                        r.getIsBreak(),
                        r.getBreakDurationMinutes()))
                .toList();

        List<PrizeDTO> prizes = context.selectFrom(POKER_TOURNAMENT_PRIZE)
                .where(POKER_TOURNAMENT_PRIZE.TOURNAMENT_ID.eq(tournamentId))
                .orderBy(POKER_TOURNAMENT_PRIZE.POSITION.asc())
                .fetch()
                .stream()
                .map(r -> new PrizeDTO(
                        r.getPrizeId(),
                        r.getPosition(),
                        r.getPercentage(),
                        r.getFixedAmount(),
                        r.getDescription()))
                .toList();

        Integer currentEntries = context.fetchCount(POKER_TOURNAMENT_ENTRY,
                POKER_TOURNAMENT_ENTRY.TOURNAMENT_ID.eq(tournamentId)
                        .and(POKER_TOURNAMENT_ENTRY.STATUS.ne("CANCELLED")));
        Integer tablesActive = context.fetchCount(POKER_TOURNAMENT_TABLE,
                POKER_TOURNAMENT_TABLE.TOURNAMENT_ID.eq(tournamentId));

        return new TournamentDTO(
                t.getTournamentId(),
                t.getTournamentName(),
                t.getDescription(),
                t.getTournamentType(),
                t.getGameVariant(),
                t.getRegistrationStart(),
                t.getRegistrationEnd(),
                t.getStartTime(),
                t.getEndTime(),
                t.getStatus(),
                t.getPrizePool(),
                t.getGuaranteedPrizePool(),
                t.getPlacesPaid(),
                toJsonObject(t.getPrizeDistribution()),
                t.getBuyIn(),
                t.getEntryFee(),
                t.getRebuyAmount(),
                t.getRebuyFee(),
                t.getAddonAmount(),
                t.getAddonFee(),
                t.getMaxRebuys(),
                t.getAddonAllowed(),
                t.getStartingChips(),
                t.getRebuyChips(),
                t.getAddonChips(),
                toJsonObject(t.getBlindStructure()),
                t.getCurrentBlindLevel(),
                t.getNextBlindIncrease(),
                t.getMaxPlayers(),
                t.getMinPlayers(),
                t.getTablesCount(),
                t.getSeatsPerTable(),
                t.getLateRegistrationAllowed(),
                t.getLateRegistrationEndLevel(),
                t.getCreatedBy(),
                t.getCreatedAt(),
                t.getUpdatedAt(),
                t.getCancelledReason(),
                t.getCompletedAt(),
                blinds,
                prizes,
                currentEntries,
                tablesActive);
    }

    private TournamentEntryDTO toEntryDto(Long entryId) {
        var r = context.selectFrom(POKER_TOURNAMENT_ENTRY)
                .where(POKER_TOURNAMENT_ENTRY.ENTRY_ID.eq(entryId))
                .fetchOne();
        if (r == null) throw notFound(entryId);
        String username = context.select(POKER_USER.USERNAME)
                .from(POKER_USER)
                .where(POKER_USER.USER_ID.eq(r.getUserId()))
                .fetchOne(POKER_USER.USERNAME);
        return new TournamentEntryDTO(
                r.getEntryId(),
                r.getTournamentId(),
                r.getUserId(),
                username,
                r.getEntryType(),
                r.getAmountPaid(),
                r.getChipsReceived(),
                r.getSeatNumber(),
                r.getTableId(),
                r.getStatus(),
                r.getRegisteredAt(),
                r.getSeatedAt(),
                r.getEliminatedAt(),
                r.getEliminatedLevel(),
                r.getFinalPosition(),
                r.getPrizeWon(),
                r.getRebuyCount(),
                r.getAddonTaken());
    }

    private dev.manestack.jooq.generated.tables.records.PokerTournamentRecord requireTournament(Long tournamentId) {
        var t = context.selectFrom(POKER_TOURNAMENT)
                .where(POKER_TOURNAMENT.TOURNAMENT_ID.eq(tournamentId))
                .fetchOne();
        if (t == null) throw notFound(tournamentId);
        return t;
    }

    private String effectiveStatus(dev.manestack.jooq.generated.tables.records.PokerTournamentRecord tournament, OffsetDateTime now) {
        String status = tournament.getStatus();
        if (STATUS_PENDING.equals(status)
                && !now.isBefore(tournament.getRegistrationStart())
                && !now.isAfter(tournament.getRegistrationEnd())) {
            return STATUS_REGISTERING;
        }
        return status;
    }

    private String resolveInitialStatus(TournamentCreateRequest req, OffsetDateTime now) {
        if (now.isBefore(req.registrationStart())) return STATUS_PENDING;
        if (!now.isAfter(req.registrationEnd())) return STATUS_REGISTERING;
        if (!now.isAfter(req.startTime())) return STATUS_REGISTERING;
        return STATUS_PENDING;
    }

    private void validateCreateRequest(TournamentCreateRequest req) {
        if (req == null) throw badRequest("Request body is required");
        if (req.tournamentName() == null || req.tournamentName().isBlank()) {
            throw badRequest("tournamentName is required");
        }
        if (req.registrationStart() == null || req.registrationEnd() == null || req.startTime() == null) {
            throw badRequest("registrationStart, registrationEnd and startTime are required");
        }
                    if (!req.registrationStart().isBefore(req.registrationEnd())) {
                        throw badRequest("registrationStart must be before registrationEnd");
                    }
                    if (req.registrationEnd().isAfter(req.startTime())) {
                        throw badRequest("registrationEnd cannot be after startTime");
                    }
        if (req.buyIn() == null || req.buyIn() < 0) throw badRequest("buyIn must be >= 0");
        if (req.maxPlayers() == null || req.maxPlayers() < 2) throw badRequest("maxPlayers must be >= 2");
        if (req.startingChips() == null || req.startingChips() <= 0) throw badRequest("startingChips must be > 0");
        if (req.blindStructureJson() == null || req.blindStructureJson().isBlank()) {
            throw badRequest("blindStructureJson is required");
        }
        try {
            Object parsed = new JsonArray(req.blindStructureJson());
        } catch (Exception e) {
            throw badRequest("blindStructureJson must be a JSON array");
        }
    }

    private Integer estimatePrizePool(TournamentCreateRequest req) {
        int buyIn = req.buyIn() != null ? req.buyIn() : 0;
        int maxPlayers = req.maxPlayers() != null ? req.maxPlayers() : 0;
        return buyIn * maxPlayers;
    }

    private void recalculatePrizePool(Long tournamentId) {
        var t = context.select(POKER_TOURNAMENT.BUY_IN, POKER_TOURNAMENT.ENTRY_FEE)
                .from(POKER_TOURNAMENT)
                .where(POKER_TOURNAMENT.TOURNAMENT_ID.eq(tournamentId))
                .fetchOne();
        if (t == null) return;
        int buyIn = t.get(POKER_TOURNAMENT.BUY_IN) != null ? t.get(POKER_TOURNAMENT.BUY_IN) : 0;
        long active = context.fetchCount(POKER_TOURNAMENT_ENTRY,
                POKER_TOURNAMENT_ENTRY.TOURNAMENT_ID.eq(tournamentId)
                        .and(POKER_TOURNAMENT_ENTRY.STATUS.ne("CANCELLED")));
        context.update(POKER_TOURNAMENT)
                .set(POKER_TOURNAMENT.PRIZE_POOL, (int) (buyIn * active))
                .set(POKER_TOURNAMENT.UPDATED_AT, OffsetDateTime.now())
                .where(POKER_TOURNAMENT.TOURNAMENT_ID.eq(tournamentId))
                .execute();
    }

    private void insertBlindLevels(Long tournamentId, String blindStructureJson) {
        if (blindStructureJson == null || blindStructureJson.isBlank()) return;
        JsonArray arr;
        try {
            arr = new JsonArray(blindStructureJson);
        } catch (Exception e) {
            throw badRequest("Invalid blindStructureJson");
        }
        for (int i = 0; i < arr.size(); i++) {
            JsonObject level = arr.getJsonObject(i);
            context.insertInto(POKER_TOURNAMENT_BLIND_LEVEL)
                    .set(POKER_TOURNAMENT_BLIND_LEVEL.TOURNAMENT_ID, tournamentId)
                    .set(POKER_TOURNAMENT_BLIND_LEVEL.LEVEL_NUMBER,
                            level.getInteger("level", i + 1))
                    .set(POKER_TOURNAMENT_BLIND_LEVEL.SMALL_BLIND,
                            level.getInteger("smallBlind", level.getInteger("small_blind", 0)))
                    .set(POKER_TOURNAMENT_BLIND_LEVEL.BIG_BLIND,
                            level.getInteger("bigBlind", level.getInteger("big_blind", 0)))
                    .set(POKER_TOURNAMENT_BLIND_LEVEL.ANTE,
                            level.getInteger("ante", 0))
                    .set(POKER_TOURNAMENT_BLIND_LEVEL.DURATION_MINUTES,
                            level.getInteger("duration", level.getInteger("durationMinutes", 15)))
                    .set(POKER_TOURNAMENT_BLIND_LEVEL.IS_BREAK,
                            level.getBoolean("isBreak", false))
                    .set(POKER_TOURNAMENT_BLIND_LEVEL.BREAK_DURATION_MINUTES,
                            level.getInteger("breakDurationMinutes", 0))
                    .execute();
        }
    }

    private void insertPrizes(Long tournamentId, TournamentCreateRequest req) {
        if (req.prizeDistributionJson() != null && !req.prizeDistributionJson().isBlank()) {
            insertPrizeDistribution(tournamentId, req.prizeDistributionJson());
            return;
        }
        int places = req.placesPaid() != null ? req.placesPaid() : 0;
        if (places <= 0) return;
        // Default: 50/30/20 for top 3, equal split beyond
        Map<Integer, BigDecimal> defaults = Map.of(
                1, new BigDecimal("0.50"),
                2, new BigDecimal("0.30"),
                3, new BigDecimal("0.20"));
        for (int pos = 1; pos <= places; pos++) {
            BigDecimal pct = defaults.get(pos);
            context.insertInto(POKER_TOURNAMENT_PRIZE)
                    .set(POKER_TOURNAMENT_PRIZE.TOURNAMENT_ID, tournamentId)
                    .set(POKER_TOURNAMENT_PRIZE.POSITION, pos)
                    .set(POKER_TOURNAMENT_PRIZE.PERCENTAGE, pct)
                    .execute();
        }
    }

    private void insertPrizeDistribution(Long tournamentId, String prizeDistributionJson) {
        try {
            Object parsed = prizeDistributionJson.trim().startsWith("{")
                    ? new JsonObject(prizeDistributionJson)
                    : new JsonArray(prizeDistributionJson);
            if (parsed instanceof JsonObject obj) {
                for (String key : obj.fieldNames()) {
                    int pos;
                    try {
                        pos = Integer.parseInt(key);
                    } catch (NumberFormatException e) {
                        continue;
                    }
                    Number val = obj.getNumber(key);
                    if (val == null) continue;
                    context.insertInto(POKER_TOURNAMENT_PRIZE)
                            .set(POKER_TOURNAMENT_PRIZE.TOURNAMENT_ID, tournamentId)
                            .set(POKER_TOURNAMENT_PRIZE.POSITION, pos)
                            .set(POKER_TOURNAMENT_PRIZE.PERCENTAGE, BigDecimal.valueOf(val.doubleValue()))
                            .execute();
                }
            } else if (parsed instanceof JsonArray arr) {
                for (int i = 0; i < arr.size(); i++) {
                    JsonObject p = arr.getJsonObject(i);
                    Integer pos = p.getInteger("position", i + 1);
                    Number pct = p.getNumber("percentage");
                    Integer fixed = p.getInteger("fixedAmount");
                    var step = context.insertInto(POKER_TOURNAMENT_PRIZE)
                            .set(POKER_TOURNAMENT_PRIZE.TOURNAMENT_ID, tournamentId)
                            .set(POKER_TOURNAMENT_PRIZE.POSITION, pos);
                    if (pct != null) step = step.set(POKER_TOURNAMENT_PRIZE.PERCENTAGE, BigDecimal.valueOf(pct.doubleValue()));
                    if (fixed != null) step = step.set(POKER_TOURNAMENT_PRIZE.FIXED_AMOUNT, fixed);
                    if (p.getString("description") != null) step = step.set(POKER_TOURNAMENT_PRIZE.DESCRIPTION, p.getString("description"));
                    step.execute();
                }
            }
        } catch (Exception e) {
            throw badRequest("Invalid prizeDistributionJson: " + e.getMessage());
        }
    }

    private Uni<Void> chargeUser(Integer userId, int amount, String type) {
        if (amount <= 0) return Uni.createFrom().voidItem();
        return balanceService.fetchUserBalance(userId)
                .onItem().transformToUni(balance -> {
                    if (balance.getBalance() < amount) {
                        return Uni.createFrom().failure(badRequest(
                                "Insufficient balance (need " + amount + ", have " + balance.getBalance() + ")"));
                    }
                    return depositService.createOutcomeRecord(userId, -amount, type)
                            .chain(() -> depositService.approveOutcomeRecords(userId));
                });
    }

    private Uni<Void> creditUser(Integer userId, int amount, String type) {
        if (amount <= 0) return Uni.createFrom().voidItem();
        return depositService.createOutcomeRecord(userId, amount, type)
                .chain(() -> depositService.approveOutcomeRecords(userId));
    }

    private Integer nextSeatNumber(Long tournamentId) {
        Integer maxSeat = context.select(DSL.max(POKER_TOURNAMENT_ENTRY.SEAT_NUMBER))
                .from(POKER_TOURNAMENT_ENTRY)
                .where(POKER_TOURNAMENT_ENTRY.TOURNAMENT_ID.eq(tournamentId))
                .fetchOne(DSL.max(POKER_TOURNAMENT_ENTRY.SEAT_NUMBER));
        return (maxSeat == null ? -1 : maxSeat) + 1;
    }

    private static org.jooq.JSONB jsonbOrNull(String json) {
        if (json == null || json.isBlank()) return null;
        return org.jooq.JSONB.valueOf(json);
    }

    private static JsonObject toJsonObject(org.jooq.JSONB value) {
        if (value == null) return null;
        try {
            return new JsonObject(value.data());
        } catch (Exception e) {
            return null;
        }
    }

    private static IllegalArgumentException badRequest(String msg) {
        return new IllegalArgumentException(msg);
    }

    private static IllegalArgumentException notFound(Object id) {
        return new IllegalArgumentException("Tournament not found: " + id);
    }
}