package com.wellsetups.WellSell.pricing;

import java.math.BigDecimal;
import java.util.Set;

/** Quote totals belong to the whole selection, including non-linear shop pricing. */
public record ShopQuote(
    Set<Integer> slots,
    BigDecimal subtotal,
    java.util.Map<Integer, BigDecimal> weights,
    Runnable afterPayment) {
  public ShopQuote {
    slots = Set.copyOf(slots);
    weights = java.util.Map.copyOf(weights);
    if (!weights.keySet().equals(slots)
        || weights.values().stream().anyMatch(value -> value.signum() <= 0)) {
      throw new PriceUnavailableException("Shop attribution does not match accepted slots");
    }
    java.util.Objects.requireNonNull(afterPayment);
    if (subtotal.signum() < 0 || subtotal.compareTo(MoneyPolicy.HARD_MAXIMUM) > 0) {
      throw new PriceUnavailableException("Shop returned an invalid total");
    }
    if (slots.isEmpty() != (subtotal.signum() == 0)) {
      throw new PriceUnavailableException("Shop quote quantities do not match its total");
    }
  }

  public static ShopQuote empty() {
    return new ShopQuote(Set.of(), BigDecimal.ZERO, java.util.Map.of(), () -> {});
  }

  public static BigDecimal decimal(double price) {
    if (!Double.isFinite(price) || price <= 0) {
      throw new PriceUnavailableException("Shop returned an invalid price");
    }
    BigDecimal value = BigDecimal.valueOf(price);
    if (value.compareTo(MoneyPolicy.HARD_MAXIMUM) > 0) {
      throw new PriceUnavailableException("Shop price exceeds the safety limit");
    }
    return value;
  }
}
