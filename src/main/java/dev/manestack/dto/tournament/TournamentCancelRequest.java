package dev.manestack.dto.tournament;

public record TournamentCancelRequest(
    Integer userId,
    String reason
) {}