package com.wellsetups.WellSell.pricing;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;

/** Preserves whole-shop batch totals and calls each accepted provider's settlement once. */
public final class ProviderPipeline {
  public record Result(ShopQuote quote, String source) {}

  private ProviderPipeline() {}

  public static Result quote(
      PricingSettings settings,
      Set<Integer> requested,
      BiFunction<PricingSettings.Provider, Set<Integer>, Optional<ShopQuote>> providers) {
    if (requested.isEmpty()) {
      return new Result(ShopQuote.empty(), "NONE");
    }
    return settings.strategy() == PricingSettings.Strategy.FIRST_AVAILABLE
        ? first(settings, requested, providers)
        : compare(settings, requested, providers);
  }

  private static Result first(
      PricingSettings settings,
      Set<Integer> requested,
      BiFunction<PricingSettings.Provider, Set<Integer>, Optional<ShopQuote>> providers) {
    Set<Integer> remaining = new java.util.TreeSet<>(requested);
    Map<Integer, BigDecimal> values = new LinkedHashMap<>();
    List<Runnable> callbacks = new ArrayList<>();
    List<String> sources = new ArrayList<>();
    BigDecimal total = BigDecimal.ZERO;
    for (var provider : settings.providers()) {
      if (remaining.isEmpty()) {
        break;
      }
      ShopQuote quote = providers.apply(provider, Set.copyOf(remaining)).orElse(ShopQuote.empty());
      validate(remaining, quote);
      if (quote.slots().isEmpty()) {
        continue;
      }
      values.putAll(
          DecimalAllocation.split(
              quote.subtotal(),
              new java.util.TreeMap<>(quote.weights()),
              Math.max(18, quote.subtotal().scale())));
      remaining.removeAll(quote.slots());
      callbacks.add(quote.afterPayment());
      sources.add(provider.name());
      total = total.add(quote.subtotal());
    }
    return new Result(
        new ShopQuote(values.keySet(), total, values, () -> callbacks.forEach(Runnable::run)),
        String.join(",", sources));
  }

  private static Result compare(
      PricingSettings settings,
      Set<Integer> requested,
      BiFunction<PricingSettings.Provider, Set<Integer>, Optional<ShopQuote>> providers) {
    Result best = null;
    boolean partial = false;
    for (var provider : settings.providers()) {
      ShopQuote quote = providers.apply(provider, requested).orElse(ShopQuote.empty());
      validate(requested, quote);
      if (!quote.slots().equals(requested)) {
        partial |= !quote.slots().isEmpty();
        continue;
      }
      if (best == null || better(quote, best.quote(), settings.strategy())) {
        best = new Result(quote, provider.name());
      }
    }
    if (best == null && partial) {
      throw new PriceUnavailableException(
          "No provider can price the complete selection; choose FIRST_AVAILABLE or a smaller selection");
    }
    return best == null ? new Result(ShopQuote.empty(), "NONE") : best;
  }

  private static boolean better(
      ShopQuote candidate, ShopQuote current, PricingSettings.Strategy strategy) {
    int comparison = candidate.subtotal().compareTo(current.subtotal());
    return strategy == PricingSettings.Strategy.HIGHEST ? comparison > 0 : comparison < 0;
  }

  private static void validate(Set<Integer> requested, ShopQuote quote) {
    if (!requested.containsAll(quote.slots())) {
      throw new PriceUnavailableException("Provider returned unrequested components");
    }
  }
}
