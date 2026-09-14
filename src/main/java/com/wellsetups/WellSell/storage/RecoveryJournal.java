package com.wellsetups.WellSell.storage;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Properties;
import java.util.UUID;

/** Accessed exclusively by the storage worker. No item replay is performed. */
final class RecoveryJournal {
  record Entry(SaleRecord sale, String state) {}

  private final Path directory;

  RecoveryJournal(Path directory) throws IOException {
    this.directory = directory;
    Files.createDirectories(directory);
  }

  Path directory() {
    return directory;
  }

  void write(SaleRecord sale, String state) throws IOException {
    Properties data = new Properties();
    data.setProperty("schema", "1");
    data.setProperty("state", state);
    data.setProperty("id", sale.id().toString());
    data.setProperty("player", sale.player().toString());
    data.setProperty("name", sale.playerName());
    data.setProperty("time", sale.time().toString());
    data.setProperty("amount", Integer.toString(sale.amount()));
    data.setProperty("money", sale.money().toPlainString());
    data.setProperty("multiplier", sale.multiplier().toPlainString());
    data.setProperty("source", sale.source());
    data.setProperty("items-base64", sale.recoveryItems());
    data.setProperty("summary", SummaryCodec.encode(sale.summary()));
    StringWriter writer = new StringWriter();
    data.store(writer, "WellSell audit intent; never replay deposits automatically");
    Path temporary = directory.resolve(sale.id() + ".tmp");
    try (FileChannel channel =
        FileChannel.open(
            temporary,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE)) {
      ByteBuffer bytes = StandardCharsets.UTF_8.encode(writer.toString());
      while (bytes.hasRemaining()) {
        channel.write(bytes);
      }
      channel.force(true);
    }
    // Require atomic rename; a filesystem that cannot provide it must fail before sale.
    Files.move(
        temporary,
        path(sale.id()),
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING);
  }

  Entry read(Path file) throws IOException {
    Properties data = new Properties();
    data.load(new StringReader(Files.readString(file, StandardCharsets.UTF_8)));
    if (!"1".equals(data.getProperty("schema"))) {
      throw new IOException("Unsupported audit schema in " + file);
    }
    SaleRecord sale =
        new SaleRecord(
            UUID.fromString(data.getProperty("id")),
            UUID.fromString(data.getProperty("player")),
            data.getProperty("name"),
            Instant.parse(data.getProperty("time")),
            Integer.parseInt(data.getProperty("amount")),
            new BigDecimal(data.getProperty("money")),
            new BigDecimal(data.getProperty("multiplier")),
            data.getProperty("source"),
            data.getProperty("items-base64"),
            SummaryCodec.decode(data.getProperty("summary", "")));
    return new Entry(sale, data.getProperty("state"));
  }

  void delete(UUID id) throws IOException {
    Files.deleteIfExists(path(id));
  }

  private Path path(UUID id) {
    return directory.resolve(id + ".properties");
  }
}
