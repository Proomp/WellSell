package com.wellsetups.WellSell.category;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wellsetups.WellSell.config.ConfigFiles;
import com.wellsetups.WellSell.config.ConfigTree;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CategoryTest {
  private CategoryCatalog catalog(String resolution, List<String> warnings) {
    return new CategoryCatalog(
        new ConfigTree(
            ConfigFiles.parse(
                """
        resolution: %s
        uncategorized: misc
        categories:
          first: {enabled: true, display-name: First, icon: DIAMOND, materials: [DIAMOND, UNKNOWN]}
          second: {enabled: true, display-name: Second, icon: EMERALD, materials: [DIAMOND, EMERALD]}
          misc: {enabled: true, display-name: Other, icon: CHEST, materials: []}
          INVALID: {enabled: true}
        """
                    .formatted(resolution))),
        name -> !name.equals("UNKNOWN"),
        warnings::add);
  }

  @Test
  void firstPriorityIsDeterministicAndWarnsOnDuplicates() {
    List<String> warnings = new ArrayList<>();
    CategoryCatalog catalog = catalog("FIRST_PRIORITY", warnings);
    assertEquals("first", catalog.resolve("minecraft:diamond", "DIAMOND").orElseThrow().id());
    assertEquals(3, warnings.size());
    assertEquals("second", catalog.resolve("minecraft:emerald", "EMERALD").orElseThrow().id());
  }

  @Test
  void uniquenessDoesNotHideConflictsBehindTheFallback() {
    CategoryCatalog catalog = catalog("REQUIRE_UNIQUE", new ArrayList<>());
    assertTrue(catalog.resolve("minecraft:diamond", "DIAMOND").isEmpty());
    assertEquals("misc", catalog.resolve("minecraft:stone", "STONE").orElseThrow().id());
  }

  @Test
  void invalidDefinitionsAreExcluded() {
    CategoryCatalog catalog = catalog("FIRST_PRIORITY", new ArrayList<>());
    assertEquals(3, catalog.categories().size());
    assertEquals("misc", catalog.resolve("minecraft:unknown", "UNKNOWN").orElseThrow().id());
  }
}
