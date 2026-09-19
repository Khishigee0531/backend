package dev.manestack.dto.admin;

public record ProfitSummary(
        long rake,
        long botNet,
        long deposits,
        long withdrawals,
        long profit) {
}
