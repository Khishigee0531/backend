package dev.manestack.dto.tournament;

public record TournamentStatusUpdateRequest(
    String status,
    String cancelledReason
) {}