package com.wellsetups.WellSell.command;

import java.util.Locale;

public enum PermissionId {
  SELL,
  SELL_HAND,
  SELL_INVENTORY,
  GUI,
  PROGRESS,
  WORTH,
  WORTH_LORE,
  HISTORY,
  TOP,
  HISTORY_OTHERS,
  RELOAD,
  PRICE_IMPORT,
  MIGRATE,
  ADMIN;

  public String key() {
    return name().toLowerCase(Locale.ROOT).replace('_', '-');
  }
}
