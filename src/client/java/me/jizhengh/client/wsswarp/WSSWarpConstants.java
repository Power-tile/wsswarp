package me.jizhengh.client.wsswarp;

/**
 * Central configuration for the local TCP ↔ WebSocket bridge.
 */
public final class WSSWarpConstants {
	private WSSWarpConstants() {}

	public static final String LOCAL_HOST = "127.0.0.1";
	public static final int LOCAL_PORT = 3716;
	public static final String REMOTE_WS_URL = "ws://localhost:8080/mc";
	public static final int PING_INTERVAL_SECONDS = 30;

	/** Preferred auth channel for the tunnel server. */
	public static final String SECRET_HEADER_NAME = "X-WSSWarp-Secret";
}
