package com.wellsetups.WellSell;

import com.wellsetups.WellSell.bootstrap.Application;
import java.io.IOException;
import java.util.logging.Level;
import org.bukkit.plugin.java.JavaPlugin;

public final class Main extends JavaPlugin {
  private Application application;

  @Override
  public void onEnable() {
    application = new Application(this);
    try {
      application.start();
    } catch (IOException | RuntimeException failure) {
      getLogger()
          .log(
              Level.SEVERE,
              "WellSell could not start safely; check configuration and dependency diagnostics",
              failure);
      getServer().getPluginManager().disablePlugin(this);
    }
  }

  @Override
  public void onDisable() {
    if (application != null) {
      application.close();
      application = null;
    }
  }
}
