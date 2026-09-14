package com.wellsetups.WellSell.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wellsetups.WellSell.config.ConfigTree;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LocalizationTest {
  @Test
  void fallsBackToCustomizedEnglishThenBundledEnglishAndWarnsOnce() {
    List<String> warnings = new ArrayList<>();
    LocaleCatalog catalog =
        new LocaleCatalog(
            tree(Map.of("sold", "satildi")),
            tree(Map.of("sold", "sold", "missing", "custom English")),
            tree(Map.of("missing", "English", "bundled", "bundled English")),
            warnings::add);
    assertEquals("satildi", catalog.text("sold"));
    assertEquals("custom English", catalog.text("missing"));
    assertEquals("custom English", catalog.text("missing"));
    assertEquals("bundled English", catalog.text("bundled"));
    assertEquals(2, warnings.size());
  }

  @Test
  void placeholderValuesCannotInjectTagsOrRecursivelyExpandOtherPlaceholders() {
    String result =
        new TextFormatter()
            .format(
                "%prefix% %player% %amount%",
                "<aqua>WS</aqua>", Map.of("player", "<red>name %amount%", "amount", "5"));
    assertTrue(result.contains("<red>name %amount%"));
    assertTrue(result.endsWith("5"));
  }

  @Test
  void unknownPlaceholdersRemainVisibleAndSafe() {
    assertEquals(
        "Unknown %mystery%", new TextFormatter().format("Unknown %mystery%", "", Map.of()));
  }

  @Test
  void supportsLegacyHexAndMiniMessageTogether() {
    String text = new TextFormatter().format("&#00E5FFHello <bold>world</bold> &aok", "", Map.of());
    assertTrue(text.contains("Hello"));
    assertTrue(text.contains("§l"));
    assertTrue(text.contains("§a"));
  }

  private static ConfigTree tree(Map<String, Object> messages) {
    return new ConfigTree(Map.of("messages", messages));
  }
}
