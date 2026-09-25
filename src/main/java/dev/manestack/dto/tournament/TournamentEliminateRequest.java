package dev.manestack.dto.tournament;

public record TournamentEliminateRequest(
    Integer userId,
    Integer finalPosition
) {}