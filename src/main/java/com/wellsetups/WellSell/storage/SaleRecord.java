package com.wellsetups.WellSell.storage;

import com.wellsetups.WellSell.sell.SellPlan;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record SaleRecord(
    UUID id,
    UUID player,
    String playerName,
    Instant time,
    int amount,
    BigDecimal money,
    BigDecimal multiplier,
    String source,
    String recoveryItems,
    com.wellsetups.WellSell.api.SaleSummary summary) {
  public SaleRecord {
    java.util.Objects.requireNonNull(id);
    java.util.Objects.requireNonNull(player);
    java.util.Objects.requireNonNull(time);
    java.util.Objects.requireNonNull(recoveryItems);
    if (playerName.length() > 32
        || source.length() > 16
        || amount < 1
        || money.signum() <= 0
        || money.compareTo(com.wellsetups.WellSell.pricing.MoneyPolicy.HARD_MAXIMUM) > 0
        || money.scale() > 6
        || multiplier.signum() <= 0
        || multiplier.toPlainString().length() > 32) {
      throw new IllegalArgumentException("Invalid persisted sale");
    }
    summary.validateTotals(money, amount);
  }

  public SaleRecord(
      UUID id,
      UUID player,
      String playerName,
      Instant time,
      int amount,
      BigDecimal money,
      BigDecimal multiplier,
      String source,
      String recoveryItems) {
    this(
        id,
        player,
        playerName,
        time,
        amount,
        money,
        multiplier,
        source,
        recoveryItems,
        com.wellsetups.WellSell.api.SaleSummary.empty());
  }

  public static SaleRecord create(
      UUID player, String name, SellPlan plan, String source, String recovery) {
    return new SaleRecord(
        UUID.randomUUID(),
        player,
        name,
        Instant.now(),
        plan.amount(),
        plan.money(),
        plan.multiplier(),
        source,
        recovery,
        plan.summary());
  }
}
