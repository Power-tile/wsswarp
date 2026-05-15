package me.jizhengh.client.wsswarp;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Listens on {@value WSSWarpConstants#LOCAL_HOST}:{@value WSSWarpConstants#LOCAL_PORT} and manages at most one active session.
 */
public final class WSSWarpLocalBridge {
	private static final Logger LOGGER = LoggerFactory.getLogger("wsswarp");
	private static final long SESSION_SLOT_WAIT_TIMEOUT_MS = 3000L;

	private final ExecutorService cleanupExecutor = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "WSSWarp-cleanup");
		t.setDaemon(true);
		return t;
	});

	private final HttpClient httpClient = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(15))
			.build();

	private final AtomicReference<WSSWarpSession> activeSession = new AtomicReference<>();
	private final AtomicLong nextSessionId = new AtomicLong(1);
	private final Object sessionSlotMonitor = new Object();
	private volatile ServerSocket serverSocket;
	private volatile boolean running;
	private Thread acceptThread;

	public void start() {
		if (running) {
			LOGGER.warn("[WSSWarp] Bridge start ignored (already running)");
			return;
		}
		try {
			ServerSocket ss = new ServerSocket();
			ss.bind(new InetSocketAddress(InetAddress.getByName(WSSWarpConstants.LOCAL_HOST), WSSWarpConstants.LOCAL_PORT));
			this.serverSocket = ss;
		} catch (IOException e) {
			LOGGER.error("[WSSWarp] Failed to bind local bridge {}:{} — {}",
					WSSWarpConstants.LOCAL_HOST, WSSWarpConstants.LOCAL_PORT, e.toString());
			return;
		}

		running = true;
		acceptThread = new Thread(this::acceptLoop, "WSSWarp-accept");
		acceptThread.setDaemon(true);
		acceptThread.start();
		LOGGER.info("[WSSWarp] Bridge listening on {}:{} (connect Minecraft multiplayer to this address)",
				WSSWarpConstants.LOCAL_HOST, WSSWarpConstants.LOCAL_PORT);
	}

	@SuppressWarnings("unused")
	public void stop() {
		running = false;
		synchronized (sessionSlotMonitor) {
			sessionSlotMonitor.notifyAll();
		}
		ServerSocket ss = serverSocket;
		serverSocket = null;
		if (ss != null) {
			try {
				ss.close();
			} catch (IOException e) {
				LOGGER.warn("[WSSWarp] Error closing server socket: {}", e.toString());
			}
		}
		if (acceptThread != null) {
			acceptThread.interrupt();
		}
		WSSWarpSession s = activeSession.get();
		if (s != null) {
			s.requestClose("bridge_stopped");
		}
		cleanupExecutor.shutdown();
		LOGGER.info("[WSSWarp] Bridge stopped");
	}

	void scheduleCleanup(Runnable r) {
		cleanupExecutor.execute(r);
	}

	void onSessionEnded(WSSWarpSession session) {
		if (activeSession.compareAndSet(session, null)) {
			synchronized (sessionSlotMonitor) {
				sessionSlotMonitor.notifyAll();
			}
		}
	}

	private void acceptLoop() {
		while (running && serverSocket != null && !serverSocket.isClosed()) {
			try {
				Socket client = serverSocket.accept();
				handleAccepted(client);
			} catch (IOException e) {
				if (running) {
					LOGGER.warn("[WSSWarp] Accept loop I/O: {}", e.toString());
				}
			}
		}
	}

	private void handleAccepted(Socket client) {
		try {
			client.setTcpNoDelay(true);
		} catch (IOException e) {
			LOGGER.warn("[WSSWarp] Could not set TCP_NODELAY on accepted socket: {}", e.toString());
		}

		if (!waitForSessionSlot(client)) {
			LOGGER.warn("[WSSWarp] Rejecting local TCP client after waiting for active session slot. Remote: {}",
					client.getRemoteSocketAddress());
			try {
				client.close();
			} catch (IOException e) {
				LOGGER.debug("[WSSWarp] Error closing rejected socket: {}", e.toString());
			}
			return;
		}

		LOGGER.info("[WSSWarp] Mock server port {}: TCP connection accepted from {}",
				WSSWarpConstants.LOCAL_PORT, client.getRemoteSocketAddress());
		long sessionId = nextSessionId.getAndIncrement();
		WSSWarpSession session = new WSSWarpSession(this, sessionId, client, httpClient);
		if (!activeSession.compareAndSet(null, session)) {
			LOGGER.warn("[WSSWarp] Race: session already active; closing incoming TCP client");
			try {
				client.close();
			} catch (IOException e) {
				LOGGER.debug("[WSSWarp] Error closing socket: {}", e.toString());
			}
			return;
		}
		LOGGER.info("[WSSWarp][session={}] Session created for local TCP peer {}", sessionId, client.getRemoteSocketAddress());
		session.start();
	}

	private boolean waitForSessionSlot(Socket client) {
		if (activeSession.get() == null) {
			return true;
		}
		long deadline = System.currentTimeMillis() + SESSION_SLOT_WAIT_TIMEOUT_MS;
		synchronized (sessionSlotMonitor) {
			while (running && !client.isClosed() && activeSession.get() != null) {
				long remaining = deadline - System.currentTimeMillis();
				if (remaining <= 0L) {
					break;
				}
				try {
					sessionSlotMonitor.wait(remaining);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return false;
				}
			}
			return activeSession.get() == null;
		}
	}
}
