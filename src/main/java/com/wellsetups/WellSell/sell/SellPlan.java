package com.wellsetups.WellSell.sell;

import com.wellsetups.WellSell.pricing.MoneyPolicy;
import com.wellsetups.WellSell.pricing.PriceProvider;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record SellPlan(
    List<Line> lines,
    int amount,
    BigDecimal money,
    BigDecimal multiplier,
    com.wellsetups.WellSell.api.SaleSummary summary) {
  public SellPlan(List<Line> lines, int amount, BigDecimal money, BigDecimal multiplier) {
    this(lines, amount, money, multiplier, com.wellsetups.WellSell.api.SaleSummary.empty());
  }

  public record Line(int slot, String material, int amount, int valuedAmount) {
    public Line(int slot, String material, int amount) {
      this(slot, material, amount, amount);
    }

    public Line {
      java.util.Objects.requireNonNull(material);
      if (slot < 0
          || slot >= 36
          || amount < 1
          || amount > 127
          || valuedAmount < 1
          || valuedAmount > 1_000_000) {
        throw new IllegalArgumentException("Invalid plan line");
      }
    }
  }

  public SellPlan {
    lines = List.copyOf(lines);
    summary.validateTotals(money, amount);
    Set<Integer> seen = new HashSet<>();
    int calculatedAmount = 0;
    for (Line line : lines) {
      if (!seen.add(line.slot())) {
        throw new IllegalArgumentException("Duplicate plan line slot");
      }
      calculatedAmount = Math.addExact(calculatedAmount, line.valuedAmount());
    }
    if (amount != calculatedAmount
        || money.signum() < 0
        || multiplier.signum() <= 0
        || money.compareTo(MoneyPolicy.HARD_MAXIMUM) > 0
        || lines.isEmpty() != (money.signum() == 0)) {
      throw new IllegalArgumentException("Invalid plan totals");
    }
  }

  public static SellPlan create(
      List<Stock> inventory, PriceProvider prices, MoneyPolicy policy, BigDecimal multiplier) {
    List<Line> lines = new ArrayList<>();
    Set<Integer> slots = new HashSet<>();
    BigDecimal subtotal = BigDecimal.ZERO;
    int quantity = 0;
    for (Stock stock : inventory) {
      if (!slots.add(stock.slot())) {
        throw new IllegalArgumentException("Duplicate inventory slot in sell request");
      }
      BigDecimal price = prices.unitPrice(stock.material()).orElse(BigDecimal.ZERO);
      if (price.signum() < 0
          || price.compareTo(MoneyPolicy.HARD_MAXIMUM) > 0
          || price.scale() > 12) {
        throw new IllegalArgumentException("Price provider returned an invalid unit price");
      }
      if (price.signum() == 0) {
        continue;
      }
      lines.add(new Line(stock.slot(), stock.material(), stock.requested()));
      quantity = Math.addExact(quantity, stock.requested());
      subtotal = subtotal.add(price.multiply(BigDecimal.valueOf(stock.requested())));
    }
    BigDecimal total = policy.round(subtotal, multiplier);
    return total.signum() == 0
        ? new SellPlan(List.of(), 0, total, multiplier)
        : new SellPlan(lines, quantity, total, multiplier);
  }

  public boolean empty() {
    return lines.isEmpty();
  }

  public static SellPlan quoted(
      List<Stock> stock, BigDecimal subtotal, MoneyPolicy policy, BigDecimal multiplier) {
    BigDecimal total = policy.round(subtotal, multiplier);
    if (total.signum() == 0) {
      return new SellPlan(List.of(), 0, total, multiplier);
    }
    List<Line> lines =
        stock.stream()
            .map(item -> new Line(item.slot(), item.material(), item.requested()))
            .toList();
    return new SellPlan(lines, lines.stream().mapToInt(Line::amount).sum(), total, multiplier);
  }

  public boolean sameQuote(SellPlan other) {
    return lines.equals(other.lines)
        && money.compareTo(other.money) == 0
        && multiplier.compareTo(other.multiplier) == 0
        && summary.equals(other.summary);
  }
}
