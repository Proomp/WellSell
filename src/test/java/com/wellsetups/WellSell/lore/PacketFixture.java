package com.wellsetups.WellSell.lore;

/**
 * Minimal library bootstrap for component-copy tests; no server or packet transport is simulated.
 */
final class PacketFixture extends com.github.retrooper.packetevents.PacketEventsAPI<Object> {
  @Override
  public boolean isLoaded() {
    return false;
  }

  @Override
  public void init() {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean isInitialized() {
    return false;
  }

  @Override
  public boolean isTerminated() {
    return false;
  }

  @Override
  public Object getPlugin() {
    throw new UnsupportedOperationException();
  }

  @Override
  public com.github.retrooper.packetevents.manager.server.ServerManager getServerManager() {
    return () -> com.github.retrooper.packetevents.manager.server.ServerVersion.V_1_21_4;
  }

  @Override
  public com.github.retrooper.packetevents.manager.protocol.ProtocolManager getProtocolManager() {
    throw new UnsupportedOperationException();
  }

  @Override
  public com.github.retrooper.packetevents.manager.player.PlayerManager getPlayerManager() {
    throw new UnsupportedOperationException();
  }

  @Override
  public com.github.retrooper.packetevents.netty.NettyManager getNettyManager() {
    return new io.github.retrooper.packetevents.impl.netty.NettyManagerImpl();
  }

  @Override
  public com.github.retrooper.packetevents.injector.ChannelInjector getInjector() {
    throw new UnsupportedOperationException();
  }
}
