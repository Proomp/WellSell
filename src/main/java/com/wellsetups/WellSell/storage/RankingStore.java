package com.wellsetups.WellSell.storage;

import com.wellsetups.WellSell.api.Leaderboard;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class RankingStore {
  private RankingStore() {}

  private static String categoryFilter(String category) {
    if (category == null) {
      return "";
    }
    if (!category.matches("[a-z][a-z0-9_]{0,31}")) {
      throw new IllegalArgumentException("Invalid leaderboard category");
    }
    return " WHERE r.category=?";
  }

  static Leaderboard leaderboard(
      Connection connection, Leaderboard.Mode mode, String category, Page page)
      throws SQLException {
    String filter = categoryFilter(category);
    String table = category == null ? "ws_stats" : "ws_categories";
    long rows;
    try (PreparedStatement count =
        connection.prepareStatement("SELECT COUNT(*) FROM " + table + " r" + filter)) {
      if (category != null) {
        count.setString(1, category);
      }
      try (ResultSet result = count.executeQuery()) {
        rows = result.next() ? result.getLong(1) : 0;
      }
    }
    String money = category == null ? "t.money" : "r.money";
    String join = category == null ? " JOIN ws_totals t ON t.player=r.player" : "";
    String order = mode == Leaderboard.Mode.TOTAL_EARNED ? "r.earned_key" : "r.items";
    List<Leaderboard.Entry> entries = new ArrayList<>();
    try (PreparedStatement query =
        connection.prepareStatement(
            "SELECT r.player,s.name,"
                + money
                + ",r.items FROM "
                + table
                + " r JOIN ws_stats s ON s.player=r.player"
                + join
                + filter
                + " ORDER BY "
                + order
                + " DESC,r.player ASC LIMIT ? OFFSET ?")) {
      int index = 1;
      if (category != null) {
        query.setString(index++, category);
      }
      query.setInt(index++, page.size());
      query.setLong(index, page.offset());
      try (ResultSet result = query.executeQuery()) {
        while (result.next()) {
          entries.add(
              new Leaderboard.Entry(
                  page.offset() + entries.size() + 1,
                  UUID.fromString(result.getString(1)),
                  result.getString(2),
                  new BigDecimal(result.getString(3)),
                  result.getLong(4)));
        }
      }
    }
    return new Leaderboard(entries, page.maximum(rows));
  }

  static List<Leaderboard.Entry> top(Connection connection, Leaderboard.Mode mode, int limit)
      throws SQLException {
    if (limit < 1 || limit > 100) {
      throw new IllegalArgumentException("Top position limit must be 1..100");
    }
    String order = mode == Leaderboard.Mode.TOTAL_EARNED ? "s.earned_key" : "s.items";
    List<Leaderboard.Entry> entries = new ArrayList<>();
    try (PreparedStatement query =
        connection.prepareStatement(
            "SELECT s.player,s.name,t.money,s.items FROM ws_stats s JOIN ws_totals t ON t.player=s.player ORDER BY "
                + order
                + " DESC,s.player ASC LIMIT ?")) {
      query.setInt(1, limit);
      try (ResultSet result = query.executeQuery()) {
        while (result.next()) {
          entries.add(
              new Leaderboard.Entry(
                  entries.size() + 1L,
                  UUID.fromString(result.getString(1)),
                  result.getString(2),
                  new BigDecimal(result.getString(3)),
                  result.getLong(4)));
        }
      }
    }
    return List.copyOf(entries);
  }

  static long rank(Connection connection, UUID player, Leaderboard.Mode mode, String category)
      throws SQLException {
    categoryFilter(category);
    String table = category == null ? "ws_stats" : "ws_categories";
    String column = mode == Leaderboard.Mode.TOTAL_EARNED ? "earned_key" : "items";
    String filter = category == null ? "" : " AND r.category=?";
    String value;
    try (PreparedStatement query =
        connection.prepareStatement(
            "SELECT r." + column + " FROM " + table + " r WHERE r.player=?" + filter)) {
      query.setString(1, player.toString());
      if (category != null) {
        query.setString(2, category);
      }
      try (ResultSet result = query.executeQuery()) {
        if (!result.next()) {
          return 0;
        }
        value = result.getString(1);
      }
    }
    try (PreparedStatement query =
        connection.prepareStatement(
            "SELECT COUNT(*) FROM "
                + table
                + " r WHERE (r."
                + column
                + ">? OR (r."
                + column
                + "=? AND r.player<?))"
                + filter)) {
      if (mode == Leaderboard.Mode.TOTAL_EARNED) {
        query.setString(1, value);
        query.setString(2, value);
      } else {
        query.setLong(1, Long.parseLong(value));
        query.setLong(2, Long.parseLong(value));
      }
      query.setString(3, player.toString());
      if (category != null) {
        query.setString(4, category);
      }
      try (ResultSet result = query.executeQuery()) {
        return result.next() ? result.getLong(1) + 1 : 0;
      }
    }
  }
}
