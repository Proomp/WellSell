package com.wellsetups.WellSell.api;

import java.math.BigDecimal;

public record ProgressionView(
    int level,
    BigDecimal multiplier,
    BigDecimal percent,
    BigDecimal currentRequired,
    BigDecimal nextRequired,
    BigDecimal nextMultiplier,
    boolean maximum) {}
