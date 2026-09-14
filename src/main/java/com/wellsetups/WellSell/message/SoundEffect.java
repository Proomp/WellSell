package com.wellsetups.WellSell.message;

import com.wellsetups.WellSell.config.ConfigTree;
import com.wellsetups.WellSell.platform.SoundLookup;
import java.util.function.Consumer;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

public record SoundEffect(boolean enabled, Sound sound, float volume, float pitch) {
  public static SoundEffect load(ConfigTree tree, String path, Consumer<String> warning) {
    try {
      boolean enabled = tree.flag(path + ".enabled");
      Sound sound = SoundLookup.resolve(tree.text(path + ".name"));
      float volume = tree.decimal(path + ".volume").floatValue();
      float pitch = tree.decimal(path + ".pitch").floatValue();
      if (!Float.isFinite(volume)
          || !Float.isFinite(pitch)
          || volume < 0
          || volume > 4
          || pitch < 0.5
          || pitch > 2) {
        throw new IllegalArgumentException("volume must be 0..4; pitch must be 0.5..2");
      }
      return new SoundEffect(enabled, sound, volume, pitch);
    } catch (IllegalArgumentException failure) {
      warning.accept("Invalid sound at " + path + "; disabled: " + failure.getMessage());
      return new SoundEffect(false, Sound.BLOCK_NOTE_BLOCK_BASS, 0, 1);
    }
  }

  public void play(Player player) {
    if (enabled) {
      player.playSound(player.getLocation(), sound, volume, pitch);
    }
  }
}
