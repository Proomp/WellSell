package com.wellsetups.WellSell.lore;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.wellsetups.WellSell.command.PermissionId;
import com.wellsetups.WellSell.config.Settings;
import com.wellsetups.WellSell.platform.PlatformExecutor;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.logging.Level;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

public final class WorthLore implements Listener, AutoCloseable {
  private static final class Entry {
    private final Player player;
    private volatile Map<Integer, LoreRenderer.Pair> items = Map.of();
    private volatile Settings snapshot;
    private volatile long expires;
    private final AtomicLong nextRefresh = new AtomicLong();

    private Entry(Player player) {
      this.player = player;
    }
  }

  private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();
  private final Plugin plugin;
  private final Supplier<Settings> settings;
  private final PlatformExecutor platform;
  private final LoreRenderer renderer;
  private final LorePackets packets;
  private final AtomicLong nextWarning = new AtomicLong();
  private volatile boolean closed;

  public WorthLore(
      Plugin plugin,
      Supplier<Settings> settings,
      PlatformExecutor platform,
      LoreRenderer renderer) {
    this.plugin = plugin;
    this.settings = settings;
    this.platform = platform;
    this.renderer = renderer;
    var version = PacketEvents.getAPI().getServerManager().getVersion();
    if (!version.isNewerThanOrEquals(ServerVersion.V_1_20_5)) {
      throw new IllegalArgumentException(
          "Worth lore requires Minecraft 1.20.5+ native protocol clients");
    }
    packets =
        new LorePackets(this::display, version.toClientVersion().getProtocolVersion(), this::warn);
    PacketEvents.getAPI().getEventManager().registerListener(packets);
    plugin.getServer().getPluginManager().registerEvents(this, plugin);
  }

  public void add(Player player) {
    entries.put(player.getUniqueId(), new Entry(player));
  }

  @EventHandler
  public void join(PlayerJoinEvent event) {
    add(event.getPlayer());
  }

  @EventHandler
  public void quit(PlayerQuitEvent event) {
    entries.remove(event.getPlayer().getUniqueId());
  }

  @EventHandler(ignoreCancelled = true)
  public void mode(PlayerGameModeChangeEvent event) {
    Entry entry = entries.get(event.getPlayer().getUniqueId());
    if (entry != null) {
      entry.items = Map.of();
      entry.snapshot = null;
      entry.nextRefresh.set(0);
      request(entry);
    }
  }

  public void invalidate(Player player) {
    Entry entry = entries.get(player.getUniqueId());
    if (entry != null) {
      entry.items = Map.of();
      entry.snapshot = null;
      entry.nextRefresh.set(0);
      request(entry);
    }
  }

  private ItemStack display(UUID player, int slot, ItemStack original) {
    Entry entry = entries.get(player);
    if (entry == null || closed) {
      return original;
    }
    Settings current = settings.get();
    if (!current.expansion().lore().enabled() || original == null || original.isEmpty()) {
      return original;
    }
    boolean fresh = entry.snapshot == current && System.nanoTime() < entry.expires;
    LoreRenderer.Pair pair = entry.items.get(slot);
    if (!fresh || pair == null || !pair.original().equals(original)) {
      request(entry);
    }
    if (!current.expansion().lore().enabled()
        || !fresh
        || pair == null
        || !pair.original().equals(original)) {
      return original;
    }
    return pair.display().copy();
  }

  private void request(Entry entry) {
    long now = System.nanoTime();
    long previous = entry.nextRefresh.get();
    if (closed || now < previous || !entry.nextRefresh.compareAndSet(previous, Long.MAX_VALUE)) {
      return;
    }
    try {
      platform.player(entry.player, () -> refresh(entry), () -> entry.nextRefresh.set(0));
    } catch (RuntimeException problem) {
      entry.nextRefresh.set(0);
      warn(problem);
    }
  }

  private void refresh(Entry entry) {
    try {
      if (closed || entries.get(entry.player.getUniqueId()) != entry) {
        return;
      }
      platform.requireOwner(entry.player);
      Settings snapshot = settings.get();
      Map<Integer, LoreRenderer.Pair> items = new HashMap<>();
      if (allowed(entry.player, snapshot)) {
        prepare(entry.player, snapshot, items);
      }
      entry.items = Map.copyOf(items);
      entry.expires =
          System.nanoTime() + snapshot.expansion().lore().cacheSeconds() * 1_000_000_000L;
      entry.snapshot = snapshot;
      entry.player.updateInventory();
    } catch (RuntimeException | LinkageError problem) {
      entry.items = Map.of();
      warn(problem);
    } finally {
      entry.nextRefresh.set(System.nanoTime() + 1_000_000_000L);
    }
  }

  private void prepare(Player player, Settings snapshot, Map<Integer, LoreRenderer.Pair> items) {
    Map<org.bukkit.inventory.ItemStack, LoreRenderer.Pair> duplicates = new HashMap<>();
    for (int slot = 0; slot < 36; slot++) {
      var item = player.getInventory().getItem(slot);
      if (item != null
          && !item.getType().isAir()
          && item.getAmount() > 0
          && item.getAmount() <= 127) {
        items.put(
            slot,
            duplicates.computeIfAbsent(
                item.clone(), key -> renderer.prepare(player, key, snapshot)));
      }
    }
  }

  private static boolean allowed(Player player, Settings settings) {
    return settings.expansion().lore().enabled()
        && settings.permissions().has(player, PermissionId.WORTH_LORE)
        && (player.getGameMode() == GameMode.SURVIVAL
            || player.getGameMode() == GameMode.ADVENTURE);
  }

  private void warn(Throwable problem) {
    long now = System.nanoTime();
    long before = nextWarning.get();
    if (now >= before && nextWarning.compareAndSet(before, now + 60_000_000_000L)) {
      plugin.getLogger().log(Level.WARNING, "Optional worth-lore packet handling failed", problem);
    }
  }

  @Override
  public void close() {
    closed = true;
    PacketEvents.getAPI().getEventManager().unregisterListener(packets);
    HandlerList.unregisterAll(this);
    entries.clear();
  }
}
