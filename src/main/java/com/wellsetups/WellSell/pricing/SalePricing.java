package com.wellsetups.WellSell.pricing;

import com.wellsetups.WellSell.config.ConfigTree;
import com.wellsetups.WellSell.config.Settings;
import com.wellsetups.WellSell.sell.BukkitInventoryAccess;
import com.wellsetups.WellSell.sell.SellPlan;
import java.math.BigDecimal;
import java.util.function.Function;
import org.bukkit.entity.Player;

public final class SalePricing {
  public record PricedSale(SellPlan plan, Runnable afterPayment) {}

  private final com.wellsetups.WellSell.storage.PlayerStatisticsCache statistics;
  private final java.util.function.Predicate<PriceSource> available;
  private final CustomIdentities identities;
  private final ContainerReader containers = new ContainerReader();
  private final PriceSource source;
  private final boolean applyMultiplier;
  private final Function<PriceSource, ShopPrices> shops;

  public SalePricing(
      ConfigTree config,
      Function<PriceSource, ShopPrices> shops,
      com.wellsetups.WellSell.storage.PlayerStatisticsCache statistics,
      java.util.function.Predicate<PriceSource> available,
      CustomIdentities identities) {
    this.statistics = statistics;
    this.available = available;
    this.identities = identities;
    source = PriceSource.parse(config.text("prices.source"));
    applyMultiplier = config.flag("prices.apply-wellsell-multiplier");
    this.shops = shops;
  }

  public PriceSource source() {
    return source;
  }

  public BigDecimal multiplier(Player player, Settings snapshot) {
    boolean apply =
        snapshot.expansion().pricing().enabled()
            ? snapshot.expansion().pricing().multiplier()
            : source == PriceSource.CONFIG || applyMultiplier;
    return apply ? snapshot.multipliers().select(player::hasPermission) : BigDecimal.ONE;
  }

  public PricedSale quote(Player player, BukkitInventoryAccess inventory, Settings snapshot) {
    try {
      return quoteSelection(player, inventory, snapshot);
    } catch (PriceUnavailableException failure) {
      throw failure;
    } catch (IllegalArgumentException failure) {
      throw failure;
    } catch (RuntimeException | LinkageError failure) {
      throw new PriceUnavailableException("Cannot quote selected price source: " + source, failure);
    }
  }

  private PricedSale quoteSelection(
      Player player, BukkitInventoryAccess inventory, Settings snapshot) {
    BigDecimal multiplier = multiplier(player, snapshot);
    java.util.Map<org.bukkit.inventory.ItemStack, String> identityCache = new java.util.HashMap<>();
    Function<org.bukkit.inventory.ItemStack, String> resolved =
        item ->
            identityCache.computeIfAbsent(
                item, key -> identities.resolve(key, snapshot.expansion().custom()));
    var selection =
        ContainerSelection.expand(
            inventory.items(true),
            snapshot.expansion().containers(),
            snapshot.general().flag("sell.allow-metadata"),
            containers,
            item -> !resolved.apply(item).equals(CustomIdentities.vanilla(item)));
    if (selection.groups().isEmpty()) {
      return new PricedSale(
          SellPlan.quoted(java.util.List.of(), BigDecimal.ZERO, snapshot.money(), multiplier),
          () -> {});
    }
    var stats = statistics.require(player.getUniqueId());
    if (snapshot.expansion().progression().enabled() && stats == null) {
      throw new PriceUnavailableException("Player progression is loading; try again shortly");
    }
    for (int attempt = 0; attempt <= 36; attempt++) {
      var items = selection.items();
      ProviderPipeline.Result result = sources(player, items, snapshot, resolved);
      ShopQuote quote = result.quote();
      if (!items.keySet().containsAll(quote.slots())) {
        throw new PriceUnavailableException("Shop returned unrequested components");
      }
      var complete = selection.completeGroups(quote.slots());
      if (complete.items().size() == quote.slots().size()) {
        return new PricedSale(
            CategoryPricing.containers(
                complete,
                quote,
                snapshot.money(),
                multiplier,
                snapshot.expansion(),
                stats,
                result.source(),
                resolved),
            quote.afterPayment());
      }
      selection = complete;
    }
    throw new PriceUnavailableException("Shop container selection did not converge");
  }

  public CustomIdentities identities() {
    return identities;
  }

  public boolean localOnly(Settings snapshot) {
    return !snapshot.expansion().pricing().enabled() && source == PriceSource.CONFIG;
  }

  public String description(Settings snapshot) {
    return snapshot.expansion().pricing().enabled()
        ? snapshot.expansion().pricing().strategy().name()
        : source.name();
  }

  private ProviderPipeline.Result sources(
      Player player,
      java.util.Map<Integer, org.bukkit.inventory.ItemStack> items,
      Settings snapshot,
      Function<org.bukkit.inventory.ItemStack, String> identity) {
    if (items.isEmpty()) {
      return new ProviderPipeline.Result(ShopQuote.empty(), "NONE");
    }
    if (!snapshot.expansion().pricing().enabled()) {
      ShopQuote quote =
          source == PriceSource.CONFIG
              ? configured(items, snapshot, identity, false)
              : shops.apply(source).quote(player, items);
      return new ProviderPipeline.Result(quote, source.name());
    }
    return ProviderPipeline.quote(
        snapshot.expansion().pricing(),
        items.keySet(),
        (provider, keys) -> {
          java.util.Map<Integer, org.bukkit.inventory.ItemStack> requested =
              new java.util.TreeMap<>();
          keys.forEach(key -> requested.put(key, items.get(key).clone()));
          if (provider == PricingSettings.Provider.CUSTOM_ITEMS
              || provider == PricingSettings.Provider.WELLSELL_PRICES) {
            return java.util.Optional.of(
                configured(
                    requested,
                    snapshot,
                    identity,
                    provider == PricingSettings.Provider.CUSTOM_ITEMS));
          }
          PriceSource external = PriceSource.valueOf(provider.name());
          return available.test(external)
              ? java.util.Optional.of(shops.apply(external).quote(player, requested, true))
              : java.util.Optional.empty();
        });
  }

  private static ShopQuote configured(
      java.util.Map<Integer, org.bukkit.inventory.ItemStack> items,
      Settings snapshot,
      Function<org.bukkit.inventory.ItemStack, String> identity,
      boolean custom) {
    java.util.Map<Integer, BigDecimal> values = new java.util.TreeMap<>();
    items.forEach(
        (key, item) -> {
          String id = identity.apply(item);
          var price =
              custom
                  ? snapshot.expansion().custom().price(id)
                  : id.equals(CustomIdentities.vanilla(item))
                      ? snapshot.prices().unitPrice(item.getType().name())
                      : java.util.Optional.<BigDecimal>empty();
          price.ifPresent(
              value -> values.put(key, value.multiply(BigDecimal.valueOf(item.getAmount()))));
        });
    return new ShopQuote(
        values.keySet(),
        values.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add),
        values,
        () -> {});
  }
}
