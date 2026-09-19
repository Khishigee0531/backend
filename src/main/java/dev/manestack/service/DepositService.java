package dev.manestack.service;

import dev.manestack.api.ws.GlobalSocket;
import dev.manestack.domain.user.*;
import dev.manestack.jooq.generated.tables.records.PokerDepositRecord;
import dev.manestack.jooq.generated.tables.records.PokerOutcomeRecord;
import dev.manestack.jooq.generated.tables.records.PokerWithdrawalRecord;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;
import org.jooq.SelectConditionStep;
import org.jooq.exception.IntegrityConstraintViolationException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static dev.manestack.jooq.generated.Tables.*;

@ApplicationScoped
public class DepositService {
    private static final Logger LOG = Logger.getLogger(DepositService.class);
    private final ExecutorService QUERY_THREADS = Executors.newFixedThreadPool(3);

    @Inject
    DSLContext context;

    @Inject
    BalanceService balanceService;

    @Inject
    GameService gameService;

    // ── Deposits ──────────────────────────────────────────────────────────────

    public Uni<List<Deposit>> fetchDeposits(Integer userId) {
        return Uni.createFrom().voidItem()
                .emitOn(QUERY_THREADS)
                .map(unused -> {
                    List<Deposit> deposits = new ArrayList<>();
                    SelectConditionStep<Record> selectStep = context.select()
                            .from(POKER_DEPOSIT)
                            .leftJoin(POKER_USER).on(POKER_DEPOSIT.USER_ID.eq(POKER_USER.USER_ID))
                            .where(POKER_USER.USER_ID.isNotNull());
                    if (userId != null) {
                        selectStep = selectStep.and(POKER_DEPOSIT.USER_ID.eq(userId));
                    }
                    selectStep.orderBy(POKER_DEPOSIT.CREATE_DATE.desc()).fetch().forEach(record -> {
                        PokerDepositRecord depositRecord = record.into(POKER_DEPOSIT);
                        Deposit deposit = new Deposit(depositRecord);
                        User user = record.into(POKER_USER).into(User.class);
                        deposit.setUser(user);
                        deposits.add(deposit);
                    });
                    return deposits;
                });
    }

    public Uni<Deposit> createDepositRequest(Integer userId, Deposit deposit) {
        return balanceService.fetchUser(userId)
            .emitOn(QUERY_THREADS)
            .chain(user -> {
                deposit.setUserId(userId);
                deposit.validate();

                try {
                    PokerDepositRecord record;
                    try {
                        record = context.insertInto(POKER_DEPOSIT)
                                .set(POKER_DEPOSIT.USER_ID, userId)
                                .set(POKER_DEPOSIT.AMOUNT, deposit.getAmount())
                                .set(POKER_DEPOSIT.TYPE, deposit.getType().name())
                                .set(POKER_DEPOSIT.CREATE_DATE, OffsetDateTime.now())
                                .set(POKER_DEPOSIT.DETAILS, JSONB.valueOf(deposit.getDetails().encode()))
                                .returning()
                                .fetchOne();

                        try {
                            context.update(POKER_DEPOSIT)
                                .set(org.jooq.impl.DSL.field("status", String.class), Deposit.DepositStatus.PENDING.name())
                                .where(POKER_DEPOSIT.DEPOSIT_ID.eq(record.getDepositId()))
                                .execute();
                        } catch (Exception e) {
                            LOG.warnv("STATUS field not found, skipping. Please run database migration: {0}", e.getMessage());
                        }
                    } catch (Exception e) {
                        LOG.error("Error creating deposit request", e);
                        return Uni.createFrom().failure(new RuntimeException("Failed to create deposit request"));
                    }

                    Deposit result = new Deposit(record);
                    try {
                        GlobalSocket socket = CDI.current().select(GlobalSocket.class).get();
                        socket.sendDepositNotification(userId, deposit.getAmount(), deposit.getType().name());
                    } catch (Exception e) {
                        LOG.warn("Failed to send deposit notification: " + e.getMessage());
                    }
                    return Uni.createFrom().item(result);
                } catch (IntegrityConstraintViolationException integrityException) {
                    throw new RuntimeException("Failed to create deposit request");
                }
            });
    }

    public Uni<Deposit> approveDeposit(Integer agentId, Long depositId) {
        return fetchDepositById(depositId)
                .chain(deposit -> {
                    if (deposit.getStatus() == Deposit.DepositStatus.APPROVED) {
                        throw new RuntimeException("Deposit already approved");
                    }
                    if (deposit.getAmount() <= 0) {
                        throw new RuntimeException("Invalid deposit amount");
                    }
                    PokerDepositRecord record = context.update(POKER_DEPOSIT)
                            .set(POKER_DEPOSIT.ADMIN_ID, agentId)
                            .set(POKER_DEPOSIT.APPROVED_DATE, OffsetDateTime.now())
                            .set(org.jooq.impl.DSL.field("status", String.class), Deposit.DepositStatus.APPROVED.name())
                            .where(POKER_DEPOSIT.DEPOSIT_ID.eq(depositId))
                            .returning()
                            .fetchOne();

                    Deposit updatedDeposit = new Deposit(record);

                    return balanceService.incrementBalance(updatedDeposit.getUserId(), updatedDeposit.getAmount())
                            .invoke(userBalance -> {
                                gameService.notifyBalanceUpdate(updatedDeposit.getUserId(), userBalance);
                                try {
                                    GlobalSocket socket = CDI.current().select(GlobalSocket.class).get();
                                    JsonObject depositApproved = new JsonObject()
                                            .put("type", "DEPOSIT_APPROVED")
                                            .put("data", new JsonObject()
                                                    .put("depositId", updatedDeposit.getDepositId())
                                                    .put("amount", updatedDeposit.getAmount())
                                                    .put("newBalance", userBalance.getBalance())
                                            );
                                    GlobalSocket.sendToUser(updatedDeposit.getUserId(), depositApproved.toString());

                                    JsonObject adminUpdate = new JsonObject()
                                            .put("type", "DEPOSIT_STATUS_UPDATE")
                                            .put("data", new JsonObject()
                                                    .put("depositId", updatedDeposit.getDepositId())
                                                    .put("userId", updatedDeposit.getUserId())
                                                    .put("status", "APPROVED")
                                                    .put("amount", updatedDeposit.getAmount())
                                            );
                                    socket.broadcastToAdmins(adminUpdate.toString());
                                } catch (Exception e) {
                                    LOG.warn("Failed to send deposit notifications: " + e.getMessage());
                                }
                            })
                            .replaceWith(updatedDeposit);
                });
    }

    public Uni<Deposit> denyDeposit(Integer agentId, Long depositId, String reason) {
        return fetchDepositById(depositId)
                .map(deposit -> {
                    if (deposit.getStatus() == Deposit.DepositStatus.APPROVED || deposit.getStatus() == Deposit.DepositStatus.DENIED) {
                        throw new RuntimeException("Deposit already processed");
                    }
                    PokerDepositRecord record = context.update(POKER_DEPOSIT)
                            .set(POKER_DEPOSIT.ADMIN_ID, agentId)
                            .set(POKER_DEPOSIT.APPROVED_DATE, OffsetDateTime.now())
                            .set(org.jooq.impl.DSL.field("status", String.class), Deposit.DepositStatus.DENIED.name())
                            .set(org.jooq.impl.DSL.field("denied_reason", String.class), reason)
                            .where(POKER_DEPOSIT.DEPOSIT_ID.eq(depositId))
                            .returning()
                            .fetchOne();
                    Deposit updatedDeposit = new Deposit(record);

                    try {
                        GlobalSocket socket = CDI.current().select(GlobalSocket.class).get();
                        JsonObject depositDenied = new JsonObject()
                                .put("type", "DEPOSIT_STATUS_UPDATE")
                                .put("data", new JsonObject()
                                        .put("depositId", updatedDeposit.getDepositId())
                                        .put("amount", updatedDeposit.getAmount())
                                        .put("reason", reason != null ? reason : "")
                                );
                        GlobalSocket.sendToUser(updatedDeposit.getUserId(), depositDenied.toString());
                    } catch (Exception e) {
                        LOG.warn("Failed to send DEPOSIT_DENIED notification: " + e.getMessage());
                    }

                    return updatedDeposit;
                });
    }

    private Uni<Deposit> fetchDepositById(Long depositId) {
        return Uni.createFrom().voidItem()
                .emitOn(QUERY_THREADS)
                .map(unused -> {
                    PokerDepositRecord record = context.selectFrom(POKER_DEPOSIT)
                            .where(POKER_DEPOSIT.DEPOSIT_ID.eq(depositId))
                            .fetchOne();
                    if (record != null) {
                        return new Deposit(record);
                    } else {
                        throw new RuntimeException("Deposit not found");
                    }
                });
    }

    // ── Withdrawals ───────────────────────────────────────────────────────────

    public Uni<List<Withdrawal>> fetchWithdrawals(Integer userId) {
        return Uni.createFrom().voidItem()
                .emitOn(QUERY_THREADS)
                .map(unused -> {
                    List<Withdrawal> withdrawals = new ArrayList<>();
                    SelectConditionStep<Record> selectStep = context.select()
                            .from(POKER_WITHDRAWAL)
                            .leftJoin(POKER_USER).on(POKER_WITHDRAWAL.USER_ID.eq(POKER_USER.USER_ID))
                            .where(POKER_USER.USER_ID.isNotNull());
                    if (userId != null) {
                        selectStep = selectStep.and(POKER_WITHDRAWAL.USER_ID.eq(userId));
                    }
                    selectStep.orderBy(POKER_WITHDRAWAL.CREATE_DATE.desc()).fetch().forEach(record -> {
                        PokerWithdrawalRecord withdrawalRecord = record.into(POKER_WITHDRAWAL);
                        Withdrawal withdrawal = new Withdrawal(withdrawalRecord);
                        User user = record.into(POKER_USER).into(User.class);
                        withdrawal.setUser(user);
                        withdrawals.add(withdrawal);
                    });
                    return withdrawals;
                });
    }

    public Uni<Withdrawal> fetchWithdrawalById(Long withdrawalId) {
        return Uni.createFrom().voidItem()
                .emitOn(QUERY_THREADS)
                .map(unused -> {
                    PokerWithdrawalRecord record = context.selectFrom(POKER_WITHDRAWAL)
                            .where(POKER_WITHDRAWAL.WITHDRAWAL_ID.eq(withdrawalId))
                            .fetchOne();
                    if (record != null) {
                        return new Withdrawal(record);
                    } else {
                        throw new RuntimeException("Withdrawal not found");
                    }
                });
    }

    public Uni<Withdrawal> createWithdrawal(Integer userId, Withdrawal withdrawal) {
        return balanceService.fetchUserBalance(userId)
                .emitOn(QUERY_THREADS)
                .map(userBalance -> {
                    withdrawal.validate();
                    if (withdrawal.getAmount() > userBalance.getBalance()) {
                        throw new RuntimeException("Insufficient balance");
                    }
                    PokerWithdrawalRecord record = context.insertInto(POKER_WITHDRAWAL)
                            .set(POKER_WITHDRAWAL.AMOUNT, withdrawal.getAmount())
                            .set(POKER_WITHDRAWAL.USER_ID, userId)
                            .set(POKER_WITHDRAWAL.CREATE_DATE, OffsetDateTime.now())
                            .set(POKER_WITHDRAWAL.DETAILS, JSONB.valueOf(withdrawal.getDetails().encode()))
                            .returning()
                            .fetchOne();
                    if (record != null) {
                        try {
                            GlobalSocket socket = CDI.current().select(GlobalSocket.class).get();
                            socket.sendWithdrawalNotification(userId, withdrawal.getAmount());
                        } catch (Exception e) {
                            LOG.warn("Failed to send withdrawal notification: " + e.getMessage());
                        }
                        return new Withdrawal(record);
                    } else {
                        throw new RuntimeException("Failed to create withdrawal");
                    }
                })
                .call(unused -> balanceService.lockUserBalance(userId, withdrawal.getAmount()));
    }

    public Uni<Withdrawal> approveWithdrawal(Integer agentId, Long withdrawalId) {
        return fetchWithdrawalById(withdrawalId)
                .map(withdrawal -> {
                    if (withdrawal.getApprovedBy() != null) {
                        throw new RuntimeException("Withdrawal already approved");
                    }
                    if (withdrawal.getAmount() <= 0) {
                        throw new RuntimeException("Invalid withdrawal amount");
                    }
                    return context.update(POKER_WITHDRAWAL)
                            .set(POKER_WITHDRAWAL.APPROVED_BY, agentId)
                            .set(POKER_WITHDRAWAL.APPROVE_DATE, OffsetDateTime.now())
                            .where(POKER_WITHDRAWAL.WITHDRAWAL_ID.eq(withdrawalId))
                            .returning()
                            .fetchOneInto(Withdrawal.class);
                })
                .call(withdrawal -> balanceService.unlockUserBalance(withdrawal.getUserId(), withdrawal.getAmount(), false))
                .call(withdrawal -> balanceService.fetchUserBalance(withdrawal.getUserId())
                        .invoke(userBalance -> {
                            gameService.notifyBalanceUpdate(withdrawal.getUserId(), userBalance);
                            try {
                                GlobalSocket socket = CDI.current().select(GlobalSocket.class).get();
                                JsonObject withdrawalApproved = new JsonObject()
                                        .put("type", "WITHDRAWAL_APPROVED")
                                        .put("data", new JsonObject()
                                                .put("withdrawalId", withdrawal.getWithdrawalId())
                                                .put("amount", withdrawal.getAmount())
                                                .put("newBalance", userBalance.getBalance())
                                        );
                                GlobalSocket.sendToUser(withdrawal.getUserId(), withdrawalApproved.toString());

                                JsonObject adminUpdate = new JsonObject()
                                        .put("type", "DEPOSIT_STATUS_UPDATE")
                                        .put("data", new JsonObject()
                                                .put("withdrawalId", withdrawal.getWithdrawalId())
                                                .put("userId", withdrawal.getUserId())
                                                .put("status", "APPROVED")
                                                .put("amount", withdrawal.getAmount())
                                        );
                                socket.broadcastToAdmins(adminUpdate.toString());
                            } catch (Exception e) {
                                LOG.warn("Failed to send WITHDRAWAL_APPROVED notification: " + e.getMessage());
                            }
                        })
                );
    }

    // ── Outcomes ──────────────────────────────────────────────────────────────

    public Uni<List<Outcome>> fetchOutcomes(Integer userId) {
        return Uni.createFrom().voidItem()
                .emitOn(QUERY_THREADS)
                .map(unused -> {
                    List<Outcome> outcomes = new ArrayList<>();
                    SelectConditionStep<Record> selectStep = context.select()
                            .from(POKER_OUTCOME)
                            .leftJoin(POKER_USER).on(POKER_OUTCOME.USER_ID.eq(POKER_USER.USER_ID))
                            .where(POKER_USER.USER_ID.isNotNull());
                    if (userId != null) {
                        selectStep = selectStep.and(POKER_OUTCOME.USER_ID.eq(userId));
                    }
                    selectStep
                            .orderBy(POKER_OUTCOME.CREATE_DATE.desc())
                            .fetch().forEach(record -> {
                        PokerOutcomeRecord outcomeRecord = record.into(POKER_OUTCOME);
                        Outcome outcome = new Outcome(outcomeRecord);
                        User user = record.into(POKER_USER).into(User.class);
                        outcome.setUser(user);
                        outcomes.add(outcome);
                    });
                    return outcomes;
                });
    }

    public Uni<Void> createOutcomeRecord(Integer userId, int amount, String type) {
        return Uni.createFrom().voidItem()
                .emitOn(QUERY_THREADS)
                .invoke(unused -> context.insertInto(POKER_OUTCOME)
                        .set(POKER_OUTCOME.USER_ID, userId)
                        .set(POKER_OUTCOME.AMOUNT, amount)
                        .set(POKER_OUTCOME.TYPE, type)
                        .set(POKER_OUTCOME.IS_ACCOUNTED, false)
                        .set(POKER_OUTCOME.CREATE_DATE, OffsetDateTime.now())
                        .execute());
    }

    public Uni<Void> approveOutcomeRecords(Integer userId) {
        return Uni.createFrom().voidItem()
                .emitOn(QUERY_THREADS)
                .call(unused -> {
                    List<PokerOutcomeRecord> records = context.selectFrom(POKER_OUTCOME)
                            .where(POKER_OUTCOME.USER_ID.eq(userId))
                            .and(POKER_OUTCOME.IS_ACCOUNTED.eq(false))
                            .fetch();
                    int totalAmount = records.stream()
                            .mapToInt(PokerOutcomeRecord::getAmount)
                            .sum();
                    List<Long> outcomeIds = records.stream()
                            .map(PokerOutcomeRecord::getOutcomeId)
                            .toList();
                    return balanceService.incrementBalance(userId, totalAmount)
                            .invoke(() -> context.update(POKER_OUTCOME)
                                    .set(POKER_OUTCOME.IS_ACCOUNTED, true)
                                    .set(POKER_OUTCOME.ACCOUNT_DATE, OffsetDateTime.now())
                                    .where(POKER_OUTCOME.OUTCOME_ID.in(outcomeIds))
                                    .execute())
                            .invoke(userBalance -> gameService.notifyBalanceUpdate(userId, userBalance));
                });
    }
}
