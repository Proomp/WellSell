package com.wellsetups.WellSell.pricing;

import com.wellsetups.WellSell.sell.SellPlan;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.inventory.ItemStack;

/** Physical slots remain the mutation unit; synthetic component keys exist only during pricing. */
public final class ContainerSelection {
  public record Group(SellPlan.Line line, Map<Integer, ItemStack> components, int containers) {
    public Group {
      components = copies(components);
    }

    @Override
    public Map<Integer, ItemStack> components() {
      return copies(components);
    }
  }

  private final List<Group> groups;

  private ContainerSelection(List<Group> groups) {
    this.groups = List.copyOf(groups);
  }

  public static ContainerSelection expand(
      Map<Integer, ItemStack> original,
      ContainerPolicy policy,
      boolean metadata,
      ContainerReader reader) {
    return expand(original, policy, metadata, reader::read, item -> false);
  }

  public static ContainerSelection expand(
      Map<Integer, ItemStack> original,
      ContainerPolicy policy,
      boolean metadata,
      ContainerReader reader,
      java.util.function.Predicate<ItemStack> trusted) {
    return expand(original, policy, metadata, reader::read, trusted);
  }

  static ContainerSelection expand(
      Map<Integer, ItemStack> original,
      ContainerPolicy policy,
      boolean metadata,
      java.util.function.BiFunction<ItemStack, ContainerReader.Kind, ContainerReader.Contents>
          reader) {
    return expand(original, policy, metadata, reader, item -> false);
  }

  private static ContainerSelection expand(
      Map<Integer, ItemStack> original,
      ContainerPolicy policy,
      boolean metadata,
      java.util.function.BiFunction<ItemStack, ContainerReader.Kind, ContainerReader.Contents>
          reader,
      java.util.function.Predicate<ItemStack> trusted) {
    List<Group> groups = new ArrayList<>();
    int key = 0;
    for (var entry : original.entrySet()) {
      List<ItemStack> leaves = new ArrayList<>();
      int containers = expandItem(entry.getValue(), policy, metadata, reader, 0, leaves, trusted);
      if (containers < 0 || leaves.isEmpty()) {
        continue;
      }
      if (key + leaves.size() > policy.maximumComponents()) {
        throw new PriceUnavailableException(
            "Container selection exceeds the configured component limit");
      }
      Map<Integer, ItemStack> components = new LinkedHashMap<>();
      for (ItemStack leaf : leaves) {
        components.put(key++, leaf);
      }
      groups.add(
          new Group(
              new SellPlan.Line(
                  entry.getKey(),
                  entry.getValue().getType().name(),
                  entry.getValue().getAmount(),
                  leaves.stream().mapToInt(ItemStack::getAmount).sum()),
              components,
              containers));
    }
    return new ContainerSelection(groups);
  }

  private static int expandItem(
      ItemStack item,
      ContainerPolicy policy,
      boolean metadata,
      java.util.function.BiFunction<ItemStack, ContainerReader.Kind, ContainerReader.Contents>
          reader,
      int depth,
      List<ItemStack> leaves,
      java.util.function.Predicate<ItemStack> trusted) {
    if (item.getType().isAir() || item.getAmount() <= 0) {
      return 0;
    }
    if (item.getAmount() > 127 || leaves.size() >= policy.maximumComponents()) {
      return -1;
    }
    ContainerReader.Kind kind = ContainerReader.kind(item);
    if (kind == ContainerReader.Kind.NONE) {
      if (!metadata && item.hasItemMeta() && !trusted.test(item)) {
        return -1;
      }
      leaves.add(item.clone());
      return 0;
    }
    return expandContainer(item, policy, metadata, reader, depth, leaves, kind, trusted);
  }

  private static int expandContainer(
      ItemStack item,
      ContainerPolicy policy,
      boolean metadata,
      java.util.function.BiFunction<ItemStack, ContainerReader.Kind, ContainerReader.Contents>
          reader,
      int depth,
      List<ItemStack> leaves,
      ContainerReader.Kind kind,
      java.util.function.Predicate<ItemStack> trusted) {
    ContainerPolicy.Rule rule =
        kind == ContainerReader.Kind.SHULKER ? policy.shulker() : policy.bundle();
    if (!permitted(rule, item, depth, policy)) {
      return -1;
    }
    ContainerReader.Contents contents = reader.apply(item, kind);
    List<ItemStack> children = contents.items();
    if (!permittedContents(rule, contents, children)) {
      return -1;
    }
    int containers = 1;
    for (ItemStack child : children) {
      if (ContainerReader.kind(child) != ContainerReader.Kind.NONE && !rule.nested()) {
        return -1;
      }
      int nested = expandItem(child, policy, metadata, reader, depth + 1, leaves, trusted);
      if (nested < 0) {
        return -1;
      }
      containers = Math.addExact(containers, nested);
    }
    if (rule.includeContainer()) {
      leaves.add(contents.shell());
    }
    return containers;
  }

  private static boolean permitted(
      ContainerPolicy.Rule rule, ItemStack item, int depth, ContainerPolicy policy) {
    return rule.enabled()
        && depth < policy.maximumDepth()
        && (depth == 0 || rule.nested())
        && item.getAmount() == 1;
  }

  private static boolean permittedContents(
      ContainerPolicy.Rule rule, ContainerReader.Contents contents, List<ItemStack> children) {
    return (!contents.extraMetadata() || rule.metadata())
        && (children.isEmpty() || rule.sellContents());
  }

  public ContainerSelection completeGroups(Set<Integer> accepted) {
    return new ContainerSelection(
        groups.stream().filter(group -> accepted.containsAll(group.components.keySet())).toList());
  }

  public Map<Integer, ItemStack> items() {
    Map<Integer, ItemStack> items = new LinkedHashMap<>();
    groups.forEach(group -> items.putAll(group.components()));
    return items;
  }

  public List<Group> groups() {
    return groups;
  }

  public int containers() {
    return groups.stream().mapToInt(Group::containers).sum();
  }

  private static Map<Integer, ItemStack> copies(Map<Integer, ItemStack> items) {
    Map<Integer, ItemStack> result = new LinkedHashMap<>();
    items.forEach((slot, item) -> result.put(slot, item.clone()));
    return java.util.Collections.unmodifiableMap(result);
  }
}
