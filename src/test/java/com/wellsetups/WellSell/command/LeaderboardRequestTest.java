package com.wellsetups.WellSell.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.wellsetups.WellSell.api.Leaderboard;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LeaderboardRequestTest {
  @Test
  void categoryPageAndModeAreUnambiguousInEitherOrder() {
    var parsed =
        LeaderboardRequest.parse(
            new String[] {"ores", "2", "--items"},
            Set.of("ores"),
            8,
            Leaderboard.Mode.TOTAL_EARNED);
    assertEquals("ores", parsed.category());
    assertEquals(2, parsed.page().number());
    assertEquals(Leaderboard.Mode.TOTAL_ITEMS_SOLD, parsed.mode());
    assertEquals(
        parsed,
        LeaderboardRequest.parse(
            new String[] {"--items", "ores", "2"},
            Set.of("ores"),
            8,
            Leaderboard.Mode.TOTAL_EARNED));
  }

  @Test
  void unknownCategoriesDuplicateArgumentsAndOutOfRangePagesAreRejected() {
    for (String[] args :
        new String[][] {
          {"unknown"}, {"0"}, {"1", "2"}, {"--items", "--earned"}, {"ores", "ores"}
        }) {
      assertThrows(
          IllegalArgumentException.class,
          () -> LeaderboardRequest.parse(args, Set.of("ores"), 8, Leaderboard.Mode.TOTAL_EARNED));
    }
  }
}
