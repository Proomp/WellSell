package com.wellsetups.WellSell.gui;

import com.wellsetups.WellSell.api.CategoryStatistics;
import com.wellsetups.WellSell.api.PlayerStatistics;
import com.wellsetups.WellSell.category.CategoryCatalog;
import com.wellsetups.WellSell.command.PermissionId;
import com.wellsetups.WellSell.config.Settings;
import com.wellsetups.WellSell.message.Messages;
import com.wellsetups.WellSell.platform.InventoryViews;
import com.wellsetups.WellSell.platform.PlatformExecutor;
import com.wellsetups.WellSell.storage.PlayerStatisticsCache;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public final class ProgressGui implements Listener {
  private static final class Session implements InventoryHolder {
    private final UUID owner;
    private final Settings settings;
    private final int page;
    private final int pages;
    private Inventory inventory;
    private boolean navigating;

    private Session(UUID owner, Settings settings, int page, int pages) {
      this.owner = owner;
      this.settings = settings;
      this.page = page;
      this.pages = pages;
    }

    @Override
    public Inventory getInventory() {
      return java.util.Objects.requireNonNull(inventory);
    }
  }

  private final Supplier<Settings> settings;
  private final PlayerStatisticsCache statistics;
  private final Messages messages;
  private final PlatformExecutor platform;
  private final GuiIcons icons;
  private final InventoryViews views = new InventoryViews();

  public ProgressGui(
      Supplier<Settings> settings,
      PlayerStatisticsCache statistics,
      Messages messages,
      PlatformExecutor platform) {
    this.settings = settings;
    this.statistics = statistics;
    this.messages = messages;
    this.platform = platform;
    icons = new GuiIcons(messages);
  }

  public void open(Player player) {
    open(player, 1);
  }

  private void open(Player player, int requested) {
    platform.requireOwner(player);
    Settings snapshot = settings.get();
    if (!snapshot.permissions().has(player, PermissionId.PROGRESS)) {
      messages.send(player, "no-permission");
      return;
    }
    ProgressGuiSettings gui = snapshot.expansion().gui();
    if (!gui.enabled()) {
      messages.send(player, "disabled");
      return;
    }
    PlayerStatistics stats = statistics.require(player.getUniqueId());
    if (stats == null) {
      messages.send(player, "statistics-loading");
      return;
    }
    var categories = new ArrayList<>(snapshot.expansion().categories().categories().values());
    int pages = Math.max(1, (categories.size() + gui.slots().size() - 1) / gui.slots().size());
    Session session =
        new Session(player.getUniqueId(), snapshot, Math.max(1, Math.min(pages, requested)), pages);
    Map<String, String> values =
        Map.of("page", Integer.toString(session.page), "max_page", Integer.toString(pages));
    session.inventory =
        Bukkit.createInventory(session, gui.size(), messages.render(gui.title(), values));
    for (int slot = 0; slot < gui.size(); slot++) {
      session.inventory.setItem(slot, icons.create(gui.filler(), values));
    }
    int first = (session.page - 1) * gui.slots().size();
    for (int index = 0; index < gui.slots().size() && first + index < categories.size(); index++) {
      CategoryCatalog.Category category = categories.get(first + index);
      Map<String, String> card = values(snapshot, stats, category);
      session.inventory.setItem(
          gui.slots().get(index),
          icons.create(
              categoryIcon(gui, category),
              card,
              Map.of(
                  "category", category.displayName(), "progress_bar", card.get("progress_bar"))));
    }
    gui.buttons()
        .forEach(
            (button, slot) ->
                session.inventory.setItem(slot, icons.create(gui.icons().get(button), values)));
    player.openInventory(session.inventory);
  }

  private static GuiSettings.Icon categoryIcon(
      ProgressGuiSettings gui, CategoryCatalog.Category category) {
    GuiSettings.Icon icon = gui.category();
    Material material =
        gui.categoryMaterial() ? Material.matchMaterial(category.icon()) : icon.material();
    if (material == null || !material.isItem() || material.isAir()) {
      material = icon.material();
    }
    return new GuiSettings.Icon(
        material,
        icon.amount(),
        icon.modelData(),
        icon.glow(),
        icon.name(),
        icon.lore(),
        icon.sound());
  }

  private static Map<String, String> values(
      Settings snapshot, PlayerStatistics player, CategoryCatalog.Category category) {
    CategoryStatistics stats =
        player.categories().getOrDefault(category.id(), CategoryStatistics.empty());
    var view = snapshot.expansion().progression().view(category.id(), stats);
    Map<String, String> values = new HashMap<>();
    values.put("category", category.displayName());
    values.put("category_id", category.id());
    values.put("level", Integer.toString(view.level()));
    values.put("multiplier", view.multiplier().stripTrailingZeros().toPlainString());
    values.put("earned", snapshot.formatMoney(stats.earned()));
    values.put("items", Long.toString(stats.items()));
    values.put("progress", view.percent().toPlainString());
    values.put("next_multiplier", view.nextMultiplier().stripTrailingZeros().toPlainString());
    values.put("next_required", view.nextRequired().toPlainString());
    values.put("progress_bar", snapshot.expansion().gui().bar(view.percent()));
    return values;
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void click(InventoryClickEvent event) {
    if (!(views.top(event.getView()).getHolder() instanceof Session session)) {
      return;
    }
    boolean cancelled = event.isCancelled();
    event.setCancelled(true);
    if (cancelled
        || !(event.getWhoClicked() instanceof Player player)
        || !session.owner.equals(player.getUniqueId())
        || session.navigating) {
      return;
    }
    if (session.settings != settings.get()) {
      invalidate(player);
      return;
    }
    if (event.getClickedInventory() != session.inventory) {
      return;
    }
    ProgressGuiSettings gui = session.settings.expansion().gui();
    for (var entry : gui.buttons().entrySet()) {
      if (entry.getValue() == event.getRawSlot()) {
        navigate(player, session, entry.getKey());
        return;
      }
    }
    if (gui.slots().contains(event.getRawSlot())) {
      gui.category().sound().play(player);
    }
  }

  private void navigate(Player player, Session session, ProgressGuiSettings.Button button) {
    session.settings.expansion().gui().icons().get(button).sound().play(player);
    if (button == ProgressGuiSettings.Button.INFO) {
      return;
    }
    session.navigating = true;
    platform.player(
        player,
        () -> {
          if (views.top(player.getOpenInventory()) != session.inventory) {
            return;
          }
          switch (button) {
            case BACK -> player.closeInventory();
            case PREVIOUS -> open(player, Math.max(1, session.page - 1));
            case NEXT -> open(player, Math.min(session.pages, session.page + 1));
            default -> throw new IllegalStateException("Unexpected navigation button");
          }
        },
        () -> {});
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void drag(InventoryDragEvent event) {
    if (views.top(event.getView()).getHolder() instanceof Session) {
      event.setCancelled(true);
    }
  }

  public void invalidate(Player player) {
    if (views.top(player.getOpenInventory()).getHolder() instanceof Session session) {
      session.navigating = true;
      platform.player(
          player,
          () -> {
            if (views.top(player.getOpenInventory()) == session.inventory) {
              player.closeInventory();
            }
          },
          () -> {});
    }
  }
}
