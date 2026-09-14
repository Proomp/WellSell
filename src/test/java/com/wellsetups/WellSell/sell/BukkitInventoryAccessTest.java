package com.wellsetups.WellSell.sell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wellsetups.WellSell.pricing.MoneyPolicy;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;

/** Adapter contract tests use controlled Bukkit interface doubles, not a simulated server. */
class BukkitInventoryAccessTest {
  private final ItemStack[] contents = new ItemStack[36];
  private int returnedElsewhere;
  private boolean dead;
  private boolean online = true;
  private int dropped;
  private boolean cancelDrops;
  private final PlayerInventory inventory =
      (PlayerInventory)
          Proxy.newProxyInstance(
              getClass().getClassLoader(),
              new Class<?>[] {PlayerInventory.class},
              (proxy, method, arguments) ->
                  switch (method.getName()) {
                    case "getItem" -> contents[(int) arguments[0]];
                    case "setItem" -> {
                      contents[(int) arguments[0]] = (ItemStack) arguments[1];
                      yield null;
                    }
                    case "getHeldItemSlot" -> 0;
                    case "addItem" -> {
                      for (ItemStack item : (ItemStack[]) arguments[0]) {
                        returnedElsewhere += item.getAmount();
                      }
                      yield new java.util.HashMap<Integer, ItemStack>();
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                  });
  private final Player player =
      (Player)
          Proxy.newProxyInstance(
              getClass().getClassLoader(),
              new Class<?>[] {Player.class},
              (proxy, method, arguments) ->
                  switch (method.getName()) {
                    case "getInventory" -> inventory;
                    case "isOnline" -> online;
                    case "isValid" -> true;
                    case "getLocation" -> new Location(null, 0, 64, 0);
                    case "isDead" -> dead;
                    default -> throw new UnsupportedOperationException(method.getName());
                  });

  @Test
  void partialSalePreservesRemainderAndRestoresExactOriginalSlot() {
    contents[0] = new TestStack(10, "tag");
    BukkitInventoryAccess access = capture(3);
    SellPlan plan = plan(access, true);
    assertTrue(access.unchanged(plan));
    access.remove(plan);
    assertEquals(7, contents[0].getAmount());
    access.restore(plan);
    assertEquals(new TestStack(10, "tag"), contents[0]);
    assertEquals(0, returnedElsewhere);
  }

  @Test
  void changedMetadataOrQuantityInvalidatesThePlan() {
    contents[0] = new TestStack(10, "original");
    BukkitInventoryAccess access = capture(3);
    SellPlan plan = plan(access, true);
    contents[0] = new TestStack(10, "changed");
    assertFalse(access.unchanged(plan));
    contents[0] = new TestStack(11, "original");
    assertFalse(access.unchanged(plan));
  }

  @Test
  void compensationDoesNotOverwriteProviderSideInventoryChanges() {
    contents[0] = new TestStack(10, "original");
    BukkitInventoryAccess access = capture(3);
    SellPlan plan = plan(access, true);
    access.remove(plan);
    contents[0] = new TestStack(64, "other-plugin");
    access.restore(plan);
    assertEquals(new TestStack(64, "other-plugin"), contents[0]);
    assertEquals(3, returnedElsewhere);
  }

  @Test
  void deathCancelsAPlanAndMetadataIsExcludedByDefault() {
    contents[0] = new TestStack(10, "custom");
    BukkitInventoryAccess access = capture(0);
    assertEquals(List.of(), access.stock(false));
    dead = true;
    assertFalse(access.unchanged(plan(access, true)));
  }

  @Test
  void guiSelectionClonesItsInputsAndPreviews() {
    ItemStack original = new TestStack(10, "original");
    BukkitInventoryAccess.Selection selection = new BukkitInventoryAccess.Selection(original, 3);
    original.setAmount(1);
    ItemStack preview = selection.preview();
    preview.setAmount(60);
    assertEquals(10, selection.original().getAmount());
    assertEquals(3, selection.preview().getAmount());
  }

  @Test
  void completeStackRemovalUsesEmptySlotAndRestoresIt() {
    contents[0] = new TestStack(10, "");
    BukkitInventoryAccess access = capture(0);
    SellPlan plan = plan(access, false);
    access.remove(plan);
    assertEquals(null, contents[0]);
    access.restore(plan);
    assertEquals(10, contents[0].getAmount());
  }

  @Test
  void deathInsideProviderReturnsRemovedItemsAtCommitLocation() {
    contents[0] = new TestStack(10, "");
    BukkitInventoryAccess access = capture(3);
    SellPlan plan = plan(access, false);
    access.remove(plan);
    dead = true;
    access.restore(plan);
    assertEquals(3, dropped);
    assertEquals(7, contents[0].getAmount());
  }

  @Test
  void disconnectInsideProviderDoesNotWriteIntoDiscardedInventory() {
    contents[0] = new TestStack(10, "");
    BukkitInventoryAccess access = capture(3);
    SellPlan plan = plan(access, false);
    access.remove(plan);
    online = false;
    access.restore(plan);
    assertEquals(3, dropped);
    assertEquals(7, contents[0].getAmount());
  }

  @Test
  void cancelledRefundDropsCannotBeReportedAsSuccessfulRestoration() {
    contents[0] = new TestStack(10, "");
    BukkitInventoryAccess access = capture(3);
    SellPlan plan = plan(access, false);
    access.remove(plan);
    dead = true;
    cancelDrops = true;
    assertThrows(IllegalStateException.class, () -> access.restore(plan));
    assertEquals(0, dropped);
  }

  @Test
  void failedContainerPaymentRestoresTheExactOuterItemWithoutDuplicatingItsContents() {
    contents[0] =
        new TestStack(Material.SHULKER_BOX, 1, "contents:64-diamonds;custom-data:original");
    ItemStack original = contents[0].clone();
    BukkitInventoryAccess access = capture(0);
    SellPlan plan =
        new SellPlan(
            List.of(new SellPlan.Line(0, "SHULKER_BOX", 1, 65)),
            65,
            new BigDecimal("640"),
            BigDecimal.ONE);
    var core =
        new TransactionCore(
            failure -> {
              throw new AssertionError(failure);
            });
    assertEquals(
        TransactionCore.Outcome.REJECTED,
        core.commit(plan, access, () -> com.wellsetups.WellSell.economy.PaymentResult.REJECTED));
    assertEquals(original, contents[0]);
    assertEquals(0, returnedElsewhere);
    assertEquals(0, dropped);
  }

  @Test
  void maintenanceCannotStartDuringAnActiveSaleAndPreventsNewSales() {
    PlayerTransactions transactions = new PlayerTransactions();
    java.util.UUID player = java.util.UUID.randomUUID();
    assertTrue(transactions.acquire(player));
    assertFalse(transactions.pause());
    transactions.release(player);
    assertTrue(transactions.pause());
    assertFalse(transactions.acquire(player));
    transactions.resume();
    assertTrue(transactions.acquire(player));
  }

  private BukkitInventoryAccess capture(int amount) {
    return BukkitInventoryAccess.capture(
        player,
        true,
        amount,
        refund -> {
          if (cancelDrops) {
            throw new IllegalStateException("Refund drop cancelled by world policy");
          }
          dropped += refund.getAmount();
        });
  }

  private static SellPlan plan(BukkitInventoryAccess access, boolean metadata) {
    return SellPlan.create(
        access.stock(metadata),
        material -> Optional.of(BigDecimal.ONE),
        new MoneyPolicy(2, RoundingMode.HALF_UP, new BigDecimal("1000")),
        BigDecimal.ONE);
  }

  private static final class TestStack extends ItemStack {
    private final String metadata;

    TestStack(int amount, String metadata) {
      this(Material.DIAMOND, amount, metadata);
    }

    TestStack(Material material, int amount, String metadata) {
      super(material, amount);
      this.metadata = metadata;
    }

    @Override
    public boolean hasItemMeta() {
      return !metadata.isEmpty();
    }

    @Override
    public ItemStack clone() {
      return new TestStack(getType(), getAmount(), metadata);
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof TestStack stack
          && getType() == stack.getType()
          && getAmount() == stack.getAmount()
          && metadata.equals(stack.metadata);
    }

    @Override
    public int hashCode() {
      return java.util.Objects.hash(getType(), getAmount(), metadata);
    }
  }
}
