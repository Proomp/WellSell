package com.wellsetups.WellSell.storage;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Add-only, resumable copy. Existing target rows must match; source rows are never written. */
final class DatabaseCopy {
  private record Table(String name, List<String> keys, List<String> columns) {}

  private static final List<Table> TABLES =
      List.of(
          table(
              "ws_sales", "id", "id,player,name,name_lower,time_ms,amount,money,multiplier,source"),
          table("ws_totals", "player", "player,money,items,last_money,last_time"),
          table("ws_stats", "player", "player,name,sales,earned_key,items,last_time"),
          table("ws_categories", "player,category", "player,category,money,items,earned_key"),
          table("ws_sale_details", "id", "id,payload"));

  private DatabaseCopy() {}

  private static Table table(String name, String keys, String columns) {
    return new Table(name, List.of(keys.split(",")), List.of(columns.split(",")));
  }

  static Map<String, Long> copy(Connection source, Connection target, boolean maria)
      throws SQLException {
    new DatabaseSchema(maria).migrate(target);
    source.setAutoCommit(false);
    target.setAutoCommit(false);
    try {
      Map<String, Long> counts = new LinkedHashMap<>();
      for (Table table : TABLES) {
        copyTable(source, target, table);
        var original = checksum(source, table, false);
        var copied = checksum(target, table, maria);
        if (original.rows() != copied.rows()
            || !Arrays.equals(original.digest(), copied.digest())) {
          throw new SQLException(
              "Migration verification mismatch for "
                  + table.name()
                  + "; target may contain unrelated data");
        }
        counts.put(table.name(), original.rows());
      }
      target.commit();
      source.rollback();
      return Map.copyOf(counts);
    } catch (SQLException | RuntimeException failure) {
      target.rollback();
      source.rollback();
      throw failure;
    }
  }

  private static void copyTable(Connection source, Connection target, Table table)
      throws SQLException {
    List<String> cursor = List.of();
    while (true) {
      List<List<String>> rows = page(source, table, cursor, false);
      if (rows.isEmpty()) {
        return;
      }
      for (List<String> row : rows) {
        insertOrVerify(target, table, row);
      }
      target.commit();
      cursor = keys(table, rows.get(rows.size() - 1));
    }
  }

  private static void insertOrVerify(Connection target, Table table, List<String> row)
      throws SQLException {
    String where = String.join(" AND ", table.keys().stream().map(key -> key + "=?").toList());
    try (PreparedStatement query =
        target.prepareStatement(
            "SELECT "
                + String.join(",", table.columns())
                + " FROM "
                + table.name()
                + " WHERE "
                + where)) {
      bind(query, keys(table, row));
      try (ResultSet result = query.executeQuery()) {
        if (result.next()) {
          if (!row.equals(row(result, table.columns().size()))) {
            throw new SQLException("Existing target data conflicts in " + table.name());
          }
          return;
        }
      }
    }
    String placeholders =
        String.join(",", java.util.Collections.nCopies(table.columns().size(), "?"));
    try (PreparedStatement insert =
        target.prepareStatement(
            "INSERT INTO "
                + table.name()
                + " ("
                + String.join(",", table.columns())
                + ") VALUES ("
                + placeholders
                + ")")) {
      bind(insert, row);
      insert.executeUpdate();
    }
  }

  private record Checksum(long rows, byte[] digest) {}

  private static Checksum checksum(Connection connection, Table table, boolean maria)
      throws SQLException {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is required by Java", impossible);
    }
    long count = 0;
    List<String> cursor = List.of();
    while (true) {
      List<List<String>> rows = page(connection, table, cursor, maria);
      if (rows.isEmpty()) {
        return new Checksum(count, digest.digest());
      }
      for (List<String> row : rows) {
        for (String value : row) {
          byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
          digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
          digest.update(bytes);
        }
        count = Math.addExact(count, 1);
      }
      cursor = keys(table, rows.get(rows.size() - 1));
    }
  }

  private static List<List<String>> page(
      Connection connection, Table table, List<String> cursor, boolean maria) throws SQLException {
    List<String> columns = table.keys().stream().map(key -> maria ? "BINARY " + key : key).toList();
    List<String> conditions = new ArrayList<>();
    List<String> arguments = new ArrayList<>();
    if (!cursor.isEmpty()) {
      for (int index = 0; index < columns.size(); index++) {
        List<String> parts = new ArrayList<>();
        for (int prior = 0; prior <= index; prior++) {
          parts.add(columns.get(prior) + (prior == index ? ">?" : "=?"));
          arguments.add(cursor.get(prior));
        }
        conditions.add("(" + String.join(" AND ", parts) + ")");
      }
    }
    String where = conditions.isEmpty() ? "" : " WHERE " + String.join(" OR ", conditions);
    List<List<String>> rows = new ArrayList<>();
    try (PreparedStatement query =
        connection.prepareStatement(
            "SELECT "
                + String.join(",", table.columns())
                + " FROM "
                + table.name()
                + where
                + " ORDER BY "
                + String.join(",", columns)
                + " LIMIT 500")) {
      bind(query, arguments);
      try (ResultSet result = query.executeQuery()) {
        while (result.next()) {
          rows.add(row(result, table.columns().size()));
        }
      }
    }
    return rows;
  }

  private static List<String> keys(Table table, List<String> row) {
    return table.keys().stream().map(key -> row.get(table.columns().indexOf(key))).toList();
  }

  private static List<String> row(ResultSet result, int columns) throws SQLException {
    List<String> row = new ArrayList<>();
    for (int index = 1; index <= columns; index++) {
      row.add(result.getString(index));
    }
    return List.copyOf(row);
  }

  private static void bind(PreparedStatement statement, List<String> values) throws SQLException {
    for (int index = 0; index < values.size(); index++) {
      statement.setString(index + 1, values.get(index));
    }
  }
}
