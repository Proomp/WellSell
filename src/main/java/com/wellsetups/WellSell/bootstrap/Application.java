package com.wellsetups.WellSell.bootstrap;

import com.wellsetups.WellSell.api.WellSellApi;
import com.wellsetups.WellSell.command.CommandId;
import com.wellsetups.WellSell.command.HistoryCommand;
import com.wellsetups.WellSell.command.OnlineDirectory;
import com.wellsetups.WellSell.command.PriceImportCommand;
import com.wellsetups.WellSell.command.WellSellCommand;
import com.wellsetups.WellSell.config.Settings;
import com.wellsetups.WellSell.config.SettingsLoader;
import com.wellsetups.WellSell.economy.EconomyBridge;
import com.wellsetups.WellSell.gui.SellGui;
import com.wellsetups.WellSell.integration.OptionalIntegrations;
import com.wellsetups.WellSell.integration.PlaceholderCache;
import com.wellsetups.WellSell.integration.ShopRegistry;
import com.wellsetups.WellSell.integration.UpdateChecker;
import com.wellsetups.WellSell.message.Messages;
import com.wellsetups.WellSell.platform.CommandRegistration;
import com.wellsetups.WellSell.platform.PlatformExecutor;
import com.wellsetups.WellSell.pricing.SalePricing;
import com.wellsetups.WellSell.sell.PlayerTransactions;
import com.wellsetups.WellSell.sell.SellService;
import com.wellsetups.WellSell.storage.JdbcHistory;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class Application implements AutoCloseable {
  private final JavaPlugin plugin;
  private final AtomicReference<Settings> settings = new AtomicReference<>();
  private final Set<Permission> permissions = new HashSet<>();
  private final List<WellSellCommand> commands = new ArrayList<>();
  private PlatformExecutor platform;
  private JdbcHistory storage;
  private OptionalIntegrations integrations;
  private CommandRegistration registration;
  private SellService selling;
  private SellGui gui;
  private com.wellsetups.WellSell.gui.ProgressGui progress;
  private ConfigurationReload reload;
  private Messages messages;
  private EconomyBridge economy;
  private Settings startup;

  public Application(JavaPlugin plugin) {
    this.plugin = plugin;
  }

  public void start() throws IOException {
    SettingsLoader loader =
        new SettingsLoader(
            plugin.getDataFolder().toPath(),
            plugin.getClass().getClassLoader(),
            plugin.getLogger()::warning);
    startup = loader.load();
    settings.set(startup);
    platform = new PlatformExecutor(plugin);
    messages =
        new Messages(settings::get, startup.commands(), plugin.getDescription().getVersion());
    integrations = new OptionalIntegrations(plugin);
    economy = integrations.economy(startup.integrations(), platform.folia());
    PlayerTransactions transactions = new PlayerTransactions();
    storage =
        new JdbcHistory(
            startup.storage(),
            plugin.getDataFolder().toPath(),
            plugin.getLogger(),
            transactions::quarantine);
    ShopRegistry shops =
        new ShopRegistry(
            plugin, platform.folia(), startup.integrations().flag("prices.allow-on-folia"));
    var statistics =
        new com.wellsetups.WellSell.storage.PlayerStatisticsCache(
            storage::statistics, storage::logFailure);
    SalePricing pricing =
        new SalePricing(
            startup.integrations(),
            shops::require,
            statistics,
            shops::enabled,
            new com.wellsetups.WellSell.pricing.CustomIdentities());
    selling =
        new SellService(
            settings::get,
            platform,
            economy,
            storage,
            messages,
            transactions,
            plugin.getLogger(),
            pricing,
            new com.wellsetups.WellSell.sell.SaleEvents(
                plugin.getServer().getPluginManager()::callEvent,
                () -> !org.bukkit.Bukkit.isPrimaryThread(),
                platform::global));
    gui = new SellGui(settings::get, selling, messages, platform);
    progress =
        new com.wellsetups.WellSell.gui.ProgressGui(settings::get, statistics, messages, platform);
    PlaceholderCache cache =
        new PlaceholderCache(
            com.wellsetups.WellSell.integration.RankingQueries.cache(
                storage,
                startup.integrations().integer("placeholderapi.top-positions", 1, 100),
                startup.integrations().integer("placeholderapi.cache-seconds", 1, 300)),
            startup.integrations().integer("placeholderapi.top-positions", 1, 100),
            platform,
            settings::get,
            player -> pricing.multiplier(player, settings.get()),
            statistics);
    selling.onCompleted(statistics::refresh);
    OnlineDirectory directory = new OnlineDirectory();
    startup.permissions().register(plugin.getServer().getPluginManager(), permissions);
    HistoryCommand history =
        new HistoryCommand(storage, settings::get, platform, messages, plugin.getLogger());
    registration = new CommandRegistration(plugin);
    reload =
        new ConfigurationReload(
            plugin,
            loader,
            settings,
            platform,
            messages,
            permissions,
            commands,
            player -> {
              gui.invalidate(player);
              progress.invalidate(player);
              integrations.invalidateLore(player);
            });
    PriceImportCommand priceImport =
        new PriceImportCommand(
            plugin, platform, messages, settings::get, startup.integrations(), shops::require);
    for (CommandId id : CommandId.values()) {
      WellSellCommand command =
          new WellSellCommand(
              id,
              plugin,
              settings::get,
              messages,
              selling,
              gui,
              history,
              directory,
              reload::reload,
              this::info,
              priceImport,
              progress,
              new com.wellsetups.WellSell.command.LeaderboardCommand(
                  storage, settings::get, messages, platform),
              new com.wellsetups.WellSell.command.MigrationCommand(
                  settings::get,
                  storage,
                  selling,
                  platform,
                  messages,
                  startup.storage().flag("migration-enabled")));
      registration.register(command);
      commands.add(command);
    }
    var manager = plugin.getServer().getPluginManager();
    manager.registerEvents(gui, plugin);
    manager.registerEvents(progress, plugin);
    manager.registerEvents(directory, plugin);
    manager.registerEvents(cache, plugin);
    UpdateChecker updates =
        new UpdateChecker(plugin, platform, settings::get, messages, startup.integrations());
    manager.registerEvents(updates, plugin);
    plugin
        .getServer()
        .getServicesManager()
        .register(
            WellSellApi.class,
            new PublicApi(settings::get, selling, platform, storage),
            plugin,
            ServicePriority.Normal);
    storage
        .initialize()
        .whenComplete(
            (ignored, failure) -> {
              if (failure != null) {
                storage.logFailure(
                    "Storage initialization failed; selling is disabled until a restart resolves it",
                    failure);
              }
            });
    integrations.start(startup.integrations(), cache);
    integrations.startLore(settings::get, platform, pricing, messages);
    for (Player player : plugin.getServer().getOnlinePlayers()) {
      platform.player(player, () -> cache.add(player), () -> {});
    }
    updates.start();
    messages.send(plugin.getServer().getConsoleSender(), "startup", infoValues());
  }

  private Map<String, String> infoValues() {
    return Map.of(
        "platform",
        plugin.getServer().getName() + " " + plugin.getServer().getBukkitVersion(),
        "folia",
        Boolean.toString(platform.folia()),
        "economy",
        economy.name(),
        "storage",
        startup.storage().text("type") + (storage.ready() ? " ✓" : " …"),
        "prices",
        Integer.toString(settings.get().prices().size()),
        "price_source",
        selling.pricing().description(settings.get()),
        "locale",
        settings.get().general().text("locale"));
  }

  private void info(CommandSender sender) {
    messages.send(sender, "info", infoValues());
  }

  @Override
  public void close() {
    try {
      stopServerServices();
    } finally {
      if (platform != null) {
        platform.close();
      }
      if (storage != null) {
        storage.close();
      }
    }
  }

  private void stopServerServices() {
    if (selling != null) {
      selling.close();
    }
    // GUI originals remain in player storage; shutdown cannot strand items in GUI custody.
    // Live plugin unloading is not supported by Folia; never touch foreign regions here.
    if (platform != null && !platform.folia() && gui != null) {
      for (Player player : plugin.getServer().getOnlinePlayers()) {
        gui.invalidate(player);
      }
    }
    if (integrations != null) {
      integrations.close();
    }
    if (registration != null) {
      registration.close();
    }
    plugin.getServer().getServicesManager().unregisterAll(plugin);
    permissions.forEach(
        permission -> plugin.getServer().getPluginManager().removePermission(permission));
  }
}
