package com.wellsetups.WellSell.sell;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.util.io.BukkitObjectOutputStream;

public final class BukkitInventoryAccess implements InventoryAccess {
  /** Mutable ItemStacks are defensively copied on entry and exit. */
  public static final class Selection {
    private final ItemStack original;
    private final int amount;

    public Selection(ItemStack original, int amount) {
      this.original = original.clone();
      this.amount = amount;
      if (amount <= 0 || amount > original.getAmount()) {
        throw new IllegalArgumentException("Selected amount exceeds original stack");
      }
    }

    public ItemStack original() {
      return original.clone();
    }

    public ItemStack preview() {
      ItemStack copy = original.clone();
      copy.setAmount(amount);
      return copy;
    }

    public int amount() {
      return amount;
    }
  }

  private final Player player;
  private final Map<Integer, Selection> selection;
  private final Consumer<ItemStack> drop;
  private Location refundLocation;

  public BukkitInventoryAccess(Player player, Map<Integer, Selection> selection) {
    this(player, selection, null);
  }

  BukkitInventoryAccess(
      Player player, Map<Integer, Selection> selection, Consumer<ItemStack> drop) {
    this.player = player;
    this.selection = Map.copyOf(selection);
    this.drop = drop == null ? this::dropRefund : drop;
  }

  public static BukkitInventoryAccess capture(Player player, boolean hand, int requested) {
    return capture(player, hand, requested, null);
  }

  static BukkitInventoryAccess capture(
      Player player, boolean hand, int requested, Consumer<ItemStack> drop) {
    Map<Integer, Selection> selections = new LinkedHashMap<>();
    PlayerInventory inventory = player.getInventory();
    int first = hand ? inventory.getHeldItemSlot() : 0;
    int last = hand ? first + 1 : 36;
    for (int slot = first; slot < last; slot++) {
      ItemStack item = inventory.getItem(slot);
      if (item == null
          || item.getType().isAir()
          || item.getAmount() <= 0
          || item.getAmount() > 127) {
        continue;
      }
      int amount = requested == 0 ? item.getAmount() : requested;
      selections.put(slot, new Selection(item, amount));
    }
    return new BukkitInventoryAccess(player, selections, drop);
  }

  public List<Stock> stock(boolean allowMetadata) {
    List<Stock> stock = new ArrayList<>();
    selection.forEach(
        (slot, selected) -> {
          ItemStack original = selected.original;
          if (allowMetadata || !original.hasItemMeta()) {
            stock.add(
                new Stock(slot, original.getType().name(), original.getAmount(), selected.amount));
          }
        });
    return stock;
  }

  public Map<Integer, ItemStack> items(boolean allowMetadata) {
    Map<Integer, ItemStack> items = new java.util.TreeMap<>();
    selection.forEach(
        (slot, selected) -> {
          if (allowMetadata || !selected.original.hasItemMeta()) {
            items.put(slot, selected.preview());
          }
        });
    return items;
  }

  @Override
  public boolean unchanged(SellPlan plan) {
    if (!player.isOnline() || player.isDead() || !player.isValid()) {
      return false;
    }
    for (SellPlan.Line line : plan.lines()) {
      Selection expected = selection.get(line.slot());
      if (expected == null
          || line.amount() != expected.amount
          || !expected.original.equals(player.getInventory().getItem(line.slot()))) {
        return false;
      }
    }
    return true;
  }

  @Override
  public void remove(SellPlan plan) {
    refundLocation = player.getLocation().clone();
    List<SellPlan.Line> removed = new ArrayList<>();
    try {
      for (SellPlan.Line line : plan.lines()) {
        ItemStack remainder = selection.get(line.slot()).original();
        remainder.setAmount(remainder.getAmount() - line.amount());
        player.getInventory().setItem(line.slot(), remainder.getAmount() == 0 ? null : remainder);
        removed.add(line);
      }
    } catch (RuntimeException failure) {
      for (SellPlan.Line line : removed) {
        returnLine(line);
      }
      throw failure;
    }
  }

  @Override
  public void restore(SellPlan plan) {
    for (SellPlan.Line line : plan.lines()) {
      returnLine(line);
    }
  }

  private void returnLine(SellPlan.Line line) {
    Selection saved = selection.get(line.slot());
    ItemStack refund = saved.original();
    refund.setAmount(line.amount());
    if (!player.isOnline() || player.isDead() || !player.isValid()) {
      // Death/quit hooks can run inside an economy provider. Writing into that player's
      // discarded inventory would lose the refund, so use the commit location instead.
      drop.accept(refund);
      return;
    }
    ItemStack expectedRemainder = saved.original();
    expectedRemainder.setAmount(expectedRemainder.getAmount() - line.amount());
    ItemStack current = player.getInventory().getItem(line.slot());
    boolean empty = current == null || current.getType().isAir();
    if (expectedRemainder.getAmount() == 0 ? empty : expectedRemainder.equals(current)) {
      player.getInventory().setItem(line.slot(), saved.original());
      return;
    }
    // An economy provider may run other plugin code synchronously. Preserve its slot
    // changes and return only our removed quantity; full inventories drop at the owner.
    player.getInventory().addItem(refund).values().forEach(drop);
  }

  private void dropRefund(ItemStack refund) {
    Location location = java.util.Objects.requireNonNull(refundLocation, "Missing commit location");
    var world = java.util.Objects.requireNonNull(location.getWorld(), "Missing refund world");
    if (!world.dropItem(location, refund).isValid()) {
      throw new IllegalStateException("Refund drop was cancelled; reconcile the audit record");
    }
  }

  public String recoveryItems(SellPlan plan) {
    try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        BukkitObjectOutputStream output = new BukkitObjectOutputStream(bytes)) {
      output.writeInt(plan.lines().size());
      for (SellPlan.Line line : plan.lines()) {
        output.writeInt(line.slot());
        ItemStack item = selection.get(line.slot()).original();
        item.setAmount(line.amount());
        output.writeObject(item);
      }
      output.flush();
      return Base64.getEncoder().encodeToString(bytes.toByteArray());
    } catch (IOException failure) {
      throw new UncheckedIOException("Cannot serialize recovery items", failure);
    }
  }
}
