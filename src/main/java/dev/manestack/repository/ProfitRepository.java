package dev.manestack.repository;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jooq.DSLContext;
import org.jooq.Field;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;

import static dev.manestack.jooq.generated.Tables.POKER_DEPOSIT;
import static dev.manestack.jooq.generated.Tables.POKER_HAND_PLAYER_RESULT;
import static dev.manestack.jooq.generated.Tables.POKER_HAND_SETTLEMENT;
import static dev.manestack.jooq.generated.Tables.POKER_WITHDRAWAL;
import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.sum;

@ApplicationScoped
public class ProfitRepository {

    @Inject
    DSLContext ctx;

    public long rakeBetween(OffsetDateTime from, OffsetDateTime to) {
        Long value = ctx.select(coalesce(sum(POKER_HAND_SETTLEMENT.RAKE_AMOUNT), 0L))
                .from(POKER_HAND_SETTLEMENT)
                .where(POKER_HAND_SETTLEMENT.CREATED_AT.ge(from)
                        .and(POKER_HAND_SETTLEMENT.CREATED_AT.lt(to)))
                .fetchOneInto(Long.class);
        return value == null ? 0 : value;
    }

    // Bot net from hands where at least one real player participated
    // This excludes bot-vs-bot games
    public long botNetFromRealPlayers(OffsetDateTime from, OffsetDateTime to) {
        // Subquery: find (table_id, hand_number) pairs that have at least one real player
        var handsWithRealPlayers = select(
                POKER_HAND_PLAYER_RESULT.TABLE_ID,
                POKER_HAND_PLAYER_RESULT.HAND_NUMBER)
                .from(POKER_HAND_PLAYER_RESULT)
                .where(POKER_HAND_PLAYER_RESULT.IS_BOT.eq(false))
                .and(POKER_HAND_PLAYER_RESULT.CREATED_AT.ge(from))
                .and(POKER_HAND_PLAYER_RESULT.CREATED_AT.lt(to))
                .groupBy(POKER_HAND_PLAYER_RESULT.TABLE_ID, POKER_HAND_PLAYER_RESULT.HAND_NUMBER);

        // Sum bot net results only from those hands
        Long value = ctx.select(coalesce(sum(POKER_HAND_PLAYER_RESULT.NET_RESULT), 0L))
                .from(POKER_HAND_PLAYER_RESULT)
                .where(POKER_HAND_PLAYER_RESULT.IS_BOT.eq(true))
                .and(POKER_HAND_PLAYER_RESULT.CREATED_AT.ge(from))
                .and(POKER_HAND_PLAYER_RESULT.CREATED_AT.lt(to))
                .and(field("(table_id, hand_number)").in(handsWithRealPlayers))
                .fetchOneInto(Long.class);
        return value == null ? 0 : value;
    }

    public long depositsBetween(OffsetDateTime from, OffsetDateTime to) {
        Long value = ctx.select(coalesce(sum(POKER_DEPOSIT.AMOUNT), 0L))
                .from(POKER_DEPOSIT)
                .where(POKER_DEPOSIT.STATUS.eq("APPROVED")
                        .and(POKER_DEPOSIT.CREATE_DATE.ge(from))
                        .and(POKER_DEPOSIT.CREATE_DATE.lt(to)))
                .fetchOneInto(Long.class);
        return value == null ? 0 : value;
    }

    public long withdrawalsBetween(OffsetDateTime from, OffsetDateTime to) {
        Long value = ctx.select(coalesce(sum(POKER_WITHDRAWAL.AMOUNT), 0L))
                .from(POKER_WITHDRAWAL)
                .where(POKER_WITHDRAWAL.STATUS.eq("APPROVED")
                        .and(POKER_WITHDRAWAL.CREATE_DATE.ge(from))
                        .and(POKER_WITHDRAWAL.CREATE_DATE.lt(to)))
                .fetchOneInto(Long.class);
        return value == null ? 0 : value;
    }

    public Map<LocalDate, Long> rakeDaily(OffsetDateTime from, OffsetDateTime to) {
        Field<Long> rakeSum = sum(POKER_HAND_SETTLEMENT.RAKE_AMOUNT).coerce(Long.class);
        return ctx.select(day(POKER_HAND_SETTLEMENT.CREATED_AT), rakeSum)
                .from(POKER_HAND_SETTLEMENT)
                .where(POKER_HAND_SETTLEMENT.CREATED_AT.ge(from)
                        .and(POKER_HAND_SETTLEMENT.CREATED_AT.lt(to)))
                .groupBy(day(POKER_HAND_SETTLEMENT.CREATED_AT))
                .fetchMap(day(POKER_HAND_SETTLEMENT.CREATED_AT), rakeSum);
    }

    // Bot net daily from hands with real players only
    public Map<LocalDate, Long> botNetFromRealPlayersDaily(OffsetDateTime from, OffsetDateTime to) {
        // Subquery: hands with real players
        var handsWithRealPlayers = select(
                POKER_HAND_PLAYER_RESULT.TABLE_ID,
                POKER_HAND_PLAYER_RESULT.HAND_NUMBER)
                .from(POKER_HAND_PLAYER_RESULT)
                .where(POKER_HAND_PLAYER_RESULT.IS_BOT.eq(false))
                .and(POKER_HAND_PLAYER_RESULT.CREATED_AT.ge(from))
                .and(POKER_HAND_PLAYER_RESULT.CREATED_AT.lt(to))
                .groupBy(POKER_HAND_PLAYER_RESULT.TABLE_ID, POKER_HAND_PLAYER_RESULT.HAND_NUMBER);

        Field<Long> netSum = sum(POKER_HAND_PLAYER_RESULT.NET_RESULT).coerce(Long.class);
        return ctx.select(day(POKER_HAND_PLAYER_RESULT.CREATED_AT), netSum)
                .from(POKER_HAND_PLAYER_RESULT)
                .where(POKER_HAND_PLAYER_RESULT.IS_BOT.eq(true))
                .and(POKER_HAND_PLAYER_RESULT.CREATED_AT.ge(from))
                .and(POKER_HAND_PLAYER_RESULT.CREATED_AT.lt(to))
                .and(field("(table_id, hand_number)").in(handsWithRealPlayers))
                .groupBy(day(POKER_HAND_PLAYER_RESULT.CREATED_AT))
                .fetchMap(day(POKER_HAND_PLAYER_RESULT.CREATED_AT), netSum);
    }

    public Map<LocalDate, Long> depositsDaily(OffsetDateTime from, OffsetDateTime to) {
        Field<Long> amountSum = sum(POKER_DEPOSIT.AMOUNT).coerce(Long.class);
        return ctx.select(day(POKER_DEPOSIT.CREATE_DATE), amountSum)
                .from(POKER_DEPOSIT)
                .where(POKER_DEPOSIT.STATUS.eq("APPROVED")
                        .and(POKER_DEPOSIT.CREATE_DATE.ge(from))
                        .and(POKER_DEPOSIT.CREATE_DATE.lt(to)))
                .groupBy(day(POKER_DEPOSIT.CREATE_DATE))
                .fetchMap(day(POKER_DEPOSIT.CREATE_DATE), amountSum);
    }

    public Map<LocalDate, Long> withdrawalsDaily(OffsetDateTime from, OffsetDateTime to) {
        Field<Long> amountSum = sum(POKER_WITHDRAWAL.AMOUNT).coerce(Long.class);
        return ctx.select(day(POKER_WITHDRAWAL.CREATE_DATE), amountSum)
                .from(POKER_WITHDRAWAL)
                .where(POKER_WITHDRAWAL.STATUS.eq("APPROVED")
                        .and(POKER_WITHDRAWAL.CREATE_DATE.ge(from))
                        .and(POKER_WITHDRAWAL.CREATE_DATE.lt(to)))
                .groupBy(day(POKER_WITHDRAWAL.CREATE_DATE))
                .fetchMap(day(POKER_WITHDRAWAL.CREATE_DATE), amountSum);
    }

    private static Field<LocalDate> day(Field<?> createdAt) {
        return field("CAST({0} AS DATE)", LocalDate.class, createdAt);
    }
}
