package dev.manestack.dto.tournament;

public record BlindLevelDTO(
    Long blindId,
    Integer levelNumber,
    Integer smallBlind,
    Integer bigBlind,
    Integer ante,
    Integer durationMinutes,
    Boolean isBreak,
    Integer breakDurationMinutes
) {}