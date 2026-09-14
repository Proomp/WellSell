package com.wellsetups.WellSell.bootstrap;

import com.wellsetups.WellSell.api.WellSellApi;
import com.wellsetups.WellSell.config.Settings;
import com.wellsetups.WellSell.platform.PlatformExecutor;
import com.wellsetups.WellSell.sell.BukkitInventoryAccess;
import com.wellsetups.WellSell.sell.SellPlan;
import com.wellsetups.WellSell.sell.SellService;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.function.Supplier;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

final class PublicApi implements WellSellApi {
  private final Supplier<Settings> settings;
  private final SellService selling;
  private final PlatformExecutor platform;
  private final com.wellsetups.WellSell.storage.JdbcHistory storage;

  PublicApi(
      Supplier<Settings> settings,
      SellService selling,
      PlatformExecutor platform,
      com.wellsetups.WellSell.storage.JdbcHistory storage) {
    this.settings = settings;
    this.selling = selling;
    this.platform = platform;
    this.storage = storage;
  }

  @Override
  public Optional<BigDecimal> unitPrice(Material material) {
    return selling.pricing().localOnly(settings.get())
        ? settings.get().prices().unitPrice(material.name())
        : Optional.empty();
  }

  @Override
  public boolean isSellable(ItemStack item) {
    Settings snapshot = settings.get();
    return item != null
        && !item.getType().isAir()
        && com.wellsetups.WellSell.pricing.ContainerReader.kind(item)
            == com.wellsetups.WellSell.pricing.ContainerReader.Kind.NONE
        && item.getAmount() > 0
        && item.getAmount() <= 127
        && (snapshot.general().flag("sell.allow-metadata") || !item.hasItemMeta())
        && unitPrice(item.getType()).isPresent();
  }

  @Override
  public Optional<BigDecimal> unitPrice(Player player, ItemStack item) {
    platform.requireOwner(player);
    if (item == null || item.getType().isAir() || item.getAmount() < 1) {
      return Optional.empty();
    }
    ItemStack single = item.clone();
    single.setAmount(1);
    SellPlan plan =
        selling.quote(
            player,
            new BukkitInventoryAccess(
                player, java.util.Map.of(0, new BukkitInventoryAccess.Selection(single, 1))));
    return plan.empty() ? Optional.empty() : Optional.of(plan.money());
  }

  @Override
  public BigDecimal multiplier(Player player) {
    platform.requireOwner(player);
    return selling.pricing().multiplier(player, settings.get());
  }

  @Override
  public Quote quoteInventory(Player player) {
    platform.requireOwner(player);
    SellPlan plan = selling.quote(player, BukkitInventoryAccess.capture(player, false, 0));
    return new Quote(plan.amount(), plan.money(), plan.multiplier());
  }

  @Override
  public void sellInventory(Player player) {
    platform.requireOwner(player);
    selling.sell(player, BukkitInventoryAccess.capture(player, false, 0), "INVENTORY");
  }

  @Override
  public void sellHand(Player player, int amount) {
    platform.requireOwner(player);
    if (amount < 0) {
      throw new IllegalArgumentException("Amount must be zero (all) or positive");
    }
    selling.sell(player, BukkitInventoryAccess.capture(player, true, amount), "HAND");
  }

  @Override
  public com.wellsetups.WellSell.api.Valuation valueItem(Player player, ItemStack item) {
    platform.requireOwner(player);
    var selected = new BukkitInventoryAccess.Selection(item, item.getAmount());
    return valuation(
        selling.quote(player, new BukkitInventoryAccess(player, java.util.Map.of(0, selected))));
  }

  @Override
  public com.wellsetups.WellSell.api.Valuation valueInventory(Player player) {
    platform.requireOwner(player);
    return valuation(selling.quote(player, BukkitInventoryAccess.capture(player, false, 0)));
  }

  private static com.wellsetups.WellSell.api.Valuation valuation(SellPlan plan) {
    return new com.wellsetups.WellSell.api.Valuation(
        plan.amount(), plan.money(), plan.multiplier(), plan.summary());
  }

  @Override
  public java.util.List<com.wellsetups.WellSell.api.CategoryDefinition> categories() {
    return settings.get().expansion().categories().categories().values().stream()
        .map(PublicApi::definition)
        .toList();
  }

  @Override
  public Optional<com.wellsetups.WellSell.api.CategoryDefinition> category(
      Player player, ItemStack item) {
    platform.requireOwner(player);
    return settings
        .get()
        .expansion()
        .categories()
        .resolve(
            selling.pricing().identities().resolve(item, settings.get().expansion().custom()),
            item.getType().name())
        .map(PublicApi::definition);
  }

  private static com.wellsetups.WellSell.api.CategoryDefinition definition(
      com.wellsetups.WellSell.category.CategoryCatalog.Category category) {
    return new com.wellsetups.WellSell.api.CategoryDefinition(
        category.id(), category.displayName(), category.icon(), category.identities());
  }

  @Override
  public java.util.concurrent.CompletableFuture<com.wellsetups.WellSell.api.PlayerStatistics>
      statistics(java.util.UUID player) {
    return storage.statistics(player);
  }

  @Override
  public java.util.concurrent.CompletableFuture<com.wellsetups.WellSell.api.ProgressionView>
      progression(java.util.UUID player, String category) {
    Settings snapshot = settings.get();
    if (!snapshot.expansion().categories().categories().containsKey(category)) {
      throw new IllegalArgumentException("Unknown category");
    }
    return storage
        .statistics(player)
        .thenApply(
            stats ->
                snapshot
                    .expansion()
                    .progression()
                    .view(
                        category,
                        stats
                            .categories()
                            .getOrDefault(
                                category, com.wellsetups.WellSell.api.CategoryStatistics.empty())));
  }

  @Override
  public java.util.concurrent.CompletableFuture<com.wellsetups.WellSell.api.Leaderboard>
      leaderboard(
          com.wellsetups.WellSell.api.Leaderboard.Mode mode, String category, int page, int size) {
    if (category != null
        && !settings.get().expansion().categories().categories().containsKey(category)) {
      throw new IllegalArgumentException("Unknown category");
    }
    return storage.leaderboard(
        java.util.Objects.requireNonNull(mode),
        category,
        new com.wellsetups.WellSell.storage.Page(page, size));
  }

  @Override
  public AutoCloseable registerItemResolver(
      org.bukkit.plugin.Plugin owner,
      int priority,
      com.wellsetups.WellSell.api.CustomItemResolver resolver) {
    java.util.Objects.requireNonNull(owner);
    return selling.pricing().identities().register(priority, owner::isEnabled, resolver);
  }
}
