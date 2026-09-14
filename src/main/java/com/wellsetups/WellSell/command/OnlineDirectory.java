package com.wellsetups.WellSell.command;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Immutable names copied by join events; completion never touches another region's player state.
 */
public final class OnlineDirectory implements Listener {
  private final ConcurrentHashMap<UUID, String> names = new ConcurrentHashMap<>();

  @EventHandler
  public void join(PlayerJoinEvent event) {
    add(event.getPlayer());
  }

  public void add(Player player) {
    names.put(player.getUniqueId(), player.getName());
  }

  @EventHandler
  public void quit(PlayerQuitEvent event) {
    names.remove(event.getPlayer().getUniqueId());
  }

  public List<String> names() {
    return names.values().stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
  }
}
