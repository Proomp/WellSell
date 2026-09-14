package com.wellsetups.WellSell.pricing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;

class CustomIdentitiesTest {
  private final CustomPrices empty = new CustomPrices(List.of(), Map.of());

  @Test
  void respectsPriorityDisableAndRegistrationLifetime() throws Exception {
    var identities = new CustomIdentities();
    identities.register(100, () -> false, item -> Optional.of("disabled:item"));
    identities.register(1, () -> true, item -> Optional.of("low:item"));
    var high = identities.register(2, () -> true, item -> Optional.of("high:item"));
    assertEquals("high:item", identities.resolve(new Stack(null), empty));
    high.close();
    high.close();
    assertEquals("low:item", identities.resolve(new Stack(null), empty));
  }

  @Test
  @SuppressWarnings("try") // Registration lifetime is the behavior under test.
  void callbackCannotMutateTheOriginalItem() throws Exception {
    var identities = new CustomIdentities();
    var original = new Stack(null);
    try (var registration =
        identities.register(
            0,
            () -> true,
            copy -> {
              copy.setAmount(1);
              return Optional.of("custom:safe");
            })) {
      assertEquals("custom:safe", identities.resolve(original, empty));
      assertEquals(8, original.getAmount());
    }
  }

  @Test
  void resolverErrorsAndInvalidIdentitiesNeverFallThrough() {
    for (String value : List.of("minecraft:diamond", "Display Name", "custom:<red>")) {
      var identities = new CustomIdentities();
      identities.register(0, () -> true, item -> Optional.of(value));
      assertThrows(
          PriceUnavailableException.class, () -> identities.resolve(new Stack(null), empty));
    }
    var identities = new CustomIdentities();
    identities.register(
        0,
        () -> true,
        item -> {
          throw new IllegalStateException("external failure");
        });
    assertThrows(PriceUnavailableException.class, () -> identities.resolve(new Stack(null), empty));
  }

  @Test
  void onlyConfiguredStringPdcKeysIdentifyCustomItems() {
    var trusted = NamespacedKey.fromString("vendor:item_id");
    var unrelated = NamespacedKey.fromString("other:item_id");
    var item = new Stack(Map.of(trusted, "vendor:ruby", unrelated, "other:wrong"));
    var identities = new CustomIdentities();
    assertEquals("minecraft:diamond", identities.resolve(item, empty));
    assertEquals(
        "vendor:ruby", identities.resolve(item, new CustomPrices(List.of(trusted), Map.of())));
    assertEquals(
        "other:wrong", identities.resolve(item, new CustomPrices(List.of(unrelated), Map.of())));
  }

  @Test
  void vanillaFallbackDoesNotReadNamesOrLore() {
    var item = new Stack(Map.of());
    assertEquals("minecraft:diamond", new CustomIdentities().resolve(item, empty));
  }

  private static final class Stack extends ItemStack {
    private final Map<NamespacedKey, String> pdc;

    private Stack(Map<NamespacedKey, String> pdc) {
      super(Material.DIAMOND, 8);
      this.pdc = pdc;
    }

    @Override
    public boolean hasItemMeta() {
      return pdc != null;
    }

    @Override
    public Stack clone() {
      var copy = new Stack(pdc);
      copy.setAmount(getAmount());
      return copy;
    }

    @Override
    public ItemMeta getItemMeta() {
      PersistentDataContainer data =
          (PersistentDataContainer)
              Proxy.newProxyInstance(
                  getClass().getClassLoader(),
                  new Class<?>[] {PersistentDataContainer.class},
                  (proxy, method, args) -> {
                    if (method.getName().equals("get")) {
                      assertEquals(PersistentDataType.STRING, args[1]);
                      return pdc.get(args[0]);
                    }
                    throw new UnsupportedOperationException(method.getName());
                  });
      return (ItemMeta)
          Proxy.newProxyInstance(
              getClass().getClassLoader(),
              new Class<?>[] {ItemMeta.class},
              (proxy, method, args) -> {
                if (method.getName().equals("getPersistentDataContainer")) {
                  return data;
                }
                throw new UnsupportedOperationException(
                    "Names/lore must not be used for identity: " + method.getName());
              });
    }
  }
}
