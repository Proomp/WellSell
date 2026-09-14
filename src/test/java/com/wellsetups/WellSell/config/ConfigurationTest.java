package com.wellsetups.WellSell.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wellsetups.WellSell.command.CommandId;
import com.wellsetups.WellSell.command.CommandNames;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigurationTest {
  @TempDir Path directory;

  @Test
  void migrationPreservesNestedValuesAndOpenCollections() {
    Map<String, Object> custom =
        Map.of("money", Map.of("scale", 3), "prices", Map.of(), "aliases", List.of("custom"));
    Map<String, Object> defaults =
        Map.of(
            "money",
            Map.of("scale", 2, "rounding", "UP"),
            "prices",
            Map.of("DIAMOND", 100),
            "aliases",
            List.of("sell"));
    Map<String, Object> merged = DefaultMerger.merge(custom, defaults, Set.of("prices"));
    ConfigTree result = new ConfigTree(merged);
    assertEquals(3, result.integer("money.scale", 0, 6));
    assertEquals("UP", result.text("money.rounding"));
    assertTrue(result.section("prices").isEmpty());
    assertEquals(List.of("custom"), result.strings("aliases"));
    assertEquals(merged, DefaultMerger.merge(merged, defaults, Set.of("prices")));
  }

  @Test
  void completeDefaultConfigurationLoadsWithoutWarnings() throws Exception {
    List<String> warnings = new ArrayList<>();
    Settings settings =
        new SettingsLoader(directory, getClass().getClassLoader(), warnings::add).load();
    assertEquals(List.of(), warnings);
    assertEquals(219, settings.prices().size());
    assertEquals("sell", settings.commands().name(CommandId.SELL));
    assertEquals(54, settings.gui().size());
    assertTrue(settings.gui().enabled());
    assertEquals("$1,234.50", settings.formatMoney(new java.math.BigDecimal("1234.5")));
  }

  @Test
  void invalidGuiGeometryFallsBackWithoutReenablingADisabledGui() throws Exception {
    Files.writeString(
        directory.resolve("gui.yml"), "config-version: 1\ngui:\n  enabled: false\n  rows: 2.5\n");
    List<String> warnings = new ArrayList<>();
    Settings settings =
        new SettingsLoader(directory, getClass().getClassLoader(), warnings::add).load();
    assertEquals(54, settings.gui().size());
    assertFalse(settings.gui().enabled());
    assertEquals(1, warnings.size());
    assertTrue(warnings.get(0).contains("Invalid GUI configuration"));
  }

  @Test
  void migrationPreservesOriginalCommentsAndCreatesBackupAndCompanion() throws Exception {
    String custom = "# My server settings\nconfig-version: 1\nlocale: tr_TR\n";
    Files.writeString(directory.resolve("config.yml"), custom);
    ConfigFiles files = new ConfigFiles(directory, getClass().getClassLoader());
    ConfigTree result = files.load("config.yml", Set.of("multipliers.groups"));
    assertEquals("tr_TR", result.text("locale"));
    assertEquals(custom, Files.readString(directory.resolve("config.yml")));
    assertEquals(custom, Files.readString(directory.resolve("config.yml.v1.bak")));
    assertTrue(Files.exists(directory.resolve("config.yml.merged.yml")));
    files.load("config.yml", Set.of("multipliers.groups"));
    assertEquals(custom, Files.readString(directory.resolve("config.yml.v1.bak")));
  }

  @Test
  void duplicateYamlKeysFailExplicitly() {
    assertThrows(
        org.yaml.snakeyaml.error.YAMLException.class,
        () -> ConfigFiles.parse("prices:\n  DIAMOND: 1\n  DIAMOND: 2\n"));
  }

  @Test
  void unsafeLocalePathsAreRejected() {
    ConfigFiles files = new ConfigFiles(directory, getClass().getClassLoader());
    assertThrows(IllegalArgumentException.class, () -> files.locale("../../secret"));
  }

  @Test
  void commandAliasesCannotCollideAcrossRoots() {
    Map<CommandId, CommandNames.Definition> map = names();
    map.put(CommandId.SELL, new CommandNames.Definition("sell", List.of("worth")));
    assertThrows(IllegalArgumentException.class, () -> new CommandNames(map));
  }

  @Test
  void rejectsCommandInjectionAndDuplicateAliases() {
    Map<CommandId, CommandNames.Definition> map = names();
    map.put(CommandId.SELL, new CommandNames.Definition("sell:admin", List.of()));
    assertThrows(IllegalArgumentException.class, () -> new CommandNames(map));
    map.put(CommandId.SELL, new CommandNames.Definition("sell", List.of("sat", "sat")));
    assertThrows(IllegalArgumentException.class, () -> new CommandNames(map));
  }

  @Test
  void renamedCommandsArePreserved() {
    Map<CommandId, CommandNames.Definition> map = names();
    map.put(CommandId.SELL, new CommandNames.Definition("satis", List.of("sat")));
    CommandNames configured = new CommandNames(map);
    map.clear();
    assertEquals("satis", configured.name(CommandId.SELL));
    assertFalse(configured.commands().isEmpty());
  }

  @Test
  void configTreeRejectsFractionalIntegerAndFreezesCollections() {
    ConfigTree tree = new ConfigTree(Map.of("amount", "1.2", "list", List.of("a")));
    assertThrows(ArithmeticException.class, () -> tree.integer("amount", 1, 2));
    assertThrows(UnsupportedOperationException.class, () -> tree.values().clear());
    assertThrows(UnsupportedOperationException.class, () -> tree.strings("list").clear());
  }

  @Test
  void expansionMigrationPreservesCustomizedLocaleIntegrationAliasesAndPermissions()
      throws Exception {
    Map<String, String> originals =
        Map.of(
            "integrations.yml",
                "config-version: 1\nprices:\n  source: SHOPGUIPLUS\n  apply-wellsell-multiplier: true\n",
            "commands.yml",
                "config-version: 1\ncommands:\n  sell:\n    name: trade\n    aliases: [cashin]\n",
            "permissions.yml",
                "config-version: 1\npermissions:\n  sell: {node: server.trade, default: op}\n",
            "lang/en_US.yml",
                "config-version: 1\nmeta: {version: 1}\nmessages:\n  sold: 'My customized message %money%'\n",
            "prices.yml", "config-version: 1\nprices: {DIAMOND: '7.25'}\n");
    for (var entry : originals.entrySet()) {
      Path file = directory.resolve(entry.getKey());
      Files.createDirectories(file.getParent());
      Files.writeString(file, entry.getValue());
    }
    Settings loaded =
        new SettingsLoader(directory, getClass().getClassLoader(), ignored -> {}).load();
    assertEquals("SHOPGUIPLUS", loaded.integrations().text("prices.source"));
    assertTrue(loaded.integrations().flag("prices.apply-wellsell-multiplier"));
    assertEquals("trade", loaded.commands().name(CommandId.SELL));
    assertEquals("My customized message %money%", loaded.locale().text("sold"));
    assertEquals(1, loaded.prices().size());
    assertFalse(loaded.expansion().pricing().enabled());
    assertFalse(loaded.expansion().progression().enabled());
    assertFalse(loaded.expansion().lore().enabled());
    for (var entry : originals.entrySet()) {
      assertEquals(entry.getValue(), Files.readString(directory.resolve(entry.getKey())));
    }
    ConfigFiles files = new ConfigFiles(directory, getClass().getClassLoader());
    assertEquals(
        List.of("cashin"), files.load("commands.yml", Set.of()).strings("commands.sell.aliases"));
    assertEquals(
        "server.trade", files.load("permissions.yml", Set.of()).text("permissions.sell.node"));
  }

  @Test
  void progressGuiRejectsOverlappingAndOutOfBoundsLayout() throws Exception {
    ConfigFiles files = new ConfigFiles(directory, getClass().getClassLoader());
    var defaults = files.bundled("progress-gui.yml").values();
    for (List<Integer> slots : List.of(List.of(10, 10), List.of(-1), List.of(54), List.of(49))) {
      var data =
          DefaultMerger.merge(Map.of("gui", Map.of("category-slots", slots)), defaults, Set.of());
      assertThrows(
          IllegalArgumentException.class,
          () ->
              com.wellsetups.WellSell.gui.ProgressGuiSettings.load(
                  new ConfigTree(data), ignored -> {}));
    }
  }

  private static Map<CommandId, CommandNames.Definition> names() {
    Map<CommandId, CommandNames.Definition> map = new EnumMap<>(CommandId.class);
    for (CommandId id : CommandId.values()) {
      map.put(id, new CommandNames.Definition(id.key(), List.of()));
    }
    return map;
  }
}
