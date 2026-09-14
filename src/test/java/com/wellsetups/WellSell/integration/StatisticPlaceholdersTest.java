package com.wellsetups.WellSell.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.wellsetups.WellSell.api.CategoryStatistics;
import com.wellsetups.WellSell.api.PlayerStatistics;
import com.wellsetups.WellSell.category.CategoryCatalog;
import com.wellsetups.WellSell.category.Progression;
import com.wellsetups.WellSell.config.ConfigFiles;
import com.wellsetups.WellSell.config.ConfigTree;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StatisticPlaceholdersTest {
  private final CategoryCatalog categories =
      new CategoryCatalog(
          new ConfigTree(
              ConfigFiles.parse(
                  """
      resolution: FIRST_PRIORITY
      uncategorized: ''
      categories:
        mob_drops: {enabled: true, display-name: Mobs, icon: BONE, materials: [BONE]}
      """)),
          ignored -> true,
          ignored -> {});
  private final Progression progression =
      new Progression(
          new ConfigTree(
              ConfigFiles.parse(
                  """
      enabled: true
      requirement: ITEMS_SOLD
      combination: MULTIPLY
      categories:
        mob_drops:
          levels:
            '1': {required: '0', multiplier: '1'}
            '2': {required: '10', multiplier: '1.2'}
      """)),
          ignored -> {});

  @Test
  void existingTotalsAndNewCategoryFieldsUseImmutableSnapshots() {
    var stats =
        new PlayerStatistics(
            UUID.randomUUID(),
            "Tester",
            new BigDecimal("20.15"),
            5,
            2,
            new BigDecimal("10.10"),
            Map.of("mob_drops", new CategoryStatistics(new BigDecimal("20.15"), 5)));
    Map<String, String> expected =
        Map.of(
            "total_sales",
            "2",
            "total_earned",
            "20.15",
            "total_items_sold",
            "5",
            "last_sale_value",
            "10.10",
            "category_mob_drops_level",
            "1",
            "category_mob_drops_progress",
            "50.00",
            "category_mob_drops_next_multiplier",
            "1.2",
            "category_mob_drops_next_required",
            "10",
            "category_mob_drops_earned",
            "20.15",
            "category_mob_drops_items",
            "5");
    expected.forEach(
        (key, value) ->
            assertEquals(
                value,
                StatisticPlaceholders.value(key, stats, categories, progression, "loading"),
                key));
    assertEquals(
        "1",
        StatisticPlaceholders.value(
            "category_mob_drops_multiplier", stats, categories, progression, "loading"));
  }

  @Test
  void loadingIsExplicitAndUnknownFieldsDoNotInitiateQueries() {
    assertEquals(
        "loading",
        StatisticPlaceholders.value("total_sales", null, categories, progression, "loading"));
    assertEquals(
        "loading",
        StatisticPlaceholders.value(
            "category_mob_drops_earned", null, categories, progression, "loading"));
    assertNull(
        StatisticPlaceholders.value(
            "category_missing_earned", null, categories, progression, "loading"));
    assertNull(
        StatisticPlaceholders.value(
            "category_mob_drops_sql", null, categories, progression, "loading"));
  }
}
