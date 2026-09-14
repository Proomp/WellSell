package com.wellsetups.WellSell.platform;

import org.bukkit.Sound;

/** Sound became an interface after 1.20. Resolve fields only during config loading. */
public final class SoundLookup {
  private SoundLookup() {}

  public static Sound resolve(String name) {
    if (!name.matches("[A-Z0-9_]{1,128}")) {
      throw new IllegalArgumentException("Use an uppercase Bukkit sound name");
    }
    try {
      Object value = Sound.class.getField(name).get(null);
      if (value instanceof Sound sound) {
        return sound;
      }
      throw new IllegalArgumentException("Not a sound constant: " + name);
    } catch (NoSuchFieldException | IllegalAccessException failure) {
      throw new IllegalArgumentException("Sound is unavailable on this server: " + name, failure);
    }
  }
}
