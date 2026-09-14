package com.wellsetups.WellSell.pricing;

import com.wellsetups.WellSell.api.CategoryContribution;
import com.wellsetups.WellSell.api.CategoryStatistics;
import com.wellsetups.WellSell.api.PlayerStatistics;
import com.wellsetups.WellSell.api.SaleSummary;
import com.wellsetups.WellSell.category.CategoryCatalog;
import com.wellsetups.WellSell.config.ExpansionSettings;
import com.wellsetups.WellSell.sell.SellPlan;
import com.wellsetups.WellSell.sell.Stock;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class CategoryPricing {
  private CategoryPricing() {}

  static SellPlan plan(
      List<Stock> stock,
      Map<Integer, BigDecimal> weights,
      BigDecimal subtotal,
      MoneyPolicy money,
      BigDecimal permission,
      ExpansionSettings expansion,
      PlayerStatistics statistics,
      String provider) {
    return calculate(
        stock.stream()
            .map(
                item ->
                    new ValuationItem(
                        item.slot(), item.material(), item.material(), item.requested()))
            .toList(),
        stock.stream()
            .map(item -> new SellPlan.Line(item.slot(), item.material(), item.requested()))
            .toList(),
        weights,
        subtotal,
        money,
        permission,
        expansion,
        statistics,
        provider,
        0);
  }

  static SellPlan containers(
      ContainerSelection selection,
      ShopQuote quote,
      MoneyPolicy money,
      BigDecimal permission,
      ExpansionSettings expansion,
      PlayerStatistics statistics,
      String provider,
      java.util.function.Function<org.bukkit.inventory.ItemStack, String> identities) {
    var items =
        selection.items().entrySet().stream()
            .map(
                entry ->
                    new ValuationItem(
                        entry.getKey(),
                        identities.apply(entry.getValue()),
                        entry.getValue().getType().name(),
                        entry.getValue().getAmount()))
            .toList();
    return calculate(
        items,
        selection.groups().stream().map(ContainerSelection.Group::line).toList(),
        quote.weights(),
        quote.subtotal(),
        money,
        permission,
        expansion,
        statistics,
        provider,
        selection.containers());
  }

  private record ValuationItem(int slot, String identity, String material, int requested) {}

  private static SellPlan calculate(
      List<ValuationItem> stock,
      List<SellPlan.Line> lines,
      Map<Integer, BigDecimal> weights,
      BigDecimal subtotal,
      MoneyPolicy money,
      BigDecimal permission,
      ExpansionSettings expansion,
      PlayerStatistics statistics,
      String provider,
      int containers) {
    if (stock.isEmpty()) {
      return SellPlan.quoted(List.of(), BigDecimal.ZERO, money, permission);
    }
    Map<String, BigDecimal> bases = new TreeMap<>();
    Map<String, Long> quantities = new TreeMap<>();
    Map<Integer, BigDecimal> allocated =
        DecimalAllocation.split(subtotal, new TreeMap<>(weights), Math.max(18, subtotal.scale()));
    for (ValuationItem item : stock) {
      String category =
          expansion
              .categories()
              .resolve(item.identity(), item.material())
              .map(CategoryCatalog.Category::id)
              .orElse("");
      bases.merge(category, allocated.get(item.slot()), BigDecimal::add);
      quantities.merge(category, (long) item.requested(), Math::addExact);
    }
    Map<String, BigDecimal> combined = new LinkedHashMap<>();
    Map<String, BigDecimal> progress = new LinkedHashMap<>();
    Map<String, BigDecimal> values = new LinkedHashMap<>();
    bases.forEach(
        (id, base) -> {
          CategoryStatistics stats =
              statistics == null
                  ? CategoryStatistics.empty()
                  : statistics.categories().getOrDefault(id, CategoryStatistics.empty());
          BigDecimal progression = expansion.progression().view(id, stats).multiplier();
          BigDecimal multiplier =
              expansion.progression().enabled()
                  ? expansion.progression().combine(permission, progression)
                  : permission;
          progress.put(id, progression);
          combined.put(id, multiplier);
          if (base.signum() > 0) {
            values.put(id, base.multiply(multiplier));
          }
        });
    BigDecimal raw = values.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal total = money.round(raw, BigDecimal.ONE);
    if (total.signum() == 0) {
      return SellPlan.quoted(List.of(), BigDecimal.ZERO, money, permission);
    }
    Map<String, BigDecimal> earnings = DecimalAllocation.split(total, values, money.scale());
    Map<String, CategoryContribution> contributions = new LinkedHashMap<>();
    quantities.forEach(
        (id, count) -> {
          if (!id.isEmpty()) {
            contributions.put(
                id,
                new CategoryContribution(
                    earnings.getOrDefault(id, BigDecimal.ZERO),
                    count,
                    permission,
                    progress.get(id),
                    combined.get(id)));
          }
        });
    BigDecimal effective = raw.divide(subtotal, 12, RoundingMode.HALF_UP).stripTrailingZeros();
    return new SellPlan(
        lines,
        stock.stream().mapToInt(ValuationItem::requested).sum(),
        total,
        effective,
        new SaleSummary(provider, contributions, containers));
  }
}
