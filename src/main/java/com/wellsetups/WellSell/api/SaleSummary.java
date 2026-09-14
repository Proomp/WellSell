package com.wellsetups.WellSell.api;

import java.util.Map;

public record SaleSummary(
    String provider, Map<String, CategoryContribution> categories, int containers) {
  public SaleSummary {
    categories = Map.copyOf(categories);
    if (provider.length() > 256 || categories.size() > 128 || containers < 0) {
      throw new IllegalArgumentException("Invalid sale summary");
    }
    if (categories.keySet().stream().anyMatch(key -> !key.matches("[a-z][a-z0-9_]{0,31}"))) {
      throw new IllegalArgumentException("Invalid summary category ID");
    }
  }

  public static SaleSummary empty() {
    return new SaleSummary("LEGACY", Map.of(), 0);
  }

  public void validateTotals(java.math.BigDecimal money, long items) {
    java.math.BigDecimal attributed = java.math.BigDecimal.ZERO;
    long quantity = 0;
    for (CategoryContribution value : categories.values()) {
      attributed = attributed.add(value.earned());
      quantity = Math.addExact(quantity, value.items());
    }
    if (attributed.compareTo(money) > 0 || quantity > items) {
      throw new IllegalArgumentException("Category contributions exceed the sale totals");
    }
  }
}
