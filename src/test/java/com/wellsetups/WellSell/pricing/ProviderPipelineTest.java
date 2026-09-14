package com.wellsetups.WellSell.pricing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ProviderPipelineTest {
  @Test
  void firstAvailableFillsMissingPricesInPriorityOrderAndSettlesOnlyAcceptedProviders() {
    AtomicInteger settlements = new AtomicInteger();
    var result =
        ProviderPipeline.quote(
            settings(PricingSettings.Strategy.FIRST_AVAILABLE),
            Set.of(0, 1),
            (provider, slots) -> {
              if (provider == PricingSettings.Provider.ECONOMYSHOPGUI) {
                return Optional.of(quote(0, "10", settlements));
              }
              assertEquals(Set.of(1), slots);
              return Optional.of(quote(1, "20", settlements));
            });
    assertEquals(new BigDecimal("30"), result.quote().subtotal());
    assertEquals(Set.of(0, 1), result.quote().slots());
    assertEquals(0, settlements.get());
    result.quote().afterPayment().run();
    assertEquals(2, settlements.get());
  }

  @Test
  void highestAndLowestCompareFullBatchTotalsWithStablePriorityTies() {
    for (var strategy :
        List.of(PricingSettings.Strategy.HIGHEST, PricingSettings.Strategy.LOWEST)) {
      var result =
          ProviderPipeline.quote(
              settings(strategy),
              Set.of(0),
              (provider, slots) ->
                  Optional.of(
                      quote(
                          0,
                          provider == PricingSettings.Provider.ECONOMYSHOPGUI ? "20" : "10",
                          new AtomicInteger())));
      assertEquals(
          strategy == PricingSettings.Strategy.HIGHEST ? "ECONOMYSHOPGUI" : "WELLSELL_PRICES",
          result.source());
    }
    var tied =
        ProviderPipeline.quote(
            settings(PricingSettings.Strategy.HIGHEST),
            Set.of(0),
            (provider, slots) -> Optional.of(quote(0, "10", new AtomicInteger())));
    assertEquals("ECONOMYSHOPGUI", tied.source());
  }

  @Test
  void missingIntegrationFallsBackButDeniedOrInvalidProviderDoesNot() {
    var result =
        ProviderPipeline.quote(
            settings(PricingSettings.Strategy.FIRST_AVAILABLE),
            Set.of(0),
            (provider, slots) ->
                provider == PricingSettings.Provider.ECONOMYSHOPGUI
                    ? Optional.empty()
                    : Optional.of(quote(0, "5", new AtomicInteger())));
    assertEquals("WELLSELL_PRICES", result.source());
    assertThrows(
        PriceUnavailableException.class,
        () ->
            ProviderPipeline.quote(
                settings(PricingSettings.Strategy.FIRST_AVAILABLE),
                Set.of(0),
                (provider, slots) -> {
                  throw new PriceUnavailableException("Permission denied");
                }));
    assertThrows(
        PriceUnavailableException.class,
        () ->
            ProviderPipeline.quote(
                settings(PricingSettings.Strategy.FIRST_AVAILABLE),
                Set.of(0),
                (provider, slots) -> Optional.of(quote(1, "5", new AtomicInteger()))));
  }

  @Test
  void incomparablePartialOffersAreRejectedForHighestAndLowest() {
    for (var strategy :
        List.of(PricingSettings.Strategy.HIGHEST, PricingSettings.Strategy.LOWEST)) {
      assertThrows(
          PriceUnavailableException.class,
          () ->
              ProviderPipeline.quote(
                  settings(strategy),
                  Set.of(0, 1),
                  (provider, slots) -> Optional.of(quote(0, "10", new AtomicInteger()))));
    }
  }

  @Test
  void unsafeFloatingPointShopValuesNeverEnterDecimalPricing() {
    for (double value :
        new double[] {
          Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1, 0, 1e10
        }) {
      assertThrows(PriceUnavailableException.class, () -> ShopQuote.decimal(value));
    }
    assertEquals(new BigDecimal("0.1"), ShopQuote.decimal(0.1));
  }

  private static PricingSettings settings(PricingSettings.Strategy strategy) {
    return new PricingSettings(
        true,
        strategy,
        List.of(PricingSettings.Provider.ECONOMYSHOPGUI, PricingSettings.Provider.WELLSELL_PRICES),
        false);
  }

  private static ShopQuote quote(int slot, String total, AtomicInteger settlements) {
    BigDecimal value = new BigDecimal(total);
    return new ShopQuote(Set.of(slot), value, Map.of(slot, value), settlements::incrementAndGet);
  }
}
