package com.wellsetups.WellSell.config;

import com.wellsetups.WellSell.category.CategoryCatalog;
import com.wellsetups.WellSell.category.Progression;
import com.wellsetups.WellSell.command.CommandNames;
import com.wellsetups.WellSell.command.PermissionService;
import com.wellsetups.WellSell.gui.GuiSettings;
import com.wellsetups.WellSell.message.LocaleCatalog;
import com.wellsetups.WellSell.message.SoundEffect;
import com.wellsetups.WellSell.pricing.ConfiguredPrices;
import com.wellsetups.WellSell.pricing.MoneyPolicy;
import com.wellsetups.WellSell.pricing.Multipliers;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import org.bukkit.Material;

public final class SettingsLoader {
  private final ConfigFiles files;
  private final Consumer<String> warning;

  public SettingsLoader(Path directory, ClassLoader resources, Consumer<String> warning) {
    files = new ConfigFiles(directory, resources);
    this.warning = warning;
  }

  public Settings load() throws IOException {
    ConfigTree general = files.load("config.yml", Set.of("multipliers.groups", "delivery"));
    ConfigTree storage = files.load("storage.yml", Set.of());
    ConfigTree integrations = files.load("integrations.yml", Set.of());
    validate(general, storage, integrations);
    CommandNames commands = CommandNames.load(files.load("commands.yml", Set.of()));
    PermissionService permissions = new PermissionService(files.load("permissions.yml", Set.of()));
    ConfiguredPrices prices =
        new ConfiguredPrices(
            files.load("prices.yml", Set.of("prices")).section("prices"),
            material -> {
              Material match = Material.matchMaterial(material);
              return match != null && match.isItem() && !match.isAir();
            },
            warning);
    MoneyPolicy money =
        new MoneyPolicy(
            general.integer("money.decimal-places", 0, 6),
            RoundingMode.valueOf(general.text("money.rounding").toUpperCase(Locale.ROOT)),
            general.decimal("money.maximum-sale"));
    List<Multipliers.Group> groups = new ArrayList<>();
    general
        .section("multipliers.groups")
        .keySet()
        .forEach(
            group -> {
              String path = "multipliers.groups." + group;
              try {
                groups.add(
                    new Multipliers.Group(
                        general.text(path + ".permission"), general.decimal(path + ".multiplier")));
              } catch (IllegalArgumentException failure) {
                warning.accept("Ignored " + path + ": " + failure.getMessage());
              }
            });
    Multipliers multipliers =
        new Multipliers(
            general.flag("multipliers.enabled"),
            Multipliers.Selection.valueOf(
                general.text("multipliers.selection").toUpperCase(Locale.ROOT).replace('-', '_')),
            groups);
    ConfigTree english = files.load("lang/en_US.yml", Set.of());
    ConfigTree selected = files.locale(general.text("locale"));
    ConfigTree bundled = files.bundled("lang/en_US.yml");
    validateLocale(selected, bundled);
    LocaleCatalog locale = new LocaleCatalog(selected, english, bundled, warning);
    ConfigTree guiConfig = files.load("gui.yml", Set.of());
    boolean guiEnabled = guiConfig.flag("gui.enabled");
    GuiSettings gui;
    try {
      gui = GuiSettings.load(guiConfig, warning);
    } catch (IllegalArgumentException | ArithmeticException failure) {
      warning.accept("Invalid GUI configuration; using bundled layout: " + failure.getMessage());
      gui = GuiSettings.load(files.bundled("gui.yml"), warning).withEnabled(guiEnabled);
    }
    DateTimeFormatter date =
        DateTimeFormatter.ofPattern(general.text("history.date-pattern"))
            .withZone(ZoneId.of(general.text("history.timezone")));
    return new Settings(
        general,
        storage,
        integrations,
        commands,
        permissions,
        prices,
        money,
        multipliers,
        locale,
        gui,
        date,
        SoundEffect.load(general, "sounds.success", warning),
        SoundEffect.load(general, "sounds.error", warning),
        new ExpansionSettings(
            new CategoryCatalog(
                files.load("categories.yml", Set.of("categories")),
                name -> {
                  Material type = Material.matchMaterial(name);
                  return type != null && type.isItem() && !type.isAir();
                },
                warning),
            new Progression(files.load("progression.yml", Set.of("categories")), warning),
            com.wellsetups.WellSell.gui.ProgressGuiSettings.load(
                files.load("progress-gui.yml", Set.of()), warning),
            com.wellsetups.WellSell.pricing.ContainerPolicy.load(
                files.load("containers.yml", Set.of())),
            com.wellsetups.WellSell.pricing.PricingSettings.load(
                files.load("pricing.yml", Set.of())),
            com.wellsetups.WellSell.pricing.CustomPrices.load(
                files.load("custom-items.yml", Set.of("prices")), warning),
            com.wellsetups.WellSell.lore.LoreSettings.load(
                files.load("worth-lore.yml", Set.of()))));
  }

  private void validateLocale(ConfigTree selected, ConfigTree bundled) {
    if (!String.valueOf(selected.get("meta.version")).equals("1")) {
      warning.accept(
          "Selected locale is absent or meta.version differs from 1; English fallback is enabled.");
    }
    selected.section("messages").keySet().stream()
        .filter(key -> !bundled.section("messages").containsKey(key))
        .forEach(key -> warning.accept("Unknown locale message: " + key));
  }

  private static void validate(ConfigTree general, ConfigTree storage, ConfigTree integrations) {
    general.flag("leaderboard.enabled");
    general.integer("leaderboard.page-size", 1, 50);
    com.wellsetups.WellSell.api.Leaderboard.Mode.valueOf(general.text("leaderboard.default-mode"));
    general.flag("sell.allow-hand");
    general.flag("sell.allow-inventory");
    general.flag("sell.allow-metadata");
    general.integer("sell.cooldown-milliseconds", 0, 60_000);
    general.integer("history.page-size", 1, 50);
    general.flag("history.category-details");
    new DecimalFormat(general.text("money.pattern")).format(BigDecimal.ONE);
    for (Object mode : general.section("delivery").values()) {
      if (!Set.of("CHAT", "ACTION_BAR", "TITLE", "SUBTITLE", "NONE").contains(mode.toString())) {
        throw new IllegalArgumentException("Invalid message delivery: " + mode);
      }
    }
    storage.flag("migration-enabled");
    if (!Set.of("SQLITE", "MARIADB").contains(storage.text("type"))) {
      throw new IllegalArgumentException("storage.type must be SQLITE or MARIADB");
    }
    if (!storage.text("sqlite-file").matches("[a-zA-Z0-9_-]+\\.db")) {
      throw new IllegalArgumentException("sqlite-file must be a simple .db filename");
    }
    if (!storage.text("mariadb.url").startsWith("jdbc:mariadb://")) {
      throw new IllegalArgumentException("Expected a jdbc:mariadb:// URL");
    }
    for (String flag :
        List.of(
            "vault.enabled",
            "vault.allow-on-folia",
            "placeholderapi.enabled",
            "bstats.enabled",
            "update-checker.enabled",
            "update-checker.notify-console",
            "update-checker.notify-admins")) {
      integrations.flag(flag);
    }
    integrations.integer("placeholderapi.top-positions", 1, 100);
    integrations.integer("placeholderapi.cache-seconds", 1, 300);
    integrations.flag("prices.import-enabled");
    integrations.flag("prices.apply-wellsell-multiplier");
    integrations.flag("prices.allow-on-folia");
    com.wellsetups.WellSell.pricing.PriceSource.parse(integrations.text("prices.source"));
  }
}
