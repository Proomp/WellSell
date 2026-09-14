package com.wellsetups.WellSell.lore;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientCreativeInventoryAction;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPlayerInventory;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems;
import java.util.ArrayList;
import java.util.UUID;
import java.util.function.Consumer;

/** Network boundary: no Bukkit inventory, permission, world or price API access here. */
public final class LorePackets extends PacketListenerAbstract {
  @FunctionalInterface
  public interface Display {
    ItemStack item(UUID player, int slot, ItemStack original);
  }

  private final Display display;
  private final int protocol;
  private final Consumer<Throwable> failure;

  public LorePackets(Display display, int protocol, Consumer<Throwable> failure) {
    super(PacketListenerPriority.HIGH);
    this.display = display;
    this.protocol = protocol;
    this.failure = failure;
  }

  @Override
  public void onPacketSend(PacketSendEvent event) {
    if (event.isCancelled()
        || event.getUser().getClientVersion().getProtocolVersion() != protocol) {
      return;
    }
    try {
      rewrite(event);
    } catch (RuntimeException | LinkageError problem) {
      failure.accept(problem);
    }
  }

  private void rewrite(PacketSendEvent event) {
    UUID id = event.getUser().getUUID();
    if (id == null) {
      return;
    }
    var type = event.getPacketType();
    if (type == PacketType.Play.Server.WINDOW_ITEMS) {
      var packet = new WrapperPlayServerWindowItems(event);
      if (packet.getWindowId() != 0) {
        return;
      }
      var items = new ArrayList<>(packet.getItems());
      for (int slot = 9; slot < Math.min(45, items.size()); slot++) {
        items.set(slot, display.item(id, windowSlot(slot), items.get(slot)));
      }
      packet.setItems(items);
    } else if (type == PacketType.Play.Server.SET_SLOT) {
      var packet = new WrapperPlayServerSetSlot(event);
      int slot =
          packet.getWindowId() == -2
              ? packet.getSlot()
              : packet.getWindowId() == 0 ? windowSlot(packet.getSlot()) : -1;
      if (slot >= 0 && slot < 36) {
        packet.setItem(display.item(id, slot, packet.getItem()));
      }
    } else if (type == PacketType.Play.Server.SET_PLAYER_INVENTORY) {
      var packet = new WrapperPlayServerSetPlayerInventory(event);
      if (packet.getSlot() >= 0 && packet.getSlot() < 36) {
        packet.setStack(display.item(id, packet.getSlot(), packet.getStack()));
      }
    }
  }

  static int windowSlot(int slot) {
    if (slot >= 36 && slot <= 44) {
      return slot - 36;
    }
    return slot >= 9 && slot <= 35 ? slot : -1;
  }

  @Override
  public void onPacketReceive(PacketReceiveEvent event) {
    if (event.isCancelled()
        || event.getPacketType() != PacketType.Play.Client.CREATIVE_INVENTORY_ACTION) {
      return;
    }
    try {
      var packet = new WrapperPlayClientCreativeInventoryAction(event);
      if (packet.getItemStack() != null) {
        packet.setItemStack(LoreMarker.strip(packet.getItemStack()));
      }
    } catch (RuntimeException | LinkageError problem) {
      event.setCancelled(true);
      failure.accept(problem);
    }
  }
}
