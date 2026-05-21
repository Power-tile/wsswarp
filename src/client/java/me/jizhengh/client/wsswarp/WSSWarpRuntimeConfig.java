package me.jizhengh.client.wsswarp;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Session-level bridge settings selected by the server entry the player joins.
 */
public final class WSSWarpRuntimeConfig {
	private static final AtomicReference<String> ACTIVE_REMOTE_WS_URL =
			new AtomicReference<>(WSSWarpConstants.REMOTE_WS_URL);
	private static final AtomicReference<String> ACTIVE_SHARED_SECRET =
			new AtomicReference<>("");
	private static final Semaphore WARPED_PING_MUTEX = new Semaphore(1, true);
	private static final AtomicBoolean WARPED_PING_MUTEX_HELD = new AtomicBoolean(false);

	private WSSWarpRuntimeConfig() {}

	public static void setActiveRemoteWsUrl(String raw) {
		ACTIVE_REMOTE_WS_URL.set(normalizeWsUrl(raw));
	}

	public static String getActiveRemoteWsUrl() {
		return ACTIVE_REMOTE_WS_URL.get();
	}

	public static void resetActiveRemoteWsUrl() {
		ACTIVE_REMOTE_WS_URL.set(WSSWarpConstants.REMOTE_WS_URL);
	}

	public static void setActiveSharedSecret(String raw) {
		ACTIVE_SHARED_SECRET.set(normalizeSharedSecret(raw));
	}

	public static String getActiveSharedSecret() {
		return ACTIVE_SHARED_SECRET.get();
	}

	public static void resetActiveSharedSecret() {
		ACTIVE_SHARED_SECRET.set("");
	}

	public static void acquireWarpedPingMutex() {
		boolean interrupted = false;
		while (true) {
			try {
				WARPED_PING_MUTEX.acquire();
				WARPED_PING_MUTEX_HELD.set(true);
				break;
			} catch (InterruptedException e) {
				interrupted = true;
			}
		}
		if (interrupted) {
			Thread.currentThread().interrupt();
		}
	}

	public static void releaseWarpedPingMutex() {
		WARPED_PING_MUTEX_HELD.set(false);
		WARPED_PING_MUTEX.release();
	}

	public static void releaseWarpedPingMutexIfHeld() {
		if (WARPED_PING_MUTEX_HELD.compareAndSet(true, false)) {
			WARPED_PING_MUTEX.release();
		}
	}

	public static String normalizeWsUrl(String raw) {
		if (raw == null) {
			return WSSWarpConstants.REMOTE_WS_URL;
		}
		String trimmed = raw.trim();
		if (trimmed.isEmpty()) {
			return WSSWarpConstants.REMOTE_WS_URL;
		}
		if (trimmed.startsWith("ws://") || trimmed.startsWith("wss://")) {
			return trimmed;
		}
		return "ws://" + trimmed;
	}

	public static String normalizeSharedSecret(String raw) {
		if (raw == null) {
			return "";
		}
		String trimmed = raw.trim();
		return trimmed;
	}
}
