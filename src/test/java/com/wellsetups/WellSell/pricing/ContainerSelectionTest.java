package com.wellsetups.WellSell.pricing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

class ContainerSelectionTest {
  private final ContainerPolicy.Rule enabled =
      new ContainerPolicy.Rule(true, true, true, false, false);
  private final ContainerPolicy policy = new ContainerPolicy(enabled, enabled, 2, 2048);

  @Test
  void emptyShulkerCountsOnlyItsShellAndWorthNeverMutatesSource() {
    Stack box = box(List.of());
    var selection = expand(box, policy);
    assertEquals(1, selection.groups().get(0).line().amount());
    assertEquals(1, selection.groups().get(0).line().valuedAmount());
    selection.items().values().iterator().next().setAmount(25);
    assertEquals(1, box.getAmount());
    assertTrue(box.children.isEmpty());
  }

  @Test
  void fullySellableShulkerCountsEveryContentAndOnePhysicalRemoval() {
    Stack diamond = new Stack(Material.DIAMOND, 64, false, List.of());
    Stack box = box(List.of(diamond, new Stack(Material.WHEAT, 20, false, List.of())));
    var selection = expand(box, policy);
    assertEquals(85, selection.groups().get(0).line().valuedAmount());
    assertEquals(1, selection.groups().get(0).line().amount());
    assertEquals(3, selection.items().size());
    assertEquals(1, selection.containers());
    assertEquals(1, selection.completeGroups(selection.items().keySet()).groups().size());
    assertEquals(64, diamond.getAmount());
  }

  @Test
  void partialProviderAcceptanceRejectsTheWholeContainer() {
    Stack box = box(List.of(new Stack(Material.DIAMOND, 5, false, List.of())));
    var selection = expand(box, policy);
    assertEquals(2, selection.items().size());
    assertTrue(selection.completeGroups(Set.of(0)).groups().isEmpty());
    assertEquals(5, box.children.get(0).getAmount());
  }

  @Test
  void metadataNestedBoxesAndStackedContainersAreRejectedByDefault() {
    assertTrue(
        expand(box(List.of(new Stack(Material.DIAMOND, 1, true, List.of()))), policy)
            .groups()
            .isEmpty());
    assertTrue(expand(box(List.of(box(List.of()))), policy).groups().isEmpty());
    Stack stacked = box(List.of());
    stacked.setAmount(2);
    assertTrue(expand(stacked, policy).groups().isEmpty());
    assertTrue(
        expand(new Stack(Material.SHULKER_BOX, 1, true, List.of()), policy).groups().isEmpty());
  }

  @Test
  void nestedOptInRemainsDepthBoundedAndCountsEachShellOnce() {
    var nested = new ContainerPolicy.Rule(true, true, true, true, false);
    var policy = new ContainerPolicy(nested, nested, 2, 2048);
    var selection =
        expand(
            box(List.of(box(List.of(new Stack(Material.DIAMOND, 3, false, List.of()))))), policy);
    assertEquals(5, selection.groups().get(0).line().valuedAmount());
    assertEquals(2, selection.containers());
    assertTrue(expand(box(List.of(box(List.of(box(List.of()))))), policy).groups().isEmpty());
  }

  @Test
  void bundleQuantitiesAndExcludedShellPolicyAreExplicit() {
    var contentsOnly = new ContainerPolicy.Rule(true, true, false, false, false);
    Stack bundle =
        new Stack(
            Material.BUNDLE, 1, false, List.of(new Stack(Material.DIAMOND, 7, false, List.of())));
    var selection = expand(bundle, new ContainerPolicy(enabled, contentsOnly, 2, 2048));
    assertEquals(7, selection.groups().get(0).line().valuedAmount());
    assertEquals(1, selection.items().size());
    assertEquals(7, bundle.children.get(0).getAmount());
    assertTrue(
        expand(
                new Stack(Material.BUNDLE, 1, false, List.of()),
                new ContainerPolicy(enabled, contentsOnly, 2, 2048))
            .groups()
            .isEmpty());
  }

  @Test
  void disabledContentsNeverDestroyFullBoxes() {
    var shellOnly = new ContainerPolicy.Rule(true, false, true, false, false);
    var policy = new ContainerPolicy(shellOnly, shellOnly, 2, 2048);
    assertTrue(
        expand(box(List.of(new Stack(Material.DIAMOND, 1, false, List.of()))), policy)
            .groups()
            .isEmpty());
    assertEquals(1, expand(box(List.of()), policy).items().size());
  }

  private static Stack box(List<Stack> children) {
    return new Stack(Material.SHULKER_BOX, 1, false, children);
  }

  private static ContainerSelection expand(Stack item, ContainerPolicy policy) {
    return ContainerSelection.expand(
        Map.of(0, item),
        policy,
        false,
        (original, kind) -> {
          Stack stack = (Stack) original;
          return new ContainerReader.Contents(
              stack.children.stream().map(child -> (ItemStack) child).toList(),
              new Stack(stack.getType(), 1, false, List.of()),
              stack.metadata);
        });
  }

  private static final class Stack extends ItemStack {
    private final boolean metadata;
    private final List<Stack> children;

    private Stack(Material material, int amount, boolean metadata, List<Stack> children) {
      super(material, amount);
      this.metadata = metadata;
      this.children = List.copyOf(children);
    }

    @Override
    public boolean hasItemMeta() {
      return metadata;
    }

    @Override
    public Stack clone() {
      return new Stack(
          getType(), getAmount(), metadata, children.stream().map(Stack::clone).toList());
    }
  }
}
