package com.wellsetups.WellSell.storage;

import com.wellsetups.WellSell.api.CategoryContribution;
import com.wellsetups.WellSell.api.CategoryStatistics;
import com.wellsetups.WellSell.api.PlayerStatistics;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Uses the caller's history transaction, including its locked player totals row. */
final class StatisticsStore {
  private final boolean maria;

  StatisticsStore(boolean maria) {
    this.maria = maria;
  }

  void record(Connection connection, SaleRecord sale) throws SQLException {
    String insert = maria ? "INSERT IGNORE" : "INSERT OR IGNORE";
    try (PreparedStatement statement =
        connection.prepareStatement(
            insert
                + " INTO ws_stats (player,name,sales,earned_key,items,last_time) VALUES (?,'',0,?,0,0)")) {
      statement.setString(1, sale.player().toString());
      statement.setString(2, DecimalOrder.key(BigDecimal.ZERO));
      statement.executeUpdate();
    }
    updateGlobal(connection, sale);
    for (Map.Entry<String, CategoryContribution> entry :
        new TreeMap<>(sale.summary().categories()).entrySet()) {
      updateCategory(connection, sale.player(), entry.getKey(), entry.getValue(), insert);
    }
    try (PreparedStatement statement =
        connection.prepareStatement("INSERT INTO ws_sale_details (id,payload) VALUES (?,?)")) {
      statement.setString(1, sale.id().toString());
      statement.setString(2, SummaryCodec.encode(sale.summary()));
      statement.executeUpdate();
    }
  }

  private void updateGlobal(Connection connection, SaleRecord sale) throws SQLException {
    try (PreparedStatement query =
        connection.prepareStatement(
            "SELECT t.money,t.items,s.sales,s.name,s.last_time FROM ws_totals t JOIN ws_stats s ON s.player=t.player WHERE t.player=?"
                + (maria ? " FOR UPDATE" : ""))) {
      query.setString(1, sale.player().toString());
      try (ResultSet result = query.executeQuery()) {
        if (!result.next()) {
          throw new SQLException("Missing player statistics after insert");
        }
        boolean newer = sale.time().toEpochMilli() >= result.getLong(5);
        try (PreparedStatement update =
            connection.prepareStatement(
                "UPDATE ws_stats SET name=?,sales=?,earned_key=?,items=?,last_time=? WHERE player=?")) {
          update.setString(1, newer ? sale.playerName() : result.getString(4));
          update.setLong(2, Math.addExact(result.getLong(3), 1));
          update.setString(3, DecimalOrder.key(new BigDecimal(result.getString(1))));
          update.setLong(4, result.getLong(2));
          update.setLong(5, newer ? sale.time().toEpochMilli() : result.getLong(5));
          update.setString(6, sale.player().toString());
          update.executeUpdate();
        }
      }
    }
  }

  private void updateCategory(
      Connection connection,
      UUID player,
      String category,
      CategoryContribution contribution,
      String insert)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            insert
                + " INTO ws_categories (player,category,money,items,earned_key) VALUES (?,?,'0',0,?)")) {
      statement.setString(1, player.toString());
      statement.setString(2, category);
      statement.setString(3, DecimalOrder.key(BigDecimal.ZERO));
      statement.executeUpdate();
    }
    try (PreparedStatement query =
        connection.prepareStatement(
            "SELECT money,items FROM ws_categories WHERE player=? AND category=?"
                + (maria ? " FOR UPDATE" : ""))) {
      query.setString(1, player.toString());
      query.setString(2, category);
      try (ResultSet result = query.executeQuery()) {
        if (!result.next()) {
          throw new SQLException("Missing category statistics after insert");
        }
        BigDecimal earned = new BigDecimal(result.getString(1)).add(contribution.earned());
        long items = Math.addExact(result.getLong(2), contribution.items());
        try (PreparedStatement update =
            connection.prepareStatement(
                "UPDATE ws_categories SET money=?,items=?,earned_key=? WHERE player=? AND category=?")) {
          update.setString(1, earned.toPlainString());
          update.setLong(2, items);
          update.setString(3, DecimalOrder.key(earned));
          update.setString(4, player.toString());
          update.setString(5, category);
          update.executeUpdate();
        }
      }
    }
  }

  PlayerStatistics read(Connection connection, UUID player) throws SQLException {
    try (PreparedStatement query =
        connection.prepareStatement(
            "SELECT s.name,t.money,t.items,s.sales,t.last_money FROM ws_totals t JOIN ws_stats s ON s.player=t.player WHERE t.player=?")) {
      query.setString(1, player.toString());
      try (ResultSet result = query.executeQuery()) {
        if (!result.next()) {
          return PlayerStatistics.empty(player);
        }
        return new PlayerStatistics(
            player,
            result.getString(1),
            new BigDecimal(result.getString(2)),
            result.getLong(3),
            result.getLong(4),
            new BigDecimal(result.getString(5)),
            categories(connection, player));
      }
    }
  }

  private Map<String, CategoryStatistics> categories(Connection connection, UUID player)
      throws SQLException {
    Map<String, CategoryStatistics> categories = new HashMap<>();
    try (PreparedStatement query =
        connection.prepareStatement(
            "SELECT category,money,items FROM ws_categories WHERE player=?")) {
      query.setString(1, player.toString());
      try (ResultSet result = query.executeQuery()) {
        while (result.next()) {
          categories.put(
              result.getString(1),
              new CategoryStatistics(new BigDecimal(result.getString(2)), result.getLong(3)));
        }
      }
    }
    return categories;
  }
}
