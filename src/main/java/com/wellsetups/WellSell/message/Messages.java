package com.wellsetups.WellSell.message;

import com.wellsetups.WellSell.command.CommandId;
import com.wellsetups.WellSell.command.CommandNames;
import com.wellsetups.WellSell.config.Settings;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class Messages {
  private final Supplier<Settings> settings;
  private final CommandNames registered;
  private final String version;
  private final TextFormatter formatter = new TextFormatter();

  public Messages(Supplier<Settings> settings, CommandNames registered, String version) {
    this.settings = settings;
    this.registered = registered;
    this.version = version;
  }

  public void send(CommandSender sender, String key) {
    send(sender, key, Map.of());
  }

  public void send(CommandSender sender, String key, Map<String, String> values) {
    Settings snapshot = settings.get();
    Map<String, String> all = new HashMap<>(values);
    if (sender instanceof Player player) {
      all.put("player", player.getName());
    }
    String text = render(snapshot.locale().text(key), all);
    String mode = snapshot.general().text("delivery." + key, "CHAT");
    if (mode.equals("NONE")) {
      return;
    }
    if (!(sender instanceof Player player)) {
      sender.sendMessage(text);
      return;
    }
    switch (mode) {
      case "ACTION_BAR" ->
          player
              .spigot()
              .sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(text));
      case "TITLE" -> player.sendTitle(text, "", 10, 50, 10);
      case "SUBTITLE" -> player.sendTitle("", text, 10, 50, 10);
      default -> player.sendMessage(text);
    }
  }

  public String render(String template, Map<String, String> values) {
    Map<String, String> all = new HashMap<>(values);
    all.put("version", version);
    for (CommandId id : CommandId.values()) {
      all.put(id.key() + "_command", registered.name(id));
    }
    return formatter.format(template, settings.get().locale().text("prefix"), all);
  }

  public Map<String, String> sale(com.wellsetups.WellSell.sell.SellPlan plan) {
    return Map.of(
        "amount",
        Integer.toString(plan.amount()),
        "money",
        settings.get().formatMoney(plan.money()),
        "multiplier",
        plan.multiplier().stripTrailingZeros().toPlainString(),
        "item",
        plan.lines().size() == 1 ? plan.lines().get(0).material() : "");
  }
}
