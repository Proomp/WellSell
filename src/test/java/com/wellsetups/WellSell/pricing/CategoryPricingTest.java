package com.wellsetups.WellSell.pricing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.wellsetups.WellSell.api.CategoryStatistics;
import com.wellsetups.WellSell.api.PlayerStatistics;
import com.wellsetups.WellSell.category.CategoryCatalog;
import com.wellsetups.WellSell.category.Progression;
import com.wellsetups.WellSell.config.ConfigFiles;
import com.wellsetups.WellSell.config.ConfigTree;
import com.wellsetups.WellSell.config.ExpansionSettings;
import com.wellsetups.WellSell.sell.Stock;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CategoryPricingTest {
  private static final MoneyPolicy MONEY =
      new MoneyPolicy(2, RoundingMode.HALF_UP, MoneyPolicy.HARD_MAXIMUM);
  private static final List<Stock> STOCK =
      List.of(new Stock(0, "DIAMOND", 2, 2), new Stock(1, "WHEAT", 3, 3));

  @Test
  void categoryBonusesAreCapturedBeforeSaleAndConserveExactPayout() {
    var plan =
        CategoryPricing.plan(
            STOCK,
            Map.of(0, new BigDecimal("10"), 1, new BigDecimal("20")),
            new BigDecimal("30"),
            MONEY,
            new BigDecimal("1.25"),
            expansion(true, "MULTIPLY"),
            stats(),
            "ECONOMYSHOPGUI");
    assertEquals(new BigDecimal("50.00"), plan.money());
    var ores = plan.summary().categories().get("ores");
    assertEquals(new BigDecimal("25.00"), ores.earned());
    assertEquals(2, ores.items());
    assertEquals(0, new BigDecimal("2.50").compareTo(ores.combinedMultiplier()));
    assertEquals("ECONOMYSHOPGUI", plan.summary().provider());
    assertEquals(5, plan.amount());
  }

  @Test
  void disabledProgressionKeepsLegacyPermissionMultiplierIncludingBelowOne() {
    var plan =
        CategoryPricing.plan(
            STOCK,
            Map.of(0, BigDecimal.ONE, 1, BigDecimal.ONE),
            new BigDecimal("0.03"),
            MONEY,
            new BigDecimal("0.5"),
            expansion(false, "HIGHEST"),
            null,
            "CONFIG");
    assertEquals(new BigDecimal("0.02"), plan.money());
    assertEquals(new BigDecimal("0.5"), plan.multiplier());
    assertEquals(
        plan.money(),
        plan.summary().categories().values().stream()
            .map(value -> value.earned())
            .reduce(BigDecimal.ZERO, BigDecimal::add));
  }

  @Test
  void highestDoesNotMultiplyPermissionAndProgression() {
    var plan =
        CategoryPricing.plan(
            STOCK,
            Map.of(0, new BigDecimal("10"), 1, new BigDecimal("20")),
            new BigDecimal("30"),
            MONEY,
            new BigDecimal("1.25"),
            expansion(true, "HIGHEST"),
            stats(),
            "CONFIG");
    assertEquals(new BigDecimal("45.00"), plan.money());
  }

  @Test
  void allocationKeepsAuthoritativeNonLinearBatchTotalAndRejectsInvalidWeights() {
    Map<Integer, BigDecimal> weights = new LinkedHashMap<>();
    weights.put(0, BigDecimal.ONE);
    weights.put(1, BigDecimal.ONE);
    weights.put(2, BigDecimal.ONE);
    Map<Integer, BigDecimal> values = DecimalAllocation.split(new BigDecimal("0.01"), weights, 2);
    assertEquals(new BigDecimal("0.00"), values.get(0));
    assertEquals(new BigDecimal("0.01"), values.get(2));
    assertThrows(
        IllegalArgumentException.class,
        () -> DecimalAllocation.split(BigDecimal.ONE, Map.of(0, BigDecimal.ZERO), 2));
    assertThrows(
        IllegalArgumentException.class, () -> DecimalAllocation.split(BigDecimal.ONE, Map.of(), 2));
  }

  private static PlayerStatistics stats() {
    return new PlayerStatistics(
        UUID.randomUUID(),
        "Tester",
        new BigDecimal("100"),
        0,
        1,
        BigDecimal.ZERO,
        Map.of("ores", new CategoryStatistics(new BigDecimal("100"), 0)));
  }

  private static ExpansionSettings expansion(boolean enabled, String mode) {
    var categories =
        new CategoryCatalog(
            new ConfigTree(
                ConfigFiles.parse(
                    """
        resolution: FIRST_PRIORITY
        uncategorized: ''
        categories:
          ores: {enabled: true, display-name: Ores, icon: DIAMOND, materials: [DIAMOND]}
          farming: {enabled: true, display-name: Farming, icon: WHEAT, materials: [WHEAT]}
        """)),
            ignored -> true,
            ignored -> {});
    var progression =
        new Progression(
            new ConfigTree(
                ConfigFiles.parse(
                    """
        enabled: %s
        requirement: MONEY_EARNED
        combination: %s
        categories:
          ores:
            levels:
              '1': {required: '0', multiplier: '1.00'}
              '2': {required: '100', multiplier: '2.00'}
        """
                        .formatted(enabled, mode))),
            ignored -> {});
    return new ExpansionSettings(categories, progression, null, null, null, null);
  }
}
