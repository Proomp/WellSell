package com.wellsetups.WellSell.pricing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MultipliersTest {
  private final List<Multipliers.Group> groups =
      List.of(
          new Multipliers.Group("first", new BigDecimal("1.25")),
          new Multipliers.Group("second", new BigDecimal("1.50")),
          new Multipliers.Group("third", new BigDecimal("0.75")));

  @ParameterizedTest
  @CsvSource({"HIGHEST,1.50", "LOWEST,0.75", "FIRST_PRIORITY,1.25"})
  void selectsExactlyOneGrantedMultiplier(Multipliers.Selection mode, String expected) {
    assertEquals(
        new BigDecimal(expected), new Multipliers(true, mode, groups).select(permission -> true));
  }

  @Test
  void missingAndDisabledPermissionsUseOne() {
    assertEquals(
        BigDecimal.ONE,
        new Multipliers(true, Multipliers.Selection.HIGHEST, groups).select(permission -> false));
    assertEquals(
        BigDecimal.ONE,
        new Multipliers(false, Multipliers.Selection.HIGHEST, groups).select(permission -> true));
  }

  @Test
  void doesNotClampValidDiscountMultipliersToOne() {
    assertEquals(
        new BigDecimal("0.75"),
        new Multipliers(true, Multipliers.Selection.HIGHEST, groups)
            .select(permission -> permission.equals("third")));
  }

  @Test
  void validatesMultiplierBoundary() {
    assertThrows(
        IllegalArgumentException.class, () -> new Multipliers.Group("vip", BigDecimal.ZERO));
    assertThrows(
        IllegalArgumentException.class, () -> new Multipliers.Group("vip", new BigDecimal("101")));
  }
}
