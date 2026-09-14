package com.wellsetups.WellSell.pricing;

import com.wellsetups.WellSell.platform.BundleContents;
import java.util.Arrays;
import java.util.List;
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;

/** Reads copied Bukkit metadata only. Real inventory items are never edited by valuation. */
public final class ContainerReader {
  public enum Kind {
    SHULKER,
    BUNDLE,
    NONE
  }

  public record Contents(List<ItemStack> items, ItemStack shell, boolean extraMetadata) {
    public Contents {
      items = items.stream().map(ItemStack::clone).toList();
      shell = shell.clone();
    }

    @Override
    public List<ItemStack> items() {
      return items.stream().map(ItemStack::clone).toList();
    }

    @Override
    public ItemStack shell() {
      return shell.clone();
    }
  }

  private final BundleContents bundles = new BundleContents();

  public static Kind kind(ItemStack item) {
    String name = item.getType().name();
    if (name.endsWith("SHULKER_BOX")) {
      return Kind.SHULKER;
    }
    if (name.equals("BUNDLE") || name.endsWith("_BUNDLE")) {
      return Kind.BUNDLE;
    }
    return Kind.NONE;
  }

  public Contents read(ItemStack item, Kind kind) {
    ItemMeta meta = item.getItemMeta();
    ItemStack plain = new ItemStack(item.getType(), item.getAmount());
    if (kind == Kind.SHULKER
        && meta instanceof BlockStateMeta block
        && block.getBlockState() instanceof ShulkerBox box) {
      return shulker(item, plain, block, box);
    }
    if (kind == Kind.BUNDLE && bundles.supports(meta)) {
      List<ItemStack> contents = bundles.items(meta);
      bundles.emptyCopy(meta);
      boolean extra = !meta.equals(plain.getItemMeta());
      ItemStack shell = item.clone();
      shell.setItemMeta(meta);
      return new Contents(contents, extra ? shell : plain, extra);
    }
    throw new PriceUnavailableException(
        "Container metadata API is unsupported for " + item.getType());
  }

  private static Contents shulker(
      ItemStack original, ItemStack plain, BlockStateMeta meta, ShulkerBox box) {
    List<ItemStack> contents =
        Arrays.stream(box.getInventory().getContents())
            .filter(java.util.Objects::nonNull)
            .map(ItemStack::clone)
            .toList();
    if (box.isLocked()
        || box.getCustomName() != null
        || box.getLootTable() != null
        || !box.getPersistentDataContainer().isEmpty()) {
      throw new PriceUnavailableException(
          "Shulker block metadata is unsupported; contents were left intact");
    }
    BlockStateMeta base = (BlockStateMeta) java.util.Objects.requireNonNull(plain.getItemMeta());
    var emptyState = base.getBlockState();
    base.setBlockState(emptyState);
    meta.setBlockState(emptyState);
    boolean extra = !meta.equals(base);
    ItemStack shell = original.clone();
    shell.setItemMeta(meta);
    return new Contents(contents, extra ? shell : plain, extra);
  }
}
