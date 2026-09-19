package dev.manestack.service;

import dev.manestack.domain.user.User;
import dev.manestack.domain.user.UserBalance;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.jooq.DSLContext;
import org.jooq.Record;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static dev.manestack.jooq.generated.Tables.*;

@ApplicationScoped
public class BalanceService {
    private static final Logger LOG = Logger.getLogger(BalanceService.class);
    private final ExecutorService QUERY_THREADS = Executors.newFixedThreadPool(3);

    @Inject
    DSLContext context;

    @Inject
    GameService gameService;

    // ── User Fetch ────────────────────────────────────────────────────────────

    public Uni<User> fetchUser(Integer userId) {
        return Uni.createFrom().voidItem()
                .emitOn(QUERY_THREADS)
                .map(unused -> {
                    Record record = context.selectFrom(POKER_USER)
                            .where(POKER_USER.USER_ID.eq(userId))
                            .fetchOne();
                    if (record == null) return null;

                    User user = record.into(User.class);
                    user.setAvatar(record.get(POKER_USER.AVATAR));
                    user.setAvatarBorder(record.get(POKER_USER.AVATAR_BORDER));
                    return user;
                })
                .onItem().transform(user -> {
                    if (user != null) {
                        user.setPassword(null);
                    }
                    return user;
                });
    }

    // ── Balance Operations ────────────────────────────────────────────────────

    public Uni<UserBalance> fetchUserBalance(Integer userId) {
        return Uni.createFrom().voidItem()
                .emitOn(QUERY_THREADS)
                .map(unused -> context.selectFrom(POKER_USER_BALANCE)
                        .where(POKER_USER_BALANCE.USER_ID.eq(userId))
                        .fetchOneInto(UserBalance.class))
                .onItem().transform(userBalance -> {
                    if (userBalance != null) {
                        return userBalance;
                    } else {
                        UserBalance newUserBalance = new UserBalance();
                        newUserBalance.setUserId(userId);
                        newUserBalance.setBalance(0);
                        newUserBalance.setLockedAmount(0);
                        newUserBalance.setBonusBalance(0);
                        return newUserBalance;
                    }
                });
    }

    public Uni<UserBalance> incrementBalance(Integer userId, int amount) {
        return Uni.createFrom().voidItem()
                .emitOn(QUERY_THREADS)
                .map(unused -> {
                    // Atomic UPSERT: insert if not exists, otherwise update
                    context.insertInto(POKER_USER_BALANCE)
                            .set(POKER_USER_BALANCE.USER_ID, userId)
                            .set(POKER_USER_BALANCE.BALANCE, amount)
                            .set(POKER_USER_BALANCE.LOCKED_AMOUNT, 0)
                            .set(POKER_USER_BALANCE.BONUS_BALANCE, 0)
                            .onConflict(POKER_USER_BALANCE.USER_ID)
                            .doUpdate()
                            .set(POKER_USER_BALANCE.BALANCE, POKER_USER_BALANCE.BALANCE.add(amount))
                            .execute();

                    // Fetch the updated balance
                    var record = context.selectFrom(POKER_USER_BALANCE)
                            .where(POKER_USER_BALANCE.USER_ID.eq(userId))
                            .fetchOne();

                    UserBalance userBalance = new UserBalance();
                    userBalance.setUserId(userId);
                    userBalance.setBalance(record.getBalance());
                    userBalance.setLockedAmount(record.getLockedAmount());
                    userBalance.setBonusBalance(record.getBonusBalance());
                    return userBalance;
                });
    }

    public Uni<Void> lockUserBalance(Integer userId, int amount) {
        return Uni.createFrom().voidItem()
                .emitOn(QUERY_THREADS)
                .map(unused -> {
                    int updated = context.update(POKER_USER_BALANCE)
                            .set(POKER_USER_BALANCE.LOCKED_AMOUNT, POKER_USER_BALANCE.LOCKED_AMOUNT.add(amount))
                            .set(POKER_USER_BALANCE.BALANCE, POKER_USER_BALANCE.BALANCE.sub(amount))
                            .where(POKER_USER_BALANCE.USER_ID.eq(userId))
                            .and(POKER_USER_BALANCE.BALANCE.greaterOrEqual(amount))
                            .execute();

                    if (updated == 0) {
                        throw new RuntimeException("Insufficient balance");
                    }
                    return null;
                });
    }

    public Uni<Void> unlockUserBalance(Integer userId, int amount, boolean isCancelled) {
        return Uni.createFrom().voidItem()
                .emitOn(QUERY_THREADS)
                .map(unused -> {
                    if (isCancelled) {
                        int updated = context.update(POKER_USER_BALANCE)
                                .set(POKER_USER_BALANCE.LOCKED_AMOUNT, POKER_USER_BALANCE.LOCKED_AMOUNT.sub(amount))
                                .set(POKER_USER_BALANCE.BALANCE, POKER_USER_BALANCE.BALANCE.add(amount))
                                .where(POKER_USER_BALANCE.USER_ID.eq(userId))
                                .and(POKER_USER_BALANCE.LOCKED_AMOUNT.greaterOrEqual(amount))
                                .execute();

                        if (updated == 0) {
                            throw new RuntimeException("Insufficient locked amount");
                        }
                    } else {
                        int updated = context.update(POKER_USER_BALANCE)
                                .set(POKER_USER_BALANCE.LOCKED_AMOUNT, POKER_USER_BALANCE.LOCKED_AMOUNT.sub(amount))
                                .where(POKER_USER_BALANCE.USER_ID.eq(userId))
                                .and(POKER_USER_BALANCE.LOCKED_AMOUNT.greaterOrEqual(amount))
                                .execute();

                        if (updated == 0) {
                            throw new RuntimeException("Insufficient locked amount");
                        }
                    }
                    return null;
                });
    }

    public Uni<UserBalance> updateUserBalance(Integer userId, int newBalance, int newBonus) {
        return Uni.createFrom().voidItem()
                .emitOn(QUERY_THREADS)
                .map(unused -> {
                    context.insertInto(POKER_USER_BALANCE)
                            .set(POKER_USER_BALANCE.USER_ID, userId)
                            .set(POKER_USER_BALANCE.BALANCE, newBalance)
                            .set(POKER_USER_BALANCE.BONUS_BALANCE, newBonus)
                            .set(POKER_USER_BALANCE.LOCKED_AMOUNT, 0)
                            .onConflict(POKER_USER_BALANCE.USER_ID)
                            .doUpdate()
                            .set(POKER_USER_BALANCE.BALANCE, newBalance)
                            .set(POKER_USER_BALANCE.BONUS_BALANCE, newBonus)
                            .execute();

                    var record = context.selectFrom(POKER_USER_BALANCE)
                            .where(POKER_USER_BALANCE.USER_ID.eq(userId))
                            .fetchOne();

                    UserBalance userBalance = new UserBalance();
                    userBalance.setUserId(userId);
                    userBalance.setBalance(record.getBalance());
                    userBalance.setBonusBalance(record.getBonusBalance());
                    userBalance.setLockedAmount(record.getLockedAmount());
                    return userBalance;
                });
    }
}
