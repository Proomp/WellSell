package com.wellsetups.WellSell.integration;

import com.wellsetups.WellSell.config.ConfigTree;
import com.wellsetups.WellSell.economy.EconomyBridge;
import java.util.logging.Level;
import org.bstats.bukkit.Metrics;
import org.bukkit.plugin.Plugin;

public final class OptionalIntegrations implements AutoCloseable {
  private static final int BSTATS_SERVICE_ID = 34018;
  private final Plugin plugin;
  private Metrics metrics;
  private com.wellsetups.WellSell.lore.WorthLore lore;
  private Runnable unregisterExpansion = () -> {};

  public OptionalIntegrations(Plugin plugin) {
    this.plugin = plugin;
  }

  public EconomyBridge economy(ConfigTree config, boolean folia) {
    if (!config.flag("vault.enabled")
        || folia && !config.flag("vault.allow-on-folia")
        || !plugin.getServer().getPluginManager().isPluginEnabled("Vault")) {
      return EconomyBridge.unavailable();
    }
    try {
      return new VaultEconomy(plugin.getServer().getServicesManager());
    } catch (LinkageError failure) {
      plugin
          .getLogger()
          .log(
              Level.WARNING, "Vault API could not link; install a compatible Vault build", failure);
      return EconomyBridge.unavailable();
    }
  }

  public void start(ConfigTree config, PlaceholderCache cache) {
    if (config.flag("placeholderapi.enabled")
        && plugin.getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
      try {
        WellSellExpansion expansion =
            new WellSellExpansion(cache, plugin.getDescription().getVersion());
        if (expansion.register()) {
          unregisterExpansion = expansion::unregister;
        } else {
          plugin
              .getLogger()
              .warning(
                  "PlaceholderAPI identifier 'wellsell' is already registered; expansion unavailable");
        }
      } catch (LinkageError | RuntimeException failure) {
        plugin
            .getLogger()
            .log(Level.WARNING, "Optional PlaceholderAPI integration failed", failure);
      }
    }
    if (config.flag("bstats.enabled")) {
      try {
        metrics = new Metrics(plugin, BSTATS_SERVICE_ID);
      } catch (LinkageError | RuntimeException failure) {
        plugin.getLogger().log(Level.WARNING, "Optional bStats initialization failed", failure);
      }
    }
  }

  public void startLore(
      java.util.function.Supplier<com.wellsetups.WellSell.config.Settings> settings,
      com.wellsetups.WellSell.platform.PlatformExecutor platform,
      com.wellsetups.WellSell.pricing.SalePricing pricing,
      com.wellsetups.WellSell.message.Messages messages) {
    if (!plugin.getServer().getPluginManager().isPluginEnabled("packetevents")) {
      if (settings.get().expansion().lore().enabled()) {
        plugin
            .getLogger()
            .warning("Worth lore is enabled but PacketEvents is unavailable; display disabled");
      }
      return;
    }
    try {
      lore =
          new com.wellsetups.WellSell.lore.WorthLore(
              plugin,
              settings,
              platform,
              new com.wellsetups.WellSell.lore.LoreRenderer(pricing, messages));
      for (var player : plugin.getServer().getOnlinePlayers()) {
        platform.player(player, () -> lore.add(player), () -> {});
      }
    } catch (LinkageError | RuntimeException failure) {
      plugin
          .getLogger()
          .log(Level.WARNING, "Optional worth lore unavailable; selling remains enabled", failure);
    }
  }

  public void invalidateLore(org.bukkit.entity.Player player) {
    if (lore != null) {
      lore.invalidate(player);
    }
  }

  @Override
  public void close() {
    try {
      if (lore != null) {
        lore.close();
      }
      unregisterExpansion.run();
    } catch (RuntimeException | LinkageError failure) {
      plugin.getLogger().log(Level.WARNING, "Optional expansion shutdown failed", failure);
    } finally {
      if (metrics != null) {
        metrics.shutdown();
      }
    }
  }
}
