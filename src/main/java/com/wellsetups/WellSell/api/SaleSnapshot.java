package com.wellsetups.WellSell.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Immutable transaction data. Contains no live Player, inventory, or ItemStack references. */
public record SaleSnapshot(
    UUID transaction,
    UUID player,
    String playerName,
    Instant time,
    int amount,
    BigDecimal money,
    BigDecimal multiplier,
    String source,
    SaleSummary summary) {}
