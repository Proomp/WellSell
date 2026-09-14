package com.wellsetups.WellSell.pricing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wellsetups.WellSell.sell.SellPlan;
import com.wellsetups.WellSell.sell.Stock;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PricingTest {
  private final MoneyPolicy policy =
      new MoneyPolicy(2, RoundingMode.HALF_UP, new BigDecimal("1000000000"));

  @Test
  void computesRequestedQuantitiesAndRoundsOnceAfterMultiplier() {
    PriceProvider prices = material -> Optional.of(new BigDecimal("0.333"));
    SellPlan plan =
        SellPlan.create(
            List.of(new Stock(0, "DIAMOND", 64, 2), new Stock(8, "IRON_INGOT", 4, 1)),
            prices,
            policy,
            new BigDecimal("1.25"));
    assertEquals(3, plan.amount());
    assertEquals(new BigDecimal("1.25"), plan.money());
    assertEquals(2, plan.lines().size());
    assertEquals(2, plan.lines().get(0).amount());
  }

  @ParameterizedTest
  @CsvSource({
    "HALF_UP,1.01",
    "HALF_EVEN,1.00",
    "DOWN,1.00",
    "UP,1.01",
    "FLOOR,1.00",
    "CEILING,1.01",
    "HALF_DOWN,1.00"
  })
  void roundingIsConfigurable(RoundingMode mode, String expected) {
    MoneyPolicy selected = new MoneyPolicy(2, mode, BigDecimal.TEN);
    assertEquals(new BigDecimal(expected), selected.round(new BigDecimal("1.005"), BigDecimal.ONE));
  }

  @ParameterizedTest
  @ValueSource(strings = {"-1", "0", "NaN", "Infinity", "1e9999", "text", "0.0000000000001"})
  void rejectsInvalidPricesWithoutRejectingValidNeighbors(String invalid) {
    List<String> warnings = new ArrayList<>();
    ConfiguredPrices prices =
        new ConfiguredPrices(
            Map.of("DIAMOND", invalid, "IRON_INGOT", "5.00"), key -> true, warnings::add);
    assertTrue(prices.unitPrice("DIAMOND").isEmpty());
    assertEquals(new BigDecimal("5.00"), prices.unitPrice("IRON_INGOT").orElseThrow());
    assertEquals(1, warnings.size());
  }

  @Test
  void unknownMaterialsAreIgnoredAndEmptyMapsStayEmpty() {
    List<String> warnings = new ArrayList<>();
    ConfiguredPrices prices =
        new ConfiguredPrices(
            Map.of("RUBY", "1", "diamond", "1"), key -> key.equals("DIAMOND"), warnings::add);
    assertEquals(0, prices.size());
    assertEquals(2, warnings.size());
    assertEquals(0, new ConfiguredPrices(Map.of(), key -> true, warnings::add).size());
  }

  @Test
  void skipsUnpricedItemsAndNeverRemovesItemsForRoundedZero() {
    assertTrue(
        SellPlan.create(
                List.of(new Stock(0, "DIRT", 64, 64)),
                material -> Optional.empty(),
                policy,
                BigDecimal.ONE)
            .empty());
    SellPlan tiny =
        SellPlan.create(
            List.of(new Stock(0, "DIAMOND", 1, 1)),
            material -> Optional.of(new BigDecimal("0.001")),
            policy,
            BigDecimal.ONE);
    assertTrue(tiny.empty());
    assertEquals(0, tiny.amount());
  }

  @Test
  void capsOverflowingSalesBeforeAnyMutation() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            SellPlan.create(
                List.of(new Stock(0, "DIAMOND", 64, 64)),
                material -> Optional.of(MoneyPolicy.HARD_MAXIMUM),
                policy,
                BigDecimal.ONE));
  }

  @Test
  void rejectsDuplicateSlotsAndInvalidQuantities() {
    Stock stack = new Stock(0, "DIAMOND", 64, 64);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            SellPlan.create(
                List.of(stack, stack),
                material -> Optional.of(BigDecimal.ONE),
                policy,
                BigDecimal.ONE));
    assertThrows(IllegalArgumentException.class, () -> new Stock(0, "DIAMOND", 4, 5));
    assertThrows(IllegalArgumentException.class, () -> new Stock(36, "DIAMOND", 1, 1));
    assertThrows(IllegalArgumentException.class, () -> new Stock(0, "DIAMOND", 1, 0));
  }

  @Test
  void plansDefensivelyCopyTheirLines() {
    List<SellPlan.Line> lines = new ArrayList<>();
    lines.add(new SellPlan.Line(0, "DIAMOND", 1));
    SellPlan plan = new SellPlan(lines, 1, BigDecimal.ONE, BigDecimal.ONE);
    lines.clear();
    assertFalse(plan.empty());
    assertThrows(UnsupportedOperationException.class, () -> plan.lines().clear());
  }

  @Test
  void rejectsInvalidExternalProviderValues() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            SellPlan.create(
                List.of(new Stock(0, "DIAMOND", 1, 1)),
                material -> Optional.of(new BigDecimal("-5")),
                policy,
                BigDecimal.ONE));
  }
}
