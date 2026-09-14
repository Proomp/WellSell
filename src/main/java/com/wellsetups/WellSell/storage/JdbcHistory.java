package com.wellsetups.WellSell.storage;

import com.wellsetups.WellSell.config.ConfigTree;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class JdbcHistory implements HistoryRepository, AutoCloseable {
  @FunctionalInterface
  private interface DatabaseWork<T> {
    T run() throws SQLException, IOException;
  }

  private final ThreadPoolExecutor worker;
  private final Logger logger;
  private final ConfigTree config;
  private final Path folder;
  private final boolean maria;
  private final Consumer<UUID> quarantine;
  private volatile boolean ready;
  private RecoveryJournal journal;

  public JdbcHistory(ConfigTree config, Path folder, Logger logger, Consumer<UUID> quarantine) {
    this.config = config;
    this.folder = folder;
    this.logger = logger;
    this.quarantine = quarantine;
    maria = config.text("type").equalsIgnoreCase("MARIADB");
    worker =
        new ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1024),
            task -> {
              Thread thread = new Thread(task, "WellSell-storage");
              // A normal JVM shutdown drains accepted journal writes without blocking a tick
              // thread.
              thread.setDaemon(false);
              return thread;
            },
            new ThreadPoolExecutor.AbortPolicy());
  }

  public CompletableFuture<Void> initialize() {
    return submit(
        () -> {
          try {
            Class.forName(maria ? "org.mariadb.jdbc.Driver" : "org.sqlite.JDBC");
          } catch (ClassNotFoundException exception) {
            throw new SQLException("JDBC driver missing from distribution", exception);
          }
          Files.createDirectories(folder);
          journal = new RecoveryJournal(folder.resolve("audit"));
          try (Connection connection = connect()) {
            new DatabaseSchema(maria).migrate(connection);
          }
          recover();
          ready = true;
          return null;
        });
  }

  public boolean ready() {
    return ready && !worker.isShutdown();
  }

  public CompletableFuture<Void> prepare(SaleRecord sale) {
    return submit(
        () -> {
          if (!ready) {
            throw new SQLException("Storage is not ready");
          }
          journal.write(sale, "PREPARED");
          return null;
        });
  }

  public CompletableFuture<Void> finish(SaleRecord sale, String state) {
    return submit(
        () -> {
          journal.write(sale, state);
          if (state.equals("SUCCESS")) {
            save(sale);
            journal.delete(sale.id());
          } else if (state.equals("CANCELLED")
              || state.equals("REJECTED")
              || state.equals("CHANGED")) {
            journal.delete(sale.id());
          }
          return null;
        });
  }

  private void recover() throws IOException, SQLException {
    try (var paths = Files.newDirectoryStream(journal.directory(), "*.properties")) {
      for (Path path : paths) {
        RecoveryJournal.Entry entry = journal.read(path);
        switch (entry.state()) {
          case "SUCCESS" -> {
            save(entry.sale());
            journal.delete(entry.sale().id());
          }
          case "REJECTED", "CHANGED", "CANCELLED" -> journal.delete(entry.sale().id());
          default -> {
            quarantine.accept(entry.sale().player());
            logger.severe(
                "Reconcile unresolved sale "
                    + entry.sale().id()
                    + " for "
                    + entry.sale().player()
                    + " using "
                    + path
                    + "; no deposit or item replay attempted.");
          }
        }
      }
    }
  }

  private Connection connect() throws SQLException {
    if (maria) {
      return DriverManager.getConnection(
          config.text("mariadb.url"),
          config.text("mariadb.username"),
          config.text("mariadb.password"));
    }
    Path database = folder.resolve(config.text("sqlite-file")).normalize();
    if (!database.getParent().equals(folder.normalize())) {
      throw new SQLException("sqlite-file must be a file name inside the plugin folder");
    }
    Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
    try (Statement statement = connection.createStatement()) {
      statement.execute("PRAGMA busy_timeout=5000");
    } catch (SQLException exception) {
      connection.close();
      throw exception;
    }
    return connection;
  }

  private void save(SaleRecord sale) throws SQLException {
    try (Connection connection = connect()) {
      connection.setAutoCommit(false);
      try {
        String insert = maria ? "INSERT IGNORE" : "INSERT OR IGNORE";
        try (PreparedStatement statement =
            connection.prepareStatement(
                insert
                    + " INTO ws_sales (id,player,name,name_lower,time_ms,amount,money,multiplier,source) VALUES (?,?,?,?,?,?,?,?,?)")) {
          statement.setString(1, sale.id().toString());
          statement.setString(2, sale.player().toString());
          statement.setString(3, sale.playerName());
          statement.setString(4, sale.playerName().toLowerCase(Locale.ROOT));
          statement.setLong(5, sale.time().toEpochMilli());
          statement.setInt(6, sale.amount());
          statement.setString(7, sale.money().toPlainString());
          statement.setString(8, sale.multiplier().toPlainString());
          statement.setString(9, sale.source());
          if (statement.executeUpdate() > 0) {
            updateTotals(connection, sale, insert);
            new StatisticsStore(maria).record(connection, sale);
          }
        }
        connection.commit();
      } catch (SQLException | RuntimeException exception) {
        connection.rollback();
        throw exception;
      }
    }
  }

  private void updateTotals(Connection connection, SaleRecord sale, String insert)
      throws SQLException {
    String player = sale.player().toString();
    try (PreparedStatement statement =
        connection.prepareStatement(
            insert
                + " INTO ws_totals (player,money,items,last_money,last_time) VALUES (?,'0',0,'0',0)")) {
      statement.setString(1, player);
      statement.executeUpdate();
    }
    try (PreparedStatement read =
        connection.prepareStatement(
            "SELECT money,items,last_money,last_time FROM ws_totals WHERE player=?"
                + (maria ? " FOR UPDATE" : ""))) {
      read.setString(1, player);
      try (ResultSet current = read.executeQuery()) {
        if (!current.next()) {
          throw new SQLException("Totals row missing after insert");
        }
        BigDecimal money = new BigDecimal(current.getString(1)).add(sale.money());
        long items = Math.addExact(current.getLong(2), sale.amount());
        boolean newer = sale.time().toEpochMilli() >= current.getLong(4);
        try (PreparedStatement update =
            connection.prepareStatement(
                "UPDATE ws_totals SET money=?, items=?, last_money=?, last_time=? WHERE player=?")) {
          update.setString(1, money.toPlainString());
          update.setLong(2, items);
          update.setString(3, newer ? sale.money().toPlainString() : current.getString(3));
          update.setLong(4, newer ? sale.time().toEpochMilli() : current.getLong(4));
          update.setString(5, player);
          update.executeUpdate();
        }
      }
    }
  }

  @Override
  public CompletableFuture<HistoryPage> history(UUID player, Page page) {
    return submit(
        () -> {
          try (Connection connection = connect();
              PreparedStatement count =
                  connection.prepareStatement("SELECT COUNT(*) FROM ws_sales WHERE player=?")) {
            count.setString(1, player.toString());
            long rows;
            try (ResultSet result = count.executeQuery()) {
              rows = result.next() ? result.getLong(1) : 0;
            }
            List<SaleRecord> records = new ArrayList<>();
            try (PreparedStatement query =
                connection.prepareStatement(
                    "SELECT s.id,s.player,s.name,s.time_ms,s.amount,s.money,s.multiplier,s.source,d.payload FROM ws_sales s LEFT JOIN ws_sale_details d ON d.id=s.id WHERE s.player=? ORDER BY s.time_ms DESC,s.id DESC LIMIT ? OFFSET ?")) {
              query.setString(1, player.toString());
              query.setInt(2, page.size());
              query.setLong(3, page.offset());
              try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                  records.add(
                      new SaleRecord(
                          UUID.fromString(result.getString(1)),
                          UUID.fromString(result.getString(2)),
                          result.getString(3),
                          Instant.ofEpochMilli(result.getLong(4)),
                          result.getInt(5),
                          new BigDecimal(result.getString(6)),
                          new BigDecimal(result.getString(7)),
                          result.getString(8),
                          "",
                          SummaryCodec.decode(result.getString(9))));
                }
              }
            }
            return new HistoryPage(records, page.maximum(rows));
          }
        });
  }

  @Override
  public CompletableFuture<Optional<UUID>> findPlayer(String name) {
    return submit(
        () -> {
          try (Connection connection = connect();
              PreparedStatement query =
                  connection.prepareStatement(
                      "SELECT player FROM ws_sales WHERE name_lower=? ORDER BY time_ms DESC LIMIT 1")) {
            query.setString(1, name.toLowerCase(Locale.ROOT));
            try (ResultSet result = query.executeQuery()) {
              return result.next()
                  ? Optional.of(UUID.fromString(result.getString(1)))
                  : Optional.empty();
            }
          }
        });
  }

  @Override
  public CompletableFuture<PlayerTotals> totals(UUID player) {
    return submit(
        () -> {
          try (Connection connection = connect();
              PreparedStatement query =
                  connection.prepareStatement(
                      "SELECT money,items,last_money FROM ws_totals WHERE player=?")) {
            query.setString(1, player.toString());
            try (ResultSet result = query.executeQuery()) {
              return result.next()
                  ? new PlayerTotals(
                      new BigDecimal(result.getString(1)),
                      result.getLong(2),
                      new BigDecimal(result.getString(3)))
                  : PlayerTotals.empty();
            }
          }
        });
  }

  public CompletableFuture<com.wellsetups.WellSell.api.PlayerStatistics> statistics(UUID player) {
    return submit(
        () -> {
          try (Connection connection = connect()) {
            return new StatisticsStore(maria).read(connection, player);
          }
        });
  }

  public CompletableFuture<com.wellsetups.WellSell.api.Leaderboard> leaderboard(
      com.wellsetups.WellSell.api.Leaderboard.Mode mode, String category, Page page) {
    return submit(
        () -> {
          try (Connection connection = connect()) {
            return RankingStore.leaderboard(connection, mode, category, page);
          }
        });
  }

  public CompletableFuture<Long> rank(
      UUID player, com.wellsetups.WellSell.api.Leaderboard.Mode mode, String category) {
    return submit(
        () -> {
          try (Connection connection = connect()) {
            return RankingStore.rank(connection, player, mode, category);
          }
        });
  }

  public CompletableFuture<java.util.List<com.wellsetups.WellSell.api.Leaderboard.Entry>> top(
      com.wellsetups.WellSell.api.Leaderboard.Mode mode, int limit) {
    return submit(
        () -> {
          try (Connection connection = connect()) {
            return RankingStore.top(connection, mode, limit);
          }
        });
  }

  public CompletableFuture<java.util.Map<String, Long>> migrateToMaria() {
    return submit(
        () -> {
          if (maria || !ready()) {
            throw new SQLException("Migration requires the ready SQLite backend");
          }
          try {
            Class.forName("org.mariadb.jdbc.Driver");
          } catch (ClassNotFoundException failure) {
            throw new SQLException("MariaDB JDBC library is unavailable", failure);
          }
          try (Connection source = connect();
              Connection target =
                  DriverManager.getConnection(
                      config.text("mariadb.url"),
                      config.text("mariadb.username"),
                      config.text("mariadb.password"))) {
            return DatabaseCopy.copy(source, target, true);
          }
        });
  }

  private <T> CompletableFuture<T> submit(DatabaseWork<T> work) {
    CompletableFuture<T> future = new CompletableFuture<>();
    try {
      worker.execute(
          () -> {
            try {
              future.complete(work.run());
            } catch (SQLException | IOException | RuntimeException failure) {
              future.completeExceptionally(failure);
            }
          });
    } catch (RejectedExecutionException failure) {
      future.completeExceptionally(failure);
    }
    return future;
  }

  public void logFailure(String context, Throwable failure) {
    logger.log(
        Level.SEVERE, context + "; inspect plugins/WellSell/audit before reconciliation.", failure);
  }

  @Override
  public void close() {
    ready = false;
    worker.shutdown();
  }
}
