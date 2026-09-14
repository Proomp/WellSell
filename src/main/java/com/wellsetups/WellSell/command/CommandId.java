package com.wellsetups.WellSell.command;

import java.util.Locale;

public enum CommandId {
  SELL,
  WORTH,
  HISTORY,
  TOP,
  ADMIN;

  public String key() {
    return name().toLowerCase(Locale.ROOT);
  }
}
