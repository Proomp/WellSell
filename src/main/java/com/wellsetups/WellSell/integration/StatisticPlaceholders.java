package com.wellsetups.WellSell.integration;

import com.wellsetups.WellSell.api.CategoryStatistics;
import com.wellsetups.WellSell.api.PlayerStatistics;
import com.wellsetups.WellSell.category.CategoryCatalog;
import com.wellsetups.WellSell.category.Progression;
import java.util.Set;

final class StatisticPlaceholders {
  private static final Set<String> TOTALS =
      Set.of("total_sales", "total_earned", "total_items_sold", "last_sale_value");

  private StatisticPlaceholders() {}

  static String value(
      String key,
      PlayerStatistics stats,
      CategoryCatalog categories,
      Progression progression,
      String loading) {
    if (TOTALS.contains(key)) {
      return stats == null ? loading : total(key, stats);
    }
    if (!key.startsWith("category_")) {
      return null;
    }
    String suffix = key.substring("category_".length());
    for (String field :
        java.util.List.of(
            "next_multiplier",
            "next_required",
            "earned",
            "items",
            "level",
            "multiplier",
            "progress")) {
      if (suffix.endsWith("_" + field)) {
        String id = suffix.substring(0, suffix.length() - field.length() - 1);
        if (categories.categories().containsKey(id)) {
          return stats == null
              ? loading
              : category(
                  field,
                  stats.categories().getOrDefault(id, CategoryStatistics.empty()),
                  progression,
                  id);
        }
      }
    }
    return null;
  }

  private static String total(String field, PlayerStatistics stats) {
    return switch (field) {
      case "total_sales" -> Long.toString(stats.sales());
      case "total_earned" -> stats.earned().toPlainString();
      case "total_items_sold" -> Long.toString(stats.items());
      default -> stats.lastSale().toPlainString();
    };
  }

  private static String category(
      String field, CategoryStatistics stats, Progression progression, String id) {
    var view = progression.view(id, stats);
    return switch (field) {
      case "earned" -> stats.earned().toPlainString();
      case "items" -> Long.toString(stats.items());
      case "level" -> Integer.toString(view.level());
      case "multiplier" -> view.multiplier().stripTrailingZeros().toPlainString();
      case "progress" -> view.percent().toPlainString();
      case "next_multiplier" -> view.nextMultiplier().stripTrailingZeros().toPlainString();
      case "next_required" -> view.nextRequired().toPlainString();
      default -> throw new IllegalArgumentException("Unknown category placeholder field");
    };
  }
}
