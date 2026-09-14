package com.wellsetups.WellSell.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wellsetups.WellSell.command.CommandNames;
import com.wellsetups.WellSell.message.TextFormatter;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ResourceValidationTest {
  @Test
  void allBundledYamlParsesWithDuplicateDetectionAndCorrectVersion() throws Exception {
    for (String name :
        List.of(
            "config.yml",
            "commands.yml",
            "permissions.yml",
            "prices.yml",
            "gui.yml",
            "storage.yml",
            "integrations.yml",
            "categories.yml",
            "progression.yml",
            "progress-gui.yml",
            "containers.yml",
            "pricing.yml",
            "custom-items.yml",
            "worth-lore.yml",
            "lang/en_US.yml")) {
      ConfigTree tree = load(name);
      assertEquals(1, tree.integer("config-version", 1, 1), name);
      assertFalse(tree.values().isEmpty());
    }
    CommandNames.load(load("commands.yml"));
  }

  @Test
  void allEnglishMessagesFormatAndUseKnownPlaceholders() throws Exception {
    ConfigTree english = load("lang/en_US.yml");
    Set<String> known =
        Set.of(
            "prefix",
            "version",
            "player",
            "amount",
            "money",
            "multiplier",
            "item",
            "page",
            "max_page",
            "transaction",
            "platform",
            "economy",
            "storage",
            "prices",
            "price_source",
            "provider",
            "containers",
            "categories",
            "permission_multiplier",
            "progression_multiplier",
            "backup",
            "locale",
            "folia",
            "target",
            "previous",
            "next",
            "date",
            "source",
            "latest",
            "sell_command",
            "worth_command",
            "history_command",
            "admin_command",
            "top_command",
            "category",
            "mode",
            "rank");
    Pattern pattern = Pattern.compile("%([a-z_]+)%");
    TextFormatter formatter = new TextFormatter();
    for (Map.Entry<String, Object> entry : english.section("messages").entrySet()) {
      assertTrue(entry.getValue() instanceof String, entry.getKey());
      String template = entry.getValue().toString();
      Matcher matcher = pattern.matcher(template);
      while (matcher.find()) {
        assertTrue(known.contains(matcher.group(1)), entry.getKey() + ": " + matcher.group(1));
      }
      formatter.format(template, english.text("messages.prefix"), Map.of());
    }
  }

  private static ConfigTree load(String name) throws Exception {
    try (InputStream input =
        ResourceValidationTest.class.getClassLoader().getResourceAsStream(name)) {
      if (input == null) {
        throw new IllegalStateException("Missing resource: " + name);
      }
      return new ConfigTree(
          ConfigFiles.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8)));
    }
  }
}
