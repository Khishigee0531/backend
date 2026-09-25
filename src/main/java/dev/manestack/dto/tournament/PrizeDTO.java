package dev.manestack.dto.tournament;

import java.math.BigDecimal;

public record PrizeDTO(
    Long prizeId,
    Integer position,
    BigDecimal percentage,
    Integer fixedAmount,
    String description
) {}