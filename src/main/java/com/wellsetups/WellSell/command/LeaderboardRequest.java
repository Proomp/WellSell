package com.wellsetups.WellSell.command;

import com.wellsetups.WellSell.api.Leaderboard;
import com.wellsetups.WellSell.storage.Page;
import java.util.Locale;
import java.util.Set;

public record LeaderboardRequest(String category, Page page, Leaderboard.Mode mode) {
  public static LeaderboardRequest parse(
      String[] args, Set<String> categories, int size, Leaderboard.Mode defaultMode) {
    if (args.length > 3) {
      throw new IllegalArgumentException("Too many leaderboard arguments");
    }
    String category = null;
    Integer number = null;
    Leaderboard.Mode mode = null;
    for (String argument : args) {
      String value = argument.toLowerCase(Locale.ROOT);
      if (value.startsWith("--")) {
        if (mode != null) {
          throw new IllegalArgumentException("Duplicate leaderboard mode");
        }
        mode = parseMode(value);
      } else if (value.matches("[+-]?[0-9]+")) {
        if (number != null) {
          throw new IllegalArgumentException("Duplicate leaderboard page");
        }
        number = Integer.valueOf(value);
      } else {
        if (category != null || !categories.contains(value)) {
          throw new IllegalArgumentException("Unknown or duplicate leaderboard category");
        }
        category = value;
      }
    }
    return new LeaderboardRequest(
        category, new Page(number == null ? 1 : number, size), mode == null ? defaultMode : mode);
  }

  private static Leaderboard.Mode parseMode(String mode) {
    return switch (mode) {
      case "--earned" -> Leaderboard.Mode.TOTAL_EARNED;
      case "--items" -> Leaderboard.Mode.TOTAL_ITEMS_SOLD;
      default -> throw new IllegalArgumentException("Unknown leaderboard mode");
    };
  }
}
