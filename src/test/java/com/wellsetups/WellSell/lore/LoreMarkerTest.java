package com.wellsetups.WellSell.lore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemLore;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.nbt.NBTInt;
import com.github.retrooper.packetevents.protocol.nbt.NBTString;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class LoreMarkerTest {
  private static PacketEventsAPI<?> previous;

  @BeforeAll
  static void bootstrap() {
    previous = PacketEvents.getAPI();
    PacketEvents.setAPI(new PacketFixture());
  }

  @AfterAll
  static void restore() {
    PacketEvents.setAPI(previous);
  }

  @Test
  void removesDisplaySuffixAndPreservesOriginalComponentsWithoutMutation() {
    var original = item();
    var data = new NBTCompound();
    data.setTag("server_custom", new NBTString("kept"));
    original.setComponent(ComponentTypes.CUSTOM_DATA, data);
    original.setComponent(ComponentTypes.LORE, new ItemLore(List.of(Component.text("Real lore"))));
    var decorated = append(original);
    var marked = LoreMarker.mark(original, decorated);
    var clean = LoreMarker.strip(marked);
    assertEquals(
        original.getComponent(ComponentTypes.LORE), clean.getComponent(ComponentTypes.LORE));
    assertEquals(
        original.getComponent(ComponentTypes.CUSTOM_DATA),
        clean.getComponent(ComponentTypes.CUSTOM_DATA));
    assertEquals(2, marked.getComponent(ComponentTypes.LORE).orElseThrow().getLines().size());
    assertNull(data.getTagOrNull(LoreMarker.KEY));
    assertSame(clean, LoreMarker.strip(clean));
  }

  @Test
  void absentComponentsRemainAbsentAfterCreativeRoundTrip() {
    var original = item();
    var clean = LoreMarker.strip(LoreMarker.mark(original, append(original)));
    assertEquals(
        original.getComponent(ComponentTypes.LORE), clean.getComponent(ComponentTypes.LORE));
    assertEquals(
        original.getComponent(ComponentTypes.CUSTOM_DATA),
        clean.getComponent(ComponentTypes.CUSTOM_DATA));
    assertFalse(original.hasComponent(ComponentTypes.CUSTOM_DATA));
  }

  @Test
  void invalidMarkersRejectInsteadOfAcceptingDisplayLore() {
    for (var tag : List.of(new NBTInt(-1), new NBTInt(9000), new NBTString("forged"))) {
      var incoming = append(item());
      var data = new NBTCompound();
      data.setTag(LoreMarker.KEY, tag);
      incoming.setComponent(ComponentTypes.CUSTOM_DATA, data);
      assertThrows(IllegalArgumentException.class, () -> LoreMarker.strip(incoming));
    }
  }

  @Test
  void refusesToOverwriteExistingReservedMetadata() {
    var original = item();
    var data = new NBTCompound();
    data.setTag(LoreMarker.KEY, new NBTString("collision"));
    original.setComponent(ComponentTypes.CUSTOM_DATA, data);
    assertEquals(original, LoreMarker.mark(original, append(original)));
  }

  @Test
  void inventorySlotMappingExcludesArmorCraftingAndCursor() {
    assertEquals(0, LorePackets.windowSlot(36));
    assertEquals(8, LorePackets.windowSlot(44));
    assertEquals(9, LorePackets.windowSlot(9));
    assertEquals(35, LorePackets.windowSlot(35));
    for (int slot : new int[] {-1, 0, 5, 8, 45, 46}) {
      assertEquals(-1, LorePackets.windowSlot(slot));
    }
  }

  private static ItemStack item() {
    return ItemStack.builder().type(ItemTypes.DIAMOND).amount(3).build();
  }

  private static ItemStack append(ItemStack original) {
    var decorated = original.copy();
    var lines =
        new java.util.ArrayList<>(
            original.getComponent(ComponentTypes.LORE).map(ItemLore::getLines).orElse(List.of()));
    lines.add(Component.text("Display value"));
    decorated.setComponent(ComponentTypes.LORE, new ItemLore(lines));
    return decorated;
  }
}
