package com.wellsetups.WellSell.sell;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PlayerTransactions {
  private final Set<UUID> active = ConcurrentHashMap.newKeySet();
  private final Set<UUID> quarantined = ConcurrentHashMap.newKeySet();

  private boolean paused;

  public synchronized boolean acquire(UUID player) {
    return !paused && !quarantined.contains(player) && active.add(player);
  }

  public boolean quarantined(UUID player) {
    return quarantined.contains(player);
  }

  public void quarantine(UUID player) {
    quarantined.add(player);
  }

  public synchronized void release(UUID player) {
    active.remove(player);
  }

  public synchronized boolean pause() {
    if (!active.isEmpty()) {
      return false;
    }
    paused = true;
    return true;
  }

  public synchronized void resume() {
    paused = false;
  }
}
