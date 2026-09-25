package dev.manestack.service;

import at.favre.lib.crypto.bcrypt.BCrypt;
import dev.manestack.api.ws.GlobalSocket;
import dev.manestack.jooq.generated.tables.records.PokerDepositRecord;
import dev.manestack.jooq.generated.tables.records.PokerOutcomeRecord;
import dev.manestack.jooq.generated.tables.records.PokerWithdrawalRecord;
import dev.manestack.service.poker.table.GameSession;
import dev.manestack.domain.user.*;
import io.smallrye.jwt.build.Jwt;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;
import org.jooq.SelectConditionStep;
import org.jooq.exception.IntegrityConstraintViolationException;
import org.jooq.impl.DSL;
import org.postgresql.util.PSQLException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import io.vertx.core.json.JsonObject;


import static dev.manestack.jooq.generated.Tables.*;

@ApplicationScoped
public class UserService {
    private static final Logger LOG = Logger.getLogger(GameSession.class);
    private final ExecutorService QUERY_THREADS = Executors.newFixedThreadPool(3);

    @Inject
    DSLContext context;
    @Inject
    GameService gameService;

    @Inject
    DSLContext dslContext;

    @Inject
    DSLContext dsl;

    @ConfigProperty(name = "dev.manestack.jwt.issuer", defaultValue = "https://manestack.dev")
    String jwtIssuer;

    private String generateJWT(Integer userId, String role) {
        return Jwt.issuer(jwtIssuer)
                .upn(String.valueOf(userId))
                .groups(role)
                .claim("role", role)
                .expiresIn(Duration.ofHours(8))
                .subject(String.valueOf(userId))
                .sign();
    }

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

    public String fetchUserRoleSync(Integer userId) {
        Record record = context.selectFrom(POKER_USER)
                .where(POKER_USER.USER_ID.eq(userId))
                .fetchOne();
        if (record == null) return "USER";
        String role = record.get(POKER_USER.ROLE);
        return role != null ? role : "USER";
    }

    public Uni<String> fetchUserRole(Integer userId) {
        return Uni.createFrom().voidItem()
                .emitOn(QUERY_THREADS)
                .map(unused -> {
                    Record record = context.selectFrom(POKER_USER)
                            .where(POKER_USER.USER_ID.eq(userId))
                            .fetchOne();
                    if (record == null) return "USER";
                    String role = record.get(POKER_USER.ROLE);
                    return role != null ? role : "USER";
                });
    }

    public Uni<User> updateAvatar(int userId, String avatar, String avatarBorder) {
        return Uni.createFrom().voidItem()
                .emitOn(QUERY_THREADS)
                .map(unused -> {
                    context.update(POKER_USER)
                            .set(POKER_USER.AVATAR, avatar)
                            .set(POKER_USER.AVATAR_BORDER, avatarBorder)
                            .where(POKER_USER.USER_ID.eq(userId))
                            .execute();

                    return context.selectFrom(POKER_USER)
                            .where(POKER_USER.USER_ID.eq(userId))
                            .fetchOne()
                            .into(User.class);
                });
    }       

    public Uni<List<User>> searchUsers(String username, boolean isAdmin) {
        return Uni.createFrom().voidItem()
                .emitOn(QUERY_THREADS)
                .map(unused -> {
                    List<User> userList = new ArrayList<>();
                    context.select()
                            .from(POKER_USER)
                            .leftJoin(POKER_USER_BALANCE)
                            .on(POKER_USER.USER_ID.eq(POKER_USER_BALANCE.USER_ID))
                            .where(POKER_USER.USERNAME.likeIgnoreCase("%" + username + "%"))
                            .fetch()
                            .forEach(record -> {
                                User user = new User();

                                user.setUserId(record.get(POKER_USER.USER_ID));
                                user.setEmail(record.get(POKER_USER.EMAIL));
                                user.setUsername(record.get(POKER_USER.USERNAME));
                                user.setProfileUrl(record.get(POKER_USER.PROFILE_URL));
                                user.setRole(User.Role.valueOf(record.get(POKER_USER.ROLE)));
                                user.setCgpScore(record.get(POKER_USER.CGP_SCORE));
                                user.setBankName(isAdmin ? record.get(POKER_USER.BANK_NAME) : null);
                                user.setAccountNumber(isAdmin ? record.get(POKER_USER.ACCOUNT_NUMBER) : null);
                                user.setPassword(null); // Never expose password hashes

                                UserBalance balance = new UserBalance();
                                balance.setUserId(record.get(POKER_USER_BALANCE.USER_ID));
                                balance.setBalance(record.get(POKER_USER_BALANCE.BALANCE));
                                balance.setLockedAmount(record.get(POKER_USER_BALANCE.LOCKED_AMOUNT));
                                balance.setUserId(record.get(POKER_USER_BALANCE.USER_ID));

                                user.setUserBalance(balance);

                                userList.add(user);
                            });
                    return userList;
                });
    }

    public Uni<String> registerUser(User user) {
        return Uni.createFrom().voidItem()
                .emitOn(QUERY_THREADS)
                .invoke(user::validateRegister)
                .map(unused -> {
                    try {
                        String hashedPassword = BCrypt.withDefaults().hashToString(12, user.getPassword().toCharArray());
                        Integer userId = context.insertInto(POKER_USER)
                                .set(POKER_USER.EMAIL, user.getEmail())
                                .set(POKER_USER.USERNAME, user.getUsername())
                                .set(POKER_USER.PASSWORD, hashedPassword)
                                .set(POKER_USER.BANK_NAME, user.getBankName())
                                .set(POKER_USER.ACCOUNT_NUMBER, user.getAccountNumber())
                                .set(POKER_USER.ROLE, User.Role.USER.name())
                                .returning(POKER_USER.USER_ID)
                                .fetchOne(POKER_USER.USER_ID);
                        return generateJWT(userId, User.Role.USER.name());
                    } catch (IntegrityConstraintViolationException integrityException) {
                        if (integrityException.getCause() instanceof PSQLException psqlException) {
                            if (psqlException.getServerErrorMessage() != null &&
                                    psqlException.getServerErrorMessage().getConstraint() != null) {
                                String constraint = psqlException.getServerErrorMessage().getConstraint();
                                switch (constraint) {
                                    case "unique_email": {
                                        throw new RuntimeException("Email already exists");
                                    }
                                    case "unique_username": {
                                        throw new RuntimeException("Username already exists");
                                    }
                                    default: {
                                        throw new RuntimeException("Failed to create user");
                                    }
                                }
                            }
                        }
                        throw new RuntimeException("Failed to create user");
                    }
                });
    }

    public Uni<User> updateUser(Integer userId, User user) {
        return Uni.createFrom().voidItem()
                .emitOn(QUERY_THREADS)
                .map(unused -> context.update(POKER_USER)
                        .set(POKER_USER.PROFILE_URL, user.getProfileUrl())
                        .set(POKER_USER.BANK_NAME, user.getBankName())
                        .set(POKER_USER.ACCOUNT_NUMBER, user.getAccountNumber())
                        .where(POKER_USER.USER_ID.eq(userId))
                        .returning()
                        .fetchOneInto(User.class));
    }

    public Uni<String> loginUser(String username, String password) {
        return Uni.createFrom().voidItem()
                .emitOn(QUERY_THREADS)
                .map(unused -> {
                    String normalizedUsername = username.trim().toLowerCase();
                    LOG.infov("LOGIN ATTEMPT: username={0}", normalizedUsername);

                    User user = context.selectFrom(POKER_USER)
                            .where(POKER_USER.USERNAME.eq(normalizedUsername))
                            .fetchOneInto(User.class);

                    if (user == null) {
                        LOG.errorv("LOGIN FAILED: User {0} not found in database", normalizedUsername);
                        throw new jakarta.ws.rs.NotAuthorizedException("Invalid username or password", (Object[]) null);
                    }

                    LOG.infov("LOGIN: User found: userId={0}, role={1}", user.getUserId(), user.getRole());

                    String storedHash = user.getPassword();
                    if (storedHash == null || storedHash.isBlank()) {
                        LOG.errorv("LOGIN FAILED: User {0} has no password set", normalizedUsername);
                        throw new jakarta.ws.rs.NotAuthorizedException("Invalid username or password", (Object[]) null);
                    }

                    LOG.infov("LOGIN: Stored hash prefix={0}, length={1}", storedHash.substring(0, Math.min(10, storedHash.length())), storedHash.length());

                    // Check if stored password is a valid BCrypt hash
                    if (!storedHash.startsWith("$2a$") && !storedHash.startsWith("$2b$") && !storedHash.startsWith("$2y$")) {
                        LOG.errorv("LOGIN FAILED: User {0} has invalid password hash format: {1}", normalizedUsername, storedHash.substring(0, Math.min(10, storedHash.length())));
                        throw new jakarta.ws.rs.NotAuthorizedException("Invalid username or password", (Object[]) null);
                    }

                    BCrypt.Result result = BCrypt.verifyer().verify(password.toCharArray(), storedHash);
                    LOG.infov("LOGIN: BCrypt verification result: verified={0}", result.verified);

                    if (!result.verified) {
                        LOG.errorv("LOGIN FAILED: Password verification failed for user {0}", normalizedUsername);
                        throw new jakarta.ws.rs.NotAuthorizedException("Invalid username or password", (Object[]) null);
                    }

                    String jwt = generateJWT(user.getUserId(), user.getRole().name());
                    LOG.infov("LOGIN SUCCESS: User {0} (userId={1}) logged in successfully, JWT generated", normalizedUsername, user.getUserId());
                    return jwt;
                });
    }

    public Uni<Void> deleteUser(Integer userId, Integer adminId) {
        return Uni.createFrom().voidItem()
                .emitOn(QUERY_THREADS)
                .invoke(() -> {
                    User adminUser = context.selectFrom(POKER_USER)
                            .where(POKER_USER.USER_ID.eq(adminId))
                            .fetchOneInto(User.class);
                    if (adminUser == null || !adminUser.getRole().equals(User.Role.ADMIN)) {
                        throw new RuntimeException("Only admin can delete users");
                    }

                    LOG.infov("Deleting user with ID: {0} by admin {1}", userId, adminId);

                    context.transaction(configuration -> {
                        DSLContext ctx = DSL.using(configuration);

                        ctx.deleteFrom(POKER_DEPOSIT)
                        .where(POKER_DEPOSIT.USER_ID.eq(userId)
                            .or(POKER_DEPOSIT.ADMIN_ID.eq(userId)))
                        .execute();

                        ctx.deleteFrom(POKER_WITHDRAWAL)
                        .where(POKER_WITHDRAWAL.USER_ID.eq(userId))
                        .execute();

                        ctx.deleteFrom(POKER_OUTCOME)
                        .where(POKER_OUTCOME.USER_ID.eq(userId))
                        .execute();

                        ctx.deleteFrom(POKER_USER_BALANCE)
                        .where(POKER_USER_BALANCE.USER_ID.eq(userId))
                        .execute();

                        ctx.deleteFrom(POKER_USER)
                        .where(POKER_USER.USER_ID.eq(userId))
                        .execute();
                    });
                });
    }

    /*
     * Details Methods
     */
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

    public Uni<Withdrawal> createWithdrawal(Integer userId, Withdrawal withdrawal) {
        return fetchUserBalance(userId)
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
                        // Notify admins about the new withdrawal request
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
                .call(unused -> lockUserBalance(userId, withdrawal.getAmount()));
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
                .call(withdrawal -> unlockUserBalance(withdrawal.getUserId(), withdrawal.getAmount(), false))
                .call(withdrawal -> fetchUserBalance(withdrawal.getUserId())
                        .invoke(userBalance -> gameService.notifyBalanceUpdate(withdrawal.getUserId(), userBalance))
                );
    }

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


    private Uni<Void> lockUserBalance(Integer userId, int amount) {
        return Uni.createFrom().voidItem()
                .emitOn(QUERY_THREADS)
                .map(unused -> {
                    // Atomic UPDATE with conditional check - no race condition
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

    private Uni<Void> unlockUserBalance(Integer userId, int amount, boolean isCancelled) {
        return Uni.createFrom().voidItem()
                .emitOn(QUERY_THREADS)
                .map(unused -> {
                    if (isCancelled) {
                        // Return locked amount back to balance
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
                        // Just reduce locked amount (money leaving the system)
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
                    return incrementBalance(userId, totalAmount)
                            .invoke(() -> context.update(POKER_OUTCOME)
                                    .set(POKER_OUTCOME.IS_ACCOUNTED, true)
                                    .set(POKER_OUTCOME.ACCOUNT_DATE, OffsetDateTime.now())
                                    .where(POKER_OUTCOME.OUTCOME_ID.in(outcomeIds))
                                    .execute())
                            .invoke(userBalance -> gameService.notifyBalanceUpdate(userId, userBalance));
                });
    }

    public Uni<UserBalance> incrementBalance(Integer userId, int amount) {
        return Uni.createFrom().voidItem()
                .emitOn(QUERY_THREADS)
                .map(unused -> {
                    // Atomic UPSERT: insert if not exists, otherwise update
                    // This eliminates the race condition between concurrent calls
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

    public Uni<List<DataBlock>> fetchDataBlocks() {
        return Uni.createFrom().voidItem()
                .emitOn(QUERY_THREADS)
                .map(unused -> {
                    List<DataBlock> dataBlocks = context.selectFrom(POKER_DATA_BLOCK)
                            .fetchInto(DataBlock.class);
                    return dataBlocks;
                });
    }

    public Uni<DataBlock> fetchDataBlockByName(String name) {
        return Uni.createFrom().voidItem()
                .emitOn(QUERY_THREADS)
                .map(unused -> {
                    return context.selectFrom(POKER_DATA_BLOCK)
                            .where(POKER_DATA_BLOCK.NAME.eq(name))
                            .fetchOneInto(DataBlock.class);
                });
    }

    public Uni<DataBlock> createDataBlock(DataBlock dataBlock) {
        return Uni.createFrom().voidItem()
                .emitOn(QUERY_THREADS)
                .map(unused -> context.insertInto(POKER_DATA_BLOCK)
                        .set(POKER_DATA_BLOCK.NAME, dataBlock.getName())
                        .set(POKER_DATA_BLOCK.VALUE, dataBlock.getValue())
                        .returning()
                        .fetchOneInto(DataBlock.class))
                .onFailure().invoke(throwable -> LOG.errorv(throwable, "Failed to create data block"));
    }

    public Uni<DataBlock> updateDataBlock(String name, DataBlock dataBlock) {
        return Uni.createFrom().voidItem()
                .emitOn(QUERY_THREADS)
                .map(unused -> {
                    return context.update(POKER_DATA_BLOCK)
                            .set(POKER_DATA_BLOCK.VALUE, dataBlock.getValue())
                            .where(POKER_DATA_BLOCK.NAME.eq(name))
                            .returning()
                            .fetchOneInto(DataBlock.class);
                });
    }

    public Uni<DataBlock> deleteDataBlock(String name) {
        return Uni.createFrom().voidItem()
            .emitOn(QUERY_THREADS)
            .map(unused -> {
                return context.deleteFrom(POKER_DATA_BLOCK)
                    .where(POKER_DATA_BLOCK.NAME.eq(name))
                    .returning()
                    .fetchOneInto(DataBlock.class);
            });
    }

    public Uni<Void> unlockBalance(Integer userId, int stackAmount) {
        return Uni.createFrom().voidItem()
            .onItem().invoke(() -> {
                int currentBalance = dslContext.select(POKER_USER_BALANCE.BALANCE)
                    .from(POKER_USER_BALANCE)
                    .where(POKER_USER_BALANCE.USER_ID.eq(userId))
                    .fetchOne(POKER_USER_BALANCE.BALANCE);

                int newBalance = currentBalance + stackAmount;

                int updatedRows = dslContext.update(POKER_USER_BALANCE)
                    .set(POKER_USER_BALANCE.BALANCE, newBalance)
                    .where(POKER_USER_BALANCE.USER_ID.eq(userId))
                    .execute();

                if (updatedRows > 0) {
                    LOG.infov("Successfully unlocked balance for user {0}. New balance: {1}", userId, newBalance);
                } else {
                    throw new IllegalStateException("Failed to unlock balance for user " + userId);
                }
            });
    }
    
    public Uni<Void> updateUserRole(Integer userId, String role) {
        return Uni.createFrom().item(() -> {
            var record = dsl.selectFrom(POKER_USER)
                    .where(POKER_USER.USER_ID.eq(userId))
                    .fetchOne();

            if (record == null) {
                throw new WebApplicationException("User not found", 404);
            }

            record.setRole(role.toUpperCase());
            record.store();

            return null;
        });
    }

    public Uni<BigDecimal> getCurrentJackpot() {
        return Uni.createFrom().item(new java.util.function.Supplier<BigDecimal>() {
            @Override
            public BigDecimal get() {
                ensureJackpotRow();
                return dsl.select(POKER_JACKPOT.CURRENT_AMOUNT)
                        .from(POKER_JACKPOT)
                        .fetchAnyInto(BigDecimal.class);
            }
        });
    }

    public void contributeToJackpot(BigDecimal contribution) {
        ensureJackpotRow();

        dsl.update(POKER_JACKPOT)
        .set(POKER_JACKPOT.CURRENT_AMOUNT, POKER_JACKPOT.CURRENT_AMOUNT.plus(contribution))
        .set(POKER_JACKPOT.LAST_UPDATED, LocalDateTime.now())
        .execute();

        BigDecimal currentAmount = dsl.select(POKER_JACKPOT.CURRENT_AMOUNT)
                                    .from(POKER_JACKPOT)
                                    .fetchOneInto(BigDecimal.class);


        JsonObject payload = new JsonObject()
                .put("type", "GAME")
                .put("action", "JACKPOT_UPDATE")
                .put("amount", currentAmount);

        GlobalSocket.broadcast(payload.encode());
    }

    private void ensureJackpotRow() {
        Integer count = dsl.selectCount()
                .from(POKER_JACKPOT)
                .fetchOneInto(Integer.class);
        if (count == null || count == 0) {
            dsl.insertInto(POKER_JACKPOT)
                .set(POKER_JACKPOT.AMOUNT, 0L)
                .set(POKER_JACKPOT.CURRENT_AMOUNT, BigDecimal.ZERO)
                .set(POKER_JACKPOT.SEED_AMOUNT, new BigDecimal("1000.00"))
                .execute();
        }
    }

    public Uni<UserBalance> triggerJackpotWin(Integer userId, BigDecimal percentage) {
        return getCurrentJackpot()
            .onItem().transformToUni(jackpotAmount -> {
                BigDecimal prize = jackpotAmount.multiply(percentage).setScale(2, java.math.RoundingMode.FLOOR);
                return incrementBalance(userId, prize.intValue())
                    .onItem().invoke(userBalance -> {
                        BigDecimal seed = dsl.select(POKER_JACKPOT.SEED_AMOUNT)
                                            .from(POKER_JACKPOT)
                                            .fetchOneInto(BigDecimal.class);

                        dsl.update(POKER_JACKPOT)
                        .set(POKER_JACKPOT.CURRENT_AMOUNT, seed)
                        .set(POKER_JACKPOT.LAST_UPDATED, LocalDateTime.now())
                        .execute();

                        gameService.notifyJackpotWin(userId, prize);
                    });
            });
    }

    public Uni<UserBalance> claimDailyBonus(Integer userId, int bonusAmount) {
        return fetchUser(userId)
            .flatMap(user -> {
                if (user == null) {
                    return Uni.createFrom().failure(new RuntimeException("User not found"));
                }

                LocalDateTime now = LocalDateTime.now();
                LocalDateTime lastClaim = user.getLastDailyBonus();

                if (lastClaim != null && lastClaim.isAfter(now.minusHours(24))) {
                    return Uni.createFrom().failure(new RuntimeException("Daily bonus already claimed. Come back later!"));
                }

                return incrementBalance(userId, bonusAmount)
                    .flatMap(userBalance -> 
                        Uni.createFrom().voidItem()
                            .emitOn(QUERY_THREADS) // run DB update on the same thread pool
                            .invoke(() -> dsl.update(POKER_USER)
                                .set(POKER_USER.LAST_DAILY_BONUS, now)
                                .where(POKER_USER.USER_ID.eq(userId))
                                .execute()
                            )
                            .map(unused -> {
                                gameService.notifyBalanceUpdate(userId, userBalance);
                                return userBalance;
                            })
                    );
            });
    }

    public Uni<UserBalance> updateUserBalance(Integer userId, int newBalance, int newBonus) {
        return Uni.createFrom().voidItem()
                .emitOn(QUERY_THREADS)
                .map(unused -> {
                    // Atomic UPSERT: insert if not exists, otherwise update
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

                    // Fetch the updated record
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


    public Uni<Deposit> createDepositRequest(Integer userId, Deposit deposit) {
        return fetchUser(userId)
            .emitOn(QUERY_THREADS)
            .chain(user -> {
                deposit.validate();

                try {
                    // Try to use STATUS field, if it doesn't exist, this will work but log warning
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
                        
                        // Try to set status using reflection
                        try {
                            context.update(POKER_DEPOSIT)
                                .set(DSL.field("status", String.class), Deposit.DepositStatus.PENDING.name())
                                .where(POKER_DEPOSIT.DEPOSIT_ID.eq(record.getDepositId()))
                                .execute();
                        } catch (Exception e) {
                            LOG.warnv("STATUS field not found, skipping. Please run database migration: {0}", e.getMessage());
                        }
                    } catch (Exception e) {
                        LOG.error("Error creating deposit request", e);
                        return Uni.createFrom().failure(new RuntimeException("Failed to create deposit request"));
                    }

                    if (record != null) {
                        Deposit newDeposit = new Deposit(record);
                        
                        // Notify admins about new deposit request
                        GlobalSocket socket = CDI.current().select(GlobalSocket.class).get();
                        socket.sendDepositNotification(userId, deposit.getAmount(), deposit.getType().name());
                        
                        LOG.infov("User {0} created deposit request for amount {1}", userId, deposit.getAmount());
                        
                        return Uni.createFrom().item(newDeposit);
                    } else {
                        return Uni.createFrom().failure(new RuntimeException("Failed to create deposit request"));
                    }

                } catch (IntegrityConstraintViolationException e) {
                    return Uni.createFrom().failure(new RuntimeException("Failed to create deposit request"));
                }
            });
    }

    public Uni<Deposit> approveDeposit(Integer adminId, Long depositId) {
        return Uni.createFrom().voidItem()
                .emitOn(QUERY_THREADS)
                .chain(unused -> {
                    // Fetch the deposit
                    PokerDepositRecord depositRecord = context.selectFrom(POKER_DEPOSIT)
                            .where(POKER_DEPOSIT.DEPOSIT_ID.eq(depositId))
                            .fetchOne();

                    if (depositRecord == null) {
                        return Uni.createFrom().failure(new RuntimeException("Deposit not found"));
                    }

                    Deposit deposit = new Deposit(depositRecord);

                    // Check if already processed
                    if (deposit.getStatus() != Deposit.DepositStatus.PENDING) {
                        return Uni.createFrom().failure(new RuntimeException("Deposit already processed"));
                    }

                    // Update deposit status to APPROVED using raw SQL for compatibility
                    try {
                        context.update(POKER_DEPOSIT)
                                .set(DSL.field("status", String.class), Deposit.DepositStatus.APPROVED.name())
                                .set(POKER_DEPOSIT.ADMIN_ID, adminId)
                                .set(DSL.field("approved_date", OffsetDateTime.class), OffsetDateTime.now())
                                .where(POKER_DEPOSIT.DEPOSIT_ID.eq(depositId))
                                .execute();
                    } catch (Exception e) {
                        LOG.error("Error updating deposit status", e);
                        return Uni.createFrom().failure(new RuntimeException("Failed to approve deposit. Please ensure database migration is applied."));
                    }

                    // Fetch updated record
                    PokerDepositRecord updatedRecord = context.selectFrom(POKER_DEPOSIT)
                            .where(POKER_DEPOSIT.DEPOSIT_ID.eq(depositId))
                            .fetchOne();

                    if (updatedRecord == null) {
                        return Uni.createFrom().failure(new RuntimeException("Failed to approve deposit"));
                    }

                    // Increment user balance
                    return incrementBalance(deposit.getUserId(), deposit.getAmount())
                            .invoke(userBalance -> {
                                // Notify user about balance update
                                gameService.notifyBalanceUpdate(deposit.getUserId(), userBalance);
                                
                                JsonObject notification = new JsonObject()
                                        .put("type", "DEPOSIT_APPROVED")
                                        .put("data", new JsonObject()
                                                .put("depositId", depositId)
                                                .put("amount", deposit.getAmount())
                                                .put("newBalance", userBalance.getBalance())
                                        );
                                GlobalSocket.sendToUser(deposit.getUserId(), notification.encode());
                                
                                LOG.infov("Admin {0} approved deposit {1} for user {2}, amount: {3}", 
                                        adminId, depositId, deposit.getUserId(), deposit.getAmount());
                            })
                            .map(userBalance -> new Deposit(updatedRecord));
                });
    }

    public Uni<Deposit> denyDeposit(Integer adminId, Long depositId, String deniedReason) {
        return Uni.createFrom().voidItem()
                .emitOn(QUERY_THREADS)
                .map(unused -> {
                    // Fetch the deposit
                    PokerDepositRecord depositRecord = context.selectFrom(POKER_DEPOSIT)
                            .where(POKER_DEPOSIT.DEPOSIT_ID.eq(depositId))
                            .fetchOne();

                    if (depositRecord == null) {
                        throw new RuntimeException("Deposit not found");
                    }

                    Deposit deposit = new Deposit(depositRecord);

                    // Check if already processed
                    if (deposit.getStatus() != Deposit.DepositStatus.PENDING) {
                        throw new RuntimeException("Deposit already processed");
                    }

                    // Update deposit status to DENIED using raw SQL for compatibility
                    try {
                        context.update(POKER_DEPOSIT)
                                .set(DSL.field("status", String.class), Deposit.DepositStatus.DENIED.name())
                                .set(POKER_DEPOSIT.ADMIN_ID, adminId)
                                .set(DSL.field("denied_reason", String.class), deniedReason != null ? deniedReason : "Denied by admin")
                                .where(POKER_DEPOSIT.DEPOSIT_ID.eq(depositId))
                                .execute();
                    } catch (Exception e) {
                        LOG.error("Error updating deposit status", e);
                        throw new RuntimeException("Failed to deny deposit. Please ensure database migration is applied.");
                    }

                    // Fetch updated record
                    PokerDepositRecord updatedRecord = context.selectFrom(POKER_DEPOSIT)
                            .where(POKER_DEPOSIT.DEPOSIT_ID.eq(depositId))
                            .fetchOne();

                    if (updatedRecord == null) {
                        throw new RuntimeException("Failed to deny deposit");
                    }

                    JsonObject notification = new JsonObject()
                            .put("type", "DEPOSIT_DENIED")
                            .put("data", new JsonObject()
                                    .put("depositId", depositId)
                                    .put("amount", deposit.getAmount())
                                    .put("reason", deniedReason != null ? deniedReason : "Denied by admin")
                            );
                    GlobalSocket.sendToUser(deposit.getUserId(), notification.encode());
                    
                    LOG.infov("Admin {0} denied deposit {1} for user {2}. Reason: {3}", 
                            adminId, depositId, deposit.getUserId(), deniedReason);

                    return new Deposit(updatedRecord);
                });
    }

    public void addBonus(Integer userId, int amount, String reason, String sessionId) {
        if (amount <= 0 || userId == null || sessionId == null || reason == null) {
            LOG.warn("Invalid bonus parameters: userId=" + userId + ", amount=" + amount);
            return;
        }

        int updated = context.update(POKER_USER_BALANCE)
                .set(POKER_USER_BALANCE.BONUS_BALANCE,
                     POKER_USER_BALANCE.BONUS_BALANCE.plus(amount))
                .set(POKER_USER_BALANCE.LAST_BONUS_SESSION_ID, sessionId)
                .where(POKER_USER_BALANCE.USER_ID.eq(userId))
                .and(POKER_USER_BALANCE.LAST_BONUS_SESSION_ID.isDistinctFrom(sessionId))
                .execute();

        if (updated > 0) {
            LOG.infov(
                "Bonus added: userId={0}, amount={1}, reason={2}, session={3}",
                userId, amount, reason, sessionId
            );
        } else {
            LOG.infov(
                "Bonus skipped (already granted for this session): userId={0}, session={1}",
                userId, sessionId
            );
        }
    }

    public void updateCgp(Integer userId, int cgpScore) {
        context.update(POKER_USER)
            .set(POKER_USER.CGP_SCORE, cgpScore)
            .where(POKER_USER.USER_ID.eq(userId))
            .execute();
    }
}
