import com.wellsetups.WellSell.message.TextFormatter;
import java.sql.DriverManager;
import java.util.Map;

public class PackagedSmoke {
  public static void main(String[] arguments) throws Exception {
    // These APIs deliberately must NOT be on this smoke-test classpath.
    try {
      Class.forName("com.github.retrooper.packetevents.PacketEvents");
      throw new AssertionError("PacketEvents unexpectedly bundled/present");
    } catch (ClassNotFoundException expected) { /* Optional dependency is absent. */ }
    new com.wellsetups.WellSell.integration.OptionalIntegrations(null);
    Class.forName("com.wellsetups.WellSell.bootstrap.Application");
    Class.forName("org.sqlite.JDBC");
    Class.forName("org.mariadb.jdbc.Driver");
    try (var connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        var statement = connection.createStatement();
        var rows = statement.executeQuery("SELECT 42")) {
      if (!rows.next() || rows.getInt(1) != 42) throw new AssertionError("SQLite query failed");
    }
    String formatted = new TextFormatter().format("<green>%player%", "", Map.of("player", "WellSell"));
    if (!formatted.contains("WellSell")) throw new AssertionError("Shaded formatter failed");
    Class.forName("com.wellsetups.WellSell.lib.yaml.Yaml").getConstructor().newInstance();
    System.out.println("PASS: separate JDBC library loading/native query, relocated formatter/YAML, core linkage without PacketEvents");
    System.out.println("Java " + Runtime.version());
  }
}
