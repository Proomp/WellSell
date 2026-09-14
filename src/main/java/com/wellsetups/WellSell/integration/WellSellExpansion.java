package com.wellsetups.WellSell.integration;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

public final class WellSellExpansion extends PlaceholderExpansion {
  private final PlaceholderCache cache;
  private final String version;

  public WellSellExpansion(PlaceholderCache cache, String version) {
    this.cache = cache;
    this.version = version;
  }

  @Override
  public String getIdentifier() {
    return "wellsell";
  }

  @Override
  public String getAuthor() {
    return "WellSetups";
  }

  @Override
  public String getVersion() {
    return version;
  }

  @Override
  public boolean persist() {
    return true;
  }

  @Override
  public String onRequest(OfflinePlayer player, String parameter) {
    return cache.value(player == null ? null : player.getUniqueId(), parameter);
  }
}
