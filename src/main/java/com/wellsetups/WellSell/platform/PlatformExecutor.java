package com.wellsetups.WellSell.platform;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * The only scheduler capability/reflection boundary. No world operations are needed by WellSell.
 */
public final class PlatformExecutor implements AutoCloseable {
  private final Plugin plugin;
  private final boolean folia;
  private final Method entityScheduler;
  private final Method entityExecute;
  private final Method globalExecute;
  private final Method owned;
  private final Object globalScheduler;
  private final ThreadPoolExecutor background;
  private volatile boolean closed;

  public PlatformExecutor(Plugin plugin) {
    this.plugin = plugin;
    folia = present("io.papermc.paper.threadedregions.RegionizedServer");
    try {
      if (folia) {
        entityScheduler = Entity.class.getMethod("getScheduler");
        entityExecute =
            entityScheduler
                .getReturnType()
                .getMethod("execute", Plugin.class, Runnable.class, Runnable.class, long.class);
        Method global = Server.class.getMethod("getGlobalRegionScheduler");
        globalScheduler = invoke(global, Bukkit.getServer());
        globalExecute = global.getReturnType().getMethod("execute", Plugin.class, Runnable.class);
        owned = Bukkit.class.getMethod("isOwnedByCurrentRegion", Entity.class);
      } else {
        entityScheduler = null;
        entityExecute = null;
        globalExecute = null;
        globalScheduler = null;
        owned = null;
      }
    } catch (ReflectiveOperationException failure) {
      throw new IllegalStateException(
          "Folia scheduler API unavailable; refusing unsafe fallback", failure);
    }
    background =
        new ThreadPoolExecutor(
            1,
            2,
            30,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64),
            task -> {
              Thread thread = new Thread(task, "WellSell-background");
              thread.setDaemon(true);
              return thread;
            },
            new ThreadPoolExecutor.AbortPolicy());
  }

  public boolean folia() {
    return folia;
  }

  public void requireOwner(Player player) {
    boolean correct = folia ? (boolean) invoke(owned, null, player) : Bukkit.isPrimaryThread();
    if (!correct) {
      throw new IllegalStateException("WellSell player API must run on the entity's owning thread");
    }
  }

  public void player(Player player, Runnable action, Runnable retired) {
    if (closed) {
      retired.run();
      return;
    }
    Runnable guarded =
        () -> {
          if (closed || !player.isOnline()) {
            retired.run();
          } else {
            action.run();
          }
        };
    if (folia) {
      if (!(boolean)
          invoke(entityExecute, invoke(entityScheduler, player), plugin, guarded, retired, 1L)) {
        retired.run();
      }
    } else {
      try {
        Bukkit.getScheduler().runTask(plugin, guarded);
      } catch (org.bukkit.plugin.IllegalPluginAccessException failure) {
        retired.run();
      }
    }
  }

  public void global(Runnable action) {
    if (closed) {
      return;
    }
    Runnable guarded =
        () -> {
          if (!closed) {
            action.run();
          }
        };
    if (folia) {
      invoke(globalExecute, globalScheduler, plugin, guarded);
    } else {
      try {
        Bukkit.getScheduler().runTask(plugin, guarded);
      } catch (org.bukkit.plugin.IllegalPluginAccessException failure) {
        if (!closed) {
          throw failure;
        }
      }
    }
  }

  public <T> CompletableFuture<T> async(Supplier<T> work) {
    try {
      return CompletableFuture.supplyAsync(work, background);
    } catch (RejectedExecutionException failure) {
      return CompletableFuture.failedFuture(failure);
    }
  }

  private static boolean present(String name) {
    try {
      Class.forName(name, false, PlatformExecutor.class.getClassLoader());
      return true;
    } catch (ClassNotFoundException missing) {
      return false;
    }
  }

  private static Object invoke(Method method, Object receiver, Object... arguments) {
    try {
      return method.invoke(receiver, arguments);
    } catch (IllegalAccessException | InvocationTargetException failure) {
      throw new IllegalStateException("Scheduler invocation failed: " + method.getName(), failure);
    }
  }

  @Override
  public void close() {
    closed = true;
    background.shutdown();
  }
}
