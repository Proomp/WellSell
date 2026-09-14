package com.wellsetups.WellSell.command;

import com.wellsetups.WellSell.config.Settings;
import com.wellsetups.WellSell.gui.SellGui;
import com.wellsetups.WellSell.message.Messages;
import com.wellsetups.WellSell.pricing.PriceUnavailableException;
import com.wellsetups.WellSell.sell.BukkitInventoryAccess;
import com.wellsetups.WellSell.sell.SellService;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public final class WellSellCommand extends Command implements PluginIdentifiableCommand {
  private final CommandId id;
  private final Plugin plugin;
  private final Supplier<Settings> settings;
  private final Messages messages;
  private final SellService selling;
  private final SellGui gui;
  private final com.wellsetups.WellSell.gui.ProgressGui progress;
  private final HistoryCommand history;
  private final LeaderboardCommand top;
  private final OnlineDirectory directory;
  private final Consumer<CommandSender> reload;
  private final Consumer<CommandSender> info;
  private final PriceImportCommand priceImport;
  private final MigrationCommand migration;

  public WellSellCommand(
      CommandId id,
      Plugin plugin,
      Supplier<Settings> settings,
      Messages messages,
      SellService selling,
      SellGui gui,
      HistoryCommand history,
      OnlineDirectory directory,
      Consumer<CommandSender> reload,
      Consumer<CommandSender> info,
      PriceImportCommand priceImport,
      com.wellsetups.WellSell.gui.ProgressGui progress,
      LeaderboardCommand top,
      MigrationCommand migration) {
    super(settings.get().commands().name(id));
    this.id = id;
    this.plugin = plugin;
    this.settings = settings;
    this.messages = messages;
    this.selling = selling;
    this.gui = gui;
    this.progress = progress;
    this.history = history;
    this.top = top;
    this.directory = directory;
    this.reload = reload;
    this.info = info;
    this.priceImport = priceImport;
    this.migration = migration;
    setAliases(settings.get().commands().commands().get(id).aliases());
    refreshPermission();
  }

  public void refreshPermission() {
    // Help remains discoverable to ordinary users; info/reload have separate checks.
    setPermission(
        id == CommandId.ADMIN ? null : settings.get().permissions().node(rootPermission()));
    setDescription(
        messages.render(settings.get().locale().text("description-" + id.key()), Map.of()));
  }

  private PermissionId rootPermission() {
    return PermissionId.valueOf(id.name());
  }

  @Override
  public boolean execute(CommandSender sender, String label, String[] args) {
    if (!plugin.isEnabled()) {
      return true;
    }
    if (id != CommandId.ADMIN && !allowed(sender, rootPermission())) {
      messages.send(sender, "no-permission");
      return true;
    }
    try {
      switch (id) {
        case ADMIN -> admin(sender, args);
        case HISTORY -> history.execute(sender, args);
        case TOP -> top.execute(sender, args);
        case SELL, WORTH -> playerCommand(sender, args);
      }
    } catch (PriceUnavailableException failure) {
      if (sender instanceof Player player) {
        selling.priceUnavailable(player, failure);
      }
    } catch (IllegalArgumentException failure) {
      messages.send(sender, "sale-too-large");
    } catch (RuntimeException failure) {
      // Command entry is an isolation boundary for third-party provider/API failures.
      plugin.getLogger().log(Level.SEVERE, "WellSell command failed", failure);
      messages.send(sender, "internal-error");
    }
    return true;
  }

  private void playerCommand(CommandSender sender, String[] args) {
    if (!(sender instanceof Player player)) {
      messages.send(sender, "player-only");
      return;
    }
    String subcommand = args.length == 0 ? "hand" : args[0].toLowerCase(Locale.ROOT);
    if (id == CommandId.SELL && subcommand.equals("gui") && args.length == 1) {
      gui.open(player);
      return;
    }
    if (id == CommandId.SELL && subcommand.equals("progress") && args.length == 1) {
      progress.open(player);
      return;
    }
    boolean hand = subcommand.equals("hand");
    if (!validPlayerRequest(subcommand, args.length)) {
      messages.send(player, "invalid-command");
      return;
    }
    java.util.OptionalInt requested = requestedAmount(player, args);
    if (requested.isEmpty()) {
      return;
    }
    BukkitInventoryAccess captured =
        BukkitInventoryAccess.capture(player, hand, requested.getAsInt());
    if (id == CommandId.WORTH) {
      messages.send(player, "worth", messages.sale(selling.quote(player, captured)));
    } else {
      selling.sell(player, captured, hand ? "HAND" : "INVENTORY");
    }
  }

  private boolean validPlayerRequest(String subcommand, int arguments) {
    return switch (subcommand) {
      case "hand" -> arguments <= (id == CommandId.SELL ? 2 : 1);
      case "inventory" -> arguments <= 1;
      case "all" -> id == CommandId.SELL && arguments <= 1;
      default -> false;
    };
  }

  private java.util.OptionalInt requestedAmount(Player player, String[] args) {
    if (args.length == 2) {
      try {
        int amount = Integer.parseInt(args[1]);
        if (amount <= 0 || amount > player.getInventory().getItemInMainHand().getAmount()) {
          throw new NumberFormatException("Amount outside held stack");
        }
        return java.util.OptionalInt.of(amount);
      } catch (NumberFormatException failure) {
        messages.send(player, "invalid-amount");
        return java.util.OptionalInt.empty();
      }
    }
    return java.util.OptionalInt.of(0);
  }

  private void admin(CommandSender sender, String[] args) {
    if (args.length > 0 && args[0].equalsIgnoreCase("migrate")) {
      migration.execute(sender, args);
      return;
    }
    if (args.length > 0 && args[0].equalsIgnoreCase("prices")) {
      priceImport.execute(sender, args);
      return;
    }
    if (args.length > 1) {
      messages.send(sender, "invalid-command");
      return;
    }
    String subcommand = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
    switch (subcommand) {
      case "help" -> messages.send(sender, "help");
      case "info" -> {
        if (allowed(sender, PermissionId.ADMIN)) {
          info.accept(sender);
        } else {
          messages.send(sender, "no-permission");
        }
      }
      case "reload" -> {
        if (allowed(sender, PermissionId.ADMIN) && allowed(sender, PermissionId.RELOAD)) {
          reload.accept(sender);
        } else {
          messages.send(sender, "no-permission");
        }
      }
      default -> messages.send(sender, "invalid-command");
    }
  }

  @Override
  public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
    if (id != CommandId.ADMIN && !allowed(sender, rootPermission())) {
      return List.of();
    }
    List<String> candidates = new ArrayList<>();
    importSuggestions(sender, args, candidates);
    migrationSuggestions(sender, args, candidates);
    if (id == CommandId.TOP && args.length <= 3) {
      candidates.addAll(top.suggestions());
    }
    if (args.length == 1) {
      firstSuggestions(sender, candidates);
    } else if (args.length == 2) {
      secondSuggestions(sender, args, candidates);
    }
    String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
    return candidates.stream()
        .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix))
        .toList();
  }

  private void migrationSuggestions(CommandSender sender, String[] args, List<String> candidates) {
    if (id != CommandId.ADMIN || !migration.permitted(sender)) {
      return;
    }
    if (args.length == 1) {
      candidates.add("migrate");
    } else if (args.length == 2 && args[0].equalsIgnoreCase("migrate")) {
      candidates.addAll(List.of("mysql", "mariadb"));
    }
  }

  private void importSuggestions(CommandSender sender, String[] args, List<String> candidates) {
    if (args.length == 0) {
      return;
    }
    if (id == CommandId.ADMIN && priceImport.permitted(sender)) {
      if (args.length == 1) {
        candidates.add("prices");
      } else if (args[0].equalsIgnoreCase("prices")) {
        if (args.length == 2) {
          candidates.add("import");
        } else if (args.length == 3 && args[1].equalsIgnoreCase("import")) {
          candidates.addAll(List.of("economyshopgui", "shopguiplus"));
        }
      }
    }
  }

  private void firstSuggestions(CommandSender sender, List<String> candidates) {
    switch (id) {
      case SELL -> {
        suggest(sender, candidates, PermissionId.SELL_HAND, "hand");
        suggest(sender, candidates, PermissionId.SELL_INVENTORY, "inventory", "all");
        suggest(sender, candidates, PermissionId.GUI, "gui");
        suggest(sender, candidates, PermissionId.PROGRESS, "progress");
      }
      case TOP -> {
        /* Bounded suggestions are supplied for each argument above. */
      }
      case WORTH -> candidates.addAll(List.of("hand", "inventory"));
      case HISTORY -> {
        candidates.addAll(List.of("1", "2", "3"));
        if (allowed(sender, PermissionId.HISTORY_OTHERS)) {
          candidates.addAll(directory.names());
        }
      }
      case ADMIN -> {
        candidates.add("help");
        suggest(sender, candidates, PermissionId.ADMIN, "info");
        if (allowed(sender, PermissionId.ADMIN)) {
          suggest(sender, candidates, PermissionId.RELOAD, "reload");
        }
      }
    }
  }

  private void secondSuggestions(CommandSender sender, String[] args, List<String> candidates) {
    if (id == CommandId.SELL
        && args[0].equalsIgnoreCase("hand")
        && sender instanceof Player player
        && allowed(sender, PermissionId.SELL_HAND)) {
      int available = player.getInventory().getItemInMainHand().getAmount();
      for (int quantity : List.of(1, 16, 32, 64)) {
        if (quantity <= available) {
          candidates.add(Integer.toString(quantity));
        }
      }
    } else if (id == CommandId.HISTORY
        && (allowed(sender, PermissionId.HISTORY_OTHERS)
            || sender instanceof Player && args[0].equalsIgnoreCase(sender.getName()))) {
      candidates.addAll(List.of("1", "2", "3"));
    }
  }

  private void suggest(
      CommandSender sender, List<String> candidates, PermissionId permission, String... labels) {
    if (allowed(sender, permission)) {
      candidates.addAll(List.of(labels));
    }
  }

  private boolean allowed(CommandSender sender, PermissionId permission) {
    return settings.get().permissions().has(sender, permission);
  }

  @Override
  public Plugin getPlugin() {
    return plugin;
  }
}
