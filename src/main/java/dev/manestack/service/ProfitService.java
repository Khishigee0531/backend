package dev.manestack.service;

import dev.manestack.dto.admin.ProfitSummary;
import dev.manestack.dto.admin.ProfitTrendPoint;
import dev.manestack.repository.ProfitRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Locale;

@ApplicationScoped
public class ProfitService {

    @Inject
    ProfitRepository profitRepository;

    public ProfitSummary summary(String period) {
        OffsetDateTime now = OffsetDateTime.now();
        PeriodRange range = resolvePeriod(period, now);
        return summary(range.from(), range.to());
    }

    public ProfitSummary summary(OffsetDateTime from, OffsetDateTime to) {
        long rake = profitRepository.rakeBetween(from, to);
        long botNet = profitRepository.botNetFromRealPlayers(from, to);
        long deposits = profitRepository.depositsBetween(from, to);
        long withdrawals = profitRepository.withdrawalsBetween(from, to);
        long profit = rake + botNet + deposits - withdrawals;
        return new ProfitSummary(rake, botNet, deposits, withdrawals, profit);
    }

    public List<ProfitTrendPoint> trend(int days) {
        if (days <= 0) days = 30;
        LocalDate today = LocalDate.now();
        LocalDate start = today.minusDays(days - 1L);

        OffsetDateTime from = start.atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime to = today.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);

        Map<LocalDate, Long> rake = profitRepository.rakeDaily(from, to);
        Map<LocalDate, Long> botNet = profitRepository.botNetFromRealPlayersDaily(from, to);
        Map<LocalDate, Long> deposits = profitRepository.depositsDaily(from, to);
        Map<LocalDate, Long> withdrawals = profitRepository.withdrawalsDaily(from, to);

        List<ProfitTrendPoint> points = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            LocalDate day = start.plusDays(i);
            long r = rake.getOrDefault(day, 0L);
            long b = botNet.getOrDefault(day, 0L);
            long d = deposits.getOrDefault(day, 0L);
            long w = withdrawals.getOrDefault(day, 0L);
            points.add(new ProfitTrendPoint(day, r, b, d, w, r + b + d - w));
        }
        points.sort(Comparator.comparing(ProfitTrendPoint::date));
        return points;
    }

    private PeriodRange resolvePeriod(String period, OffsetDateTime now) {
        OffsetDateTime truncated = now.toLocalDate().atStartOfDay().atOffset(now.getOffset());
        if (period == null || period.isBlank()) {
            return new PeriodRange(truncated, truncated.plusDays(1));
        }
        return switch (period.trim().toUpperCase(Locale.ROOT)) {
            case "WEEK" -> {
                int dayOfWeek = truncated.getDayOfWeek().getValue() - 1;
                OffsetDateTime start = truncated.minusDays(dayOfWeek);
                yield new PeriodRange(start, start.plusDays(7));
            }
            case "MONTH" -> {
                OffsetDateTime start = truncated.withDayOfMonth(1);
                yield new PeriodRange(start, start.plusMonths(1));
            }
            case "ALL" -> new PeriodRange(
                    OffsetDateTime.of(2000, 1, 1, 0, 0, 0, 0, now.getOffset()),
                    truncated.plusDays(1));
            default -> new PeriodRange(truncated, truncated.plusDays(1));
        };
    }

    private record PeriodRange(OffsetDateTime from, OffsetDateTime to) {
    }
}
