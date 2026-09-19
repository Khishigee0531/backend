package dev.manestack.dto.admin;

import java.time.LocalDate;

public record ProfitTrendPoint(
        LocalDate date,
        long rake,
        long botNet,
        long deposits,
        long withdrawals,
        long profit) {
}
