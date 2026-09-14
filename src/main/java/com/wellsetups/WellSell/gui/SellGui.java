package com.wellsetups.WellSell.gui;

import com.wellsetups.WellSell.command.PermissionId;
import com.wellsetups.WellSell.config.Settings;
import com.wellsetups.WellSell.message.Messages;
import com.wellsetups.WellSell.platform.InventoryViews;
import com.wellsetups.WellSell.platform.PlatformExecutor;
import com.wellsetups.WellSell.pricing.PriceUnavailableException;
import com.wellsetups.WellSell.sell.BukkitInventoryAccess;
import com.wellsetups.WellSell.sell.SellPlan;
import com.wellsetups.WellSell.sell.SellService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

public final class SellGui implements Listener {
  static final class Session implements InventoryHolder {
    private final UUID owner;
    private final Settings settings;
    private final Map<Integer, BukkitInventoryAccess.Selection> selected = new LinkedHashMap<>();
    private Inventory inventory;
    private boolean closed;

    Session(UUID owner, Settings settings) {
      this.owner = owner;
      this.settings = settings;
    }

    @Override
    public Inventory getInventory() {
      return java.util.Objects.requireNonNull(inventory);
    }
  }

  private final Supplier<Settings> settings;
  private final SellService selling;
  private final Messages messages;
  private final PlatformExecutor platform;
  private final GuiIcons icons;
  private final InventoryViews views = new InventoryViews();

  public SellGui(
      Supplier<Settings> settings,
      SellService selling,
      Messages messages,
      PlatformExecutor platform) {
    this.settings = settings;
    this.selling = selling;
    this.messages = messages;
    icons = new GuiIcons(messages);
    this.platform = platform;
  }

  public void open(Player player) {
    Settings snapshot = settings.get();
    if (!snapshot.permissions().has(player, PermissionId.SELL)
        || !snapshot.permissions().has(player, PermissionId.GUI)) {
      messages.send(player, "no-permission");
      return;
    }
    if (!snapshot.gui().enabled()) {
      messages.send(player, "disabled");
      return;
    }
    Session session = new Session(player.getUniqueId(), snapshot);
    session.inventory =
        Bukkit.createInventory(
            session, snapshot.gui().size(), messages.render(snapshot.gui().title(), Map.of()));
    render(player, session);
    player.openInventory(session.inventory);
    messages.send(player, "gui-hint");
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void click(InventoryClickEvent event) {
    if (!(views.top(event.getView()).getHolder() instanceof Session session)) {
      return;
    }
    boolean alreadyCancelled = event.isCancelled();
    event.setCancelled(true);
    if (alreadyCancelled
        || !(event.getWhoClicked() instanceof Player player)
        || !session.owner.equals(player.getUniqueId())
        || session.closed) {
      return;
    }
    if (session.settings != settings.get()) {
      closeLater(player, session);
      return;
    }
    ClickType click = event.getClick();
    // All transfers stay cancelled. Only these explicit selection gestures have meaning.
    if (!java.util.Set.of(
            ClickType.LEFT, ClickType.RIGHT, ClickType.SHIFT_LEFT, ClickType.SHIFT_RIGHT)
        .contains(click)) {
      return;
    }
    if (event.getClickedInventory() == session.inventory) {
      topClick(player, session, event.getRawSlot());
    } else if (event.getClickedInventory() == player.getInventory()
        && event.getSlot() >= 0
        && event.getSlot() < 36) {
      select(
          player,
          session,
          event.getSlot(),
          click == ClickType.RIGHT || click == ClickType.SHIFT_RIGHT);
    }
  }

  private void topClick(Player player, Session session, int slot) {
    GuiSettings gui = session.settings.gui();
    if (slot == gui.confirmSlot()) {
      // Close on the next entity tick, as required by InventoryClickEvent's API contract.
      Map<Integer, BukkitInventoryAccess.Selection> captured = Map.copyOf(session.selected);
      session.closed = true;
      platform.player(
          player,
          () -> {
            if (views.top(player.getOpenInventory()) != session.inventory
                || settings.get() != session.settings) {
              return;
            }
            player.closeInventory();
            gui.confirm().sound().play(player);
            selling.sell(player, new BukkitInventoryAccess(player, captured), "GUI");
          },
          () -> {});
    } else if (slot == gui.cancelSlot()) {
      gui.cancel().sound().play(player);
      closeLater(player, session);
    } else if (gui.slots().contains(slot)) {
      int index = gui.slots().indexOf(slot);
      List<Integer> selectedSlots = new ArrayList<>(session.selected.keySet());
      if (index < selectedSlots.size()) {
        session.selected.remove(selectedSlots.get(index));
        gui.click().play(player);
        render(player, session);
      }
    } else if (slot == gui.infoSlot()) {
      gui.info().sound().play(player);
    }
  }

  private void select(Player player, Session session, int slot, boolean single) {
    if (session.selected.containsKey(slot)) {
      session.selected.remove(slot);
    } else {
      if (session.selected.size() >= session.settings.gui().slots().size()) {
        messages.send(player, "gui-full");
        return;
      }
      ItemStack item = player.getInventory().getItem(slot);
      if (item == null
          || item.getType().isAir()
          || item.getAmount() < 1
          || item.getAmount() > 127) {
        return;
      }
      var candidate = new BukkitInventoryAccess.Selection(item, single ? 1 : item.getAmount());
      try {
        if (selling
            .quote(player, new BukkitInventoryAccess(player, Map.of(slot, candidate)))
            .empty()) {
          messages.send(player, "nothing-to-sell");
          return;
        }
      } catch (PriceUnavailableException failure) {
        selling.priceUnavailable(player, failure);
        return;
      }
      session.selected.put(slot, candidate);
    }
    session.settings.gui().click().play(player);
    render(player, session);
  }

  private void render(Player player, Session session) {
    GuiSettings gui = session.settings.gui();
    SellPlan plan;
    try {
      plan = selling.quote(player, new BukkitInventoryAccess(player, session.selected));
    } catch (PriceUnavailableException failure) {
      session.selected.clear();
      plan = selling.quote(player, new BukkitInventoryAccess(player, session.selected));
      selling.priceUnavailable(player, failure);
    } catch (IllegalArgumentException tooLarge) {
      session.selected.clear();
      plan = selling.quote(player, new BukkitInventoryAccess(player, session.selected));
      messages.send(player, "sale-too-large");
    }
    Map<String, String> values = messages.sale(plan);
    ItemStack filler = icons.create(gui.filler(), values);
    for (int slot = 0; slot < gui.size(); slot++) {
      session.inventory.setItem(slot, gui.slots().contains(slot) ? null : filler);
    }
    int index = 0;
    for (BukkitInventoryAccess.Selection item : session.selected.values()) {
      session.inventory.setItem(gui.slots().get(index++), item.preview());
    }
    session.inventory.setItem(gui.confirmSlot(), icons.create(gui.confirm(), values));
    session.inventory.setItem(gui.cancelSlot(), icons.create(gui.cancel(), values));
    session.inventory.setItem(gui.infoSlot(), icons.create(gui.info(), values));
  }

  private void closeLater(Player player, Session session) {
    session.closed = true;
    platform.player(
        player,
        () -> {
          if (views.top(player.getOpenInventory()) == session.inventory) {
            player.closeInventory();
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

  @EventHandler
  public void close(InventoryCloseEvent event) {
    if (event.getInventory().getHolder() instanceof Session session) {
      session.closed = true;
      session.selected.clear();
      session.inventory.clear();
    }
  }

  @EventHandler
  public void quit(PlayerQuitEvent event) {
    invalidate(event.getPlayer());
    selling.forget(event.getPlayer().getUniqueId());
  }

  @EventHandler
  public void death(PlayerDeathEvent event) {
    invalidate(event.getEntity());
  }

  public void invalidate(Player player) {
    if (views.top(player.getOpenInventory()).getHolder() instanceof Session session) {
      session.closed = true;
      session.selected.clear();
      session.inventory.clear();
    }
  }
}
