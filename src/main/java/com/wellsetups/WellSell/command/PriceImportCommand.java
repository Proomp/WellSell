package com.wellsetups.WellSell.command;

import com.wellsetups.WellSell.config.ConfigTree;
import com.wellsetups.WellSell.config.Settings;
import com.wellsetups.WellSell.message.Messages;
import com.wellsetups.WellSell.platform.PlatformExecutor;
import com.wellsetups.WellSell.pricing.PriceImportStore;
import com.wellsetups.WellSell.pricing.PriceSource;
import com.wellsetups.WellSell.pricing.ShopPrices;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public final class PriceImportCommand {
  private static final int BATCH_SIZE = 24;
  private final Plugin plugin;
  private final PlatformExecutor platform;
  private final Messages messages;
  private final Supplier<Settings> settings;
  private final Function<PriceSource, ShopPrices> shops;
  private final PriceImportStore files;
  private final boolean enabled;
  private final AtomicBoolean importing = new AtomicBoolean();
  private final List<Material> materials =
      Arrays.stream(Material.values())
          .filter(
              material ->
                  material.isItem() && !material.isAir() && !material.name().startsWith("LEGACY_"))
          .toList();

  public PriceImportCommand(
      Plugin plugin,
      PlatformExecutor platform,
      Messages messages,
      Supplier<Settings> settings,
      ConfigTree config,
      Function<PriceSource, ShopPrices> shops) {
    this.plugin = plugin;
    this.platform = platform;
    this.messages = messages;
    this.settings = settings;
    this.shops = shops;
    enabled = config.flag("prices.import-enabled");
    files = new PriceImportStore(plugin.getDataFolder().toPath());
  }

  public boolean permitted(CommandSender sender) {
    return settings.get().permissions().has(sender, PermissionId.ADMIN)
        && settings.get().permissions().has(sender, PermissionId.PRICE_IMPORT);
  }

  public void execute(CommandSender sender, String[] args) {
    if (!permitted(sender)) {
      messages.send(sender, "no-permission");
      return;
    }
    if (!enabled) {
      messages.send(sender, "price-import-disabled");
      return;
    }
    if (args.length != 3 || !args[1].equalsIgnoreCase("import")) {
      messages.send(sender, "price-import-usage");
      return;
    }
    PriceSource source;
    try {
      source = PriceSource.parse(args[2]);
      if (source == PriceSource.CONFIG) {
        throw new IllegalArgumentException("Select an external shop");
      }
    } catch (IllegalArgumentException failure) {
      messages.send(sender, "price-import-usage");
      return;
    }
    if (platform.folia() && !(sender instanceof Player)) {
      messages.send(sender, "player-only");
      return;
    }
    begin(sender, source);
  }

  private void begin(CommandSender sender, PriceSource source) {
    if (!importing.compareAndSet(false, true)) {
      messages.send(sender, "price-import-busy");
      return;
    }
    messages.send(sender, "price-import-start", Map.of("source", source.name()));
    platform
        .async(
            () -> {
              try {
                return files.snapshot();
              } catch (IOException failure) {
                throw new UncheckedIOException(failure);
              }
            })
        .whenComplete(
            (original, failure) -> {
              if (failure != null) {
                fail(sender, failure);
              } else {
                dispatch(sender, () -> batch(sender, source, original, new LinkedHashMap<>(), 0));
              }
            });
  }

  private void batch(
      CommandSender sender,
      PriceSource source,
      byte[] original,
      Map<String, BigDecimal> prices,
      int index) {
    if (!plugin.isEnabled() || !permitted(sender)) {
      importing.set(false);
      return;
    }
    try {
      ShopPrices shop = shops.apply(source);
      int end = Math.min(materials.size(), index + BATCH_SIZE);
      for (int offset = index; offset < end; offset++) {
        Material material = materials.get(offset);
        shop.basePrice(material).ifPresent(value -> prices.put(material.name(), value));
      }
      if (end < materials.size()) {
        dispatch(sender, () -> batch(sender, source, original, prices, end));
      } else if (prices.isEmpty()) {
        importing.set(false);
        messages.send(sender, "price-import-empty");
      } else {
        save(sender, source, original, Map.copyOf(prices));
      }
    } catch (RuntimeException | LinkageError failure) {
      fail(sender, failure);
    }
  }

  private void save(
      CommandSender sender, PriceSource source, byte[] original, Map<String, BigDecimal> prices) {
    platform
        .async(
            () -> {
              try {
                return files.save(original, prices);
              } catch (IOException failure) {
                throw new UncheckedIOException(failure);
              }
            })
        .whenComplete(
            (backup, failure) -> {
              importing.set(false);
              if (failure != null) {
                fail(sender, failure);
              } else {
                dispatch(
                    sender,
                    () ->
                        messages.send(
                            sender,
                            "price-import-done",
                            Map.of(
                                "source",
                                source.name(),
                                "amount",
                                Integer.toString(prices.size()),
                                "backup",
                                backup)));
              }
            });
  }

  private void fail(CommandSender sender, Throwable failure) {
    importing.set(false);
    plugin
        .getLogger()
        .log(
            Level.WARNING,
            "Price import failed; inspect the original/backup before retrying",
            failure);
    dispatch(sender, () -> messages.send(sender, "price-import-error"));
  }

  private void dispatch(CommandSender sender, Runnable action) {
    if (sender instanceof Player player) {
      platform.player(player, action, () -> importing.set(false));
    } else {
      platform.global(action);
    }
  }
}
