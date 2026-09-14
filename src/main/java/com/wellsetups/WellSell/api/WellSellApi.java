package com.wellsetups.WellSell.api;

import java.math.BigDecimal;
import java.util.Optional;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Obtain via Bukkit ServicesManager. Player methods require the player's owning thread. */
public interface WellSellApi {
  record Quote(int amount, BigDecimal money, BigDecimal multiplier) {}

  /** Thread safe. Local CONFIG rate only; empty with live shop pricing, which needs a player. */
  Optional<BigDecimal> unitPrice(Material material);

  /**
   * Quote one item for this player, including configured modifiers and rounding. Owner thread only.
   */
  Optional<BigDecimal> unitPrice(Player player, ItemStack item);

  /** Local CONFIG eligibility; false with live pricing. Never mutates the caller-owned stack. */
  boolean isSellable(ItemStack item);

  BigDecimal multiplier(Player player);

  Quote quoteInventory(Player player);

  /** Starts the normal asynchronous audited flow, with the same permissions as the command. */
  void sellInventory(Player player);

  /** Amount zero means the complete held stack. */
  void sellHand(Player player, int amount);

  /** Full immutable quote including provider/category attribution. Owner thread only. */
  Valuation valueItem(Player player, ItemStack item);

  Valuation valueInventory(Player player);

  java.util.List<CategoryDefinition> categories();

  Optional<CategoryDefinition> category(Player player, ItemStack item);

  java.util.concurrent.CompletableFuture<PlayerStatistics> statistics(java.util.UUID player);

  java.util.concurrent.CompletableFuture<ProgressionView> progression(
      java.util.UUID player, String category);

  /** Null category means global. Page size is bounded to 1..50. */
  java.util.concurrent.CompletableFuture<Leaderboard> leaderboard(
      Leaderboard.Mode mode, String category, int page, int size);

  /**
   * Higher priorities run first; close registration on disable. Callbacks run on player owner
   * threads.
   */
  AutoCloseable registerItemResolver(
      org.bukkit.plugin.Plugin owner, int priority, CustomItemResolver resolver);
}
