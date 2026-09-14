package com.wellsetups.WellSell.category;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wellsetups.WellSell.api.CategoryStatistics;
import com.wellsetups.WellSell.config.ConfigFiles;
import com.wellsetups.WellSell.config.ConfigTree;
import java.math.BigDecimal;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class ProgressionTest {
  private Progression progression(boolean enabled, String requirement, String combination) {
    return new Progression(
        new ConfigTree(
            ConfigFiles.parse(
                """
        enabled: %s
        requirement: %s
        combination: %s
        categories:
          ores:
            levels:
              '1': {required: '0', multiplier: '1.00'}
              '2': {required: '100', multiplier: '1.10'}
              '3': {required: '300', multiplier: '1.25'}
        """
                    .formatted(enabled, requirement, combination))),
        warning -> {
          throw new AssertionError(warning);
        });
  }

  @Test
  void moneyThresholdsAndProgressAreExact() {
    var progression = progression(true, "MONEY_EARNED", "MULTIPLY");
    var view = progression.view("ores", new CategoryStatistics(new BigDecimal("200"), 0));
    assertEquals(2, view.level());
    assertEquals(new BigDecimal("50.00"), view.percent());
    assertEquals(new BigDecimal("1.25"), view.nextMultiplier());
    assertEquals(
        1, progression.view("ores", new CategoryStatistics(new BigDecimal("99.99"), 999)).level());
    assertEquals(
        2, progression.view("ores", new CategoryStatistics(new BigDecimal("100"), 0)).level());
  }

  @Test
  void itemStrategyIgnoresEarnedMoneyAndCapsAtLastTier() {
    var progression = progression(true, "ITEMS_SOLD", "MULTIPLY");
    assertEquals(
        1, progression.view("ores", new CategoryStatistics(new BigDecimal("99999"), 99)).level());
    var maximum = progression.view("ores", new CategoryStatistics(BigDecimal.ZERO, 900));
    assertEquals(3, maximum.level());
    assertTrue(maximum.maximum());
    assertEquals(new BigDecimal("100"), maximum.percent());
  }

  @Test
  void disabledProgressionAndUnknownCategoryUseOne() {
    var stats = new CategoryStatistics(new BigDecimal("99999"), 99999);
    assertEquals(
        BigDecimal.ONE,
        progression(false, "MONEY_EARNED", "MULTIPLY").view("ores", stats).multiplier());
    assertEquals(
        BigDecimal.ONE,
        progression(true, "MONEY_EARNED", "MULTIPLY").view("unknown", stats).multiplier());
  }

  @Test
  void multiplierCombinationIsExplicitAndBounded() {
    BigDecimal permission = new BigDecimal("1.25");
    BigDecimal category = new BigDecimal("1.10");
    var multiply = progression(true, "MONEY_EARNED", "MULTIPLY");
    assertEquals(new BigDecimal("1.3750"), multiply.combine(permission, category));
    assertEquals(
        permission, progression(true, "MONEY_EARNED", "HIGHEST").combine(permission, category));
    assertEquals(permission, multiply.combine(permission, BigDecimal.ONE));
    assertEquals(category, multiply.combine(BigDecimal.ONE, category));
    assertThrows(
        IllegalArgumentException.class, () -> multiply.combine(new BigDecimal("100"), category));
  }

  @Test
  void malformedLevelsWarnWithoutIntroducingAnInvalidTier() {
    var warnings = new ArrayList<String>();
    var progression =
        new Progression(
            new ConfigTree(
                ConfigFiles.parse(
                    """
        enabled: true
        requirement: ITEMS_SOLD
        combination: MULTIPLY
        categories:
          ores:
            levels:
              '1': {required: '0', multiplier: '1'}
              '2': {required: '1.5', multiplier: '2'}
        """)),
            warnings::add);
    assertEquals(1, warnings.size());
    assertEquals(BigDecimal.ONE, progression.view("ores", CategoryStatistics.empty()).multiplier());
  }
}
