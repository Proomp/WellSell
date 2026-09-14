package com.wellsetups.WellSell.storage;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

final class DatabaseSchema {
  private final boolean maria;

  DatabaseSchema(boolean maria) {
    this.maria = maria;
  }

  void migrate(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.executeUpdate(
          "CREATE TABLE IF NOT EXISTS ws_schema (id INTEGER PRIMARY KEY, version INTEGER NOT NULL)");
    }
    int version = version(connection);
    if (version > 2 || version < 0) {
      throw new SQLException("Unsupported WellSell database schema " + version);
    }
    if (version == 0) {
      createOriginal(connection);
    }
    if (version < 2) {
      extend(connection);
    }
  }

  private static int version(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery("SELECT version FROM ws_schema WHERE id=1")) {
      return result.next() ? result.getInt(1) : 0;
    }
  }

  private void extend(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.executeUpdate(
          "CREATE TABLE IF NOT EXISTS ws_stats (player VARCHAR(36) PRIMARY KEY, name VARCHAR(32) NOT NULL, sales BIGINT NOT NULL, earned_key CHAR(76) NOT NULL, items BIGINT NOT NULL, last_time BIGINT NOT NULL)");
      statement.executeUpdate(
          "CREATE TABLE IF NOT EXISTS ws_categories (player VARCHAR(36) NOT NULL, category VARCHAR(32) NOT NULL, money VARCHAR(64) NOT NULL, items BIGINT NOT NULL, earned_key CHAR(76) NOT NULL, PRIMARY KEY (player,category))");
      statement.executeUpdate(
          "CREATE TABLE IF NOT EXISTS ws_sale_details (id VARCHAR(36) PRIMARY KEY, payload "
              + (maria ? "LONGTEXT" : "TEXT")
              + " NOT NULL)");
    }
    createIndex(connection, "ws_stats", "ws_earned_rank", "earned_key, player");
    createIndex(connection, "ws_stats", "ws_items_rank", "items, player");
    createIndex(connection, "ws_categories", "ws_category_earned", "category, earned_key, player");
    createIndex(connection, "ws_categories", "ws_category_items", "category, items, player");
    backfill(connection);
    try (Statement statement = connection.createStatement()) {
      statement.executeUpdate("UPDATE ws_schema SET version=2 WHERE id=1");
    }
  }

  private void backfill(Connection connection) throws SQLException {
    String cursor = "";
    while (true) {
      List<String> players = new ArrayList<>();
      try (PreparedStatement query =
          connection.prepareStatement(
              "SELECT player FROM ws_totals WHERE player>? ORDER BY player LIMIT 500")) {
        query.setString(1, cursor);
        try (ResultSet result = query.executeQuery()) {
          while (result.next()) {
            players.add(result.getString(1));
          }
        }
      }
      if (players.isEmpty()) {
        return;
      }
      for (String player : players) {
        backfillPlayer(connection, player);
      }
      cursor = players.get(players.size() - 1);
    }
  }

  private void backfillPlayer(Connection connection, String player) throws SQLException {
    String prefix = maria ? "INSERT IGNORE" : "INSERT OR IGNORE";
    try (PreparedStatement query =
        connection.prepareStatement(
            "SELECT money,items,last_time,(SELECT COUNT(*) FROM ws_sales WHERE player=?),(SELECT name FROM ws_sales WHERE player=? ORDER BY time_ms DESC,id DESC LIMIT 1) FROM ws_totals WHERE player=?")) {
      for (int index = 1; index <= 3; index++) {
        query.setString(index, player);
      }
      try (ResultSet result = query.executeQuery()) {
        if (!result.next()) {
          throw new SQLException("Missing legacy total during migration");
        }
        try (PreparedStatement insert =
            connection.prepareStatement(
                prefix
                    + " INTO ws_stats (player,name,sales,earned_key,items,last_time) VALUES (?,?,?,?,?,?)")) {
          insert.setString(1, player);
          insert.setString(2, result.getString(5) == null ? "" : result.getString(5));
          insert.setLong(3, result.getLong(4));
          insert.setString(4, DecimalOrder.key(new BigDecimal(result.getString(1))));
          insert.setLong(5, result.getLong(2));
          insert.setLong(6, result.getLong(3));
          insert.executeUpdate();
        }
      }
    }
  }

  private void createOriginal(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      if (!maria) {
        statement.execute("PRAGMA journal_mode=WAL");
      }
      statement.executeUpdate(
          "CREATE TABLE IF NOT EXISTS ws_schema (id INTEGER PRIMARY KEY, version INTEGER NOT NULL)");
      try (ResultSet result = statement.executeQuery("SELECT version FROM ws_schema WHERE id=1")) {
        if (result.next()) {
          if (result.getInt(1) != 1) {
            throw new SQLException(
                "Unsupported WellSell database schema; restore a compatible plugin version");
          }
          return;
        }
      }
      statement.executeUpdate(
          "CREATE TABLE IF NOT EXISTS ws_sales (id VARCHAR(36) PRIMARY KEY, player VARCHAR(36) NOT NULL, "
              + "name VARCHAR(32) NOT NULL, name_lower VARCHAR(32) NOT NULL, time_ms BIGINT NOT NULL, amount INTEGER NOT NULL, "
              + "money VARCHAR(64) NOT NULL, multiplier VARCHAR(32) NOT NULL, source VARCHAR(16) NOT NULL)");
      statement.executeUpdate(
          "CREATE TABLE IF NOT EXISTS ws_totals (player VARCHAR(36) PRIMARY KEY, "
              + "money VARCHAR(64) NOT NULL, items BIGINT NOT NULL, last_money VARCHAR(64) NOT NULL, last_time BIGINT NOT NULL)");
      // Metadata checks make interrupted DDL migrations restartable on MariaDB too.
      createIndex(connection, "ws_sales", "ws_player_time", "player, time_ms, id");
      createIndex(connection, "ws_sales", "ws_name_time", "name_lower, time_ms");
      statement.executeUpdate("INSERT INTO ws_schema (id, version) VALUES (1, 1)");
    }
  }

  static void createIndex(Connection connection, String table, String name, String columns)
      throws SQLException {
    try (ResultSet indexes =
        connection.getMetaData().getIndexInfo(connection.getCatalog(), null, table, false, false)) {
      while (indexes.next()) {
        if (name.equalsIgnoreCase(indexes.getString("INDEX_NAME"))) {
          return;
        }
      }
    }
    try (Statement statement = connection.createStatement()) {
      // All identifiers originate in the fixed migration above, never configuration/input.
      statement.executeUpdate("CREATE INDEX " + name + " ON " + table + " (" + columns + ")");
    }
  }
}
