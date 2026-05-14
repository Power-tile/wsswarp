package me.jizhengh.client.wsswarp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One local TCP socket ↔ one WebSocket, raw byte forwarding only (no Minecraft protocol parsing).
 */
public final class WSSWarpSession {
	private static final Logger LOGGER = LoggerFactory.getLogger("wsswarp");
	private static final int TCP_READ_BUFFER_SIZE = 64 * 1024;

	private final WSSWarpLocalBridge bridge;
	private final java.net.Socket tcpSocket;
	private final HttpClient httpClient;

	private final ExecutorService wsToTcpWriter = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "WSSWarp-ws-to-tcp");
		t.setDaemon(true);
		return t;
	});

	private final ScheduledExecutorService pingScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "WSSWarp-ping");
		t.setDaemon(true);
		return t;
	});

	private final AtomicBoolean closing = new AtomicBoolean(false);
	private final AtomicBoolean teardownScheduled = new AtomicBoolean(false);
	private final AtomicBoolean resourcesReleased = new AtomicBoolean(false);
	private final AtomicLong bytesTcpToWs = new AtomicLong();
	private final AtomicLong bytesWsToTcp = new AtomicLong();

	private volatile WebSocket webSocket;
	private volatile Thread tcpReaderThread;
	private volatile Future<?> pingFuture;

	WSSWarpSession(WSSWarpLocalBridge bridge, java.net.Socket tcpSocket, HttpClient httpClient) {
		this.bridge = bridge;
		this.tcpSocket = tcpSocket;
		this.httpClient = httpClient;
	}

	/**
	 * Request teardown from any thread; socket cleanup runs on the bridge cleanup executor
	 * so this never blocks the Minecraft client thread.
	 */
	void requestClose(String reason) {
		closing.set(true);
		if (!teardownScheduled.compareAndSet(false, true)) {
			return;
		}
		bridge.scheduleCleanup(() -> closeImpl(reason));
	}

	void start() {
		Thread t = new Thread(this::openWebSocketAndPrepareTcp, "WSSWarp-ws-handshake");
		t.setDaemon(true);
		t.start();
	}

	private void openWebSocketAndPrepareTcp() {
		try {
			tcpSocket.setTcpNoDelay(true);
		} catch (IOException e) {
			LOGGER.error("[WSSWarp] Failed to set TCP_NODELAY on local socket: {}", e.toString());
			requestClose("tcp_nodelay_failed");
			return;
		}

		URI wsUri = URI.create(WSSWarpConstants.REMOTE_WS_URL);
		LOGGER.info("[WSSWarp] Opening WebSocket to {} (local session {})", wsUri, tcpSocket.getRemoteSocketAddress());

		WebSocket.Listener listener = new WebSocket.Listener() {
			@Override
			public void onOpen(WebSocket webSocket) {
				WSSWarpSession.this.webSocket = webSocket;
				// java.net.http.WebSocket uses demand-based delivery.
				// Without request(1), no inbound frames are dispatched to onBinary.
				webSocket.request(1);
				LOGGER.info("[WSSWarp] WebSocket connected to {}", WSSWarpConstants.REMOTE_WS_URL);
				startTcpReaderThread();
				startPeriodicPing();
			}

			@Override
			public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
				int n = data.remaining();
				if (n > 0) {
					bytesWsToTcp.addAndGet(n);
					byte[] chunk = new byte[n];
					data.get(chunk);
					wsToTcpWriter.execute(() -> writeTcp(chunk));
				}
				webSocket.request(1);
				return CompletableFuture.completedFuture(null);
			}

			@Override
			public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
				LOGGER.info("[WSSWarp] WebSocket closed: status={} reason={} (ws→tcp {} bytes, tcp→ws {} bytes)",
						statusCode, reason, bytesWsToTcp.get(), bytesTcpToWs.get());
				requestClose("websocket_on_close");
				return CompletableFuture.completedFuture(null);
			}

			@Override
			public void onError(WebSocket webSocket, Throwable error) {
				LOGGER.error("[WSSWarp] WebSocket error: {} (ws→tcp {} bytes, tcp→ws {} bytes)",
						error.toString(), bytesWsToTcp.get(), bytesTcpToWs.get());
				requestClose("websocket_on_error");
			}
		};

		httpClient.newWebSocketBuilder()
				.connectTimeout(Duration.ofSeconds(15))
				.header(WSSWarpConstants.SECRET_HEADER_NAME, WSSWarpConstants.SHARED_SECRET)
				.buildAsync(wsUri, listener)
				.whenComplete((ws, err) -> {
					if (err != null) {
						LOGGER.error("[WSSWarp] WebSocket connect failed: {}", err.toString());
						requestClose("websocket_connect_failed");
					}
				});
	}

	private void startTcpReaderThread() {
		tcpReaderThread = new Thread(this::tcpReadLoop, "WSSWarp-tcp-read");
		tcpReaderThread.setDaemon(true);
		tcpReaderThread.start();
	}

	private void tcpReadLoop() {
		try (InputStream in = tcpSocket.getInputStream()) {
			byte[] buf = new byte[TCP_READ_BUFFER_SIZE];
			int read;
			while (!closing.get() && (read = in.read(buf)) != -1) {
				if (read == 0) {
					continue;
				}
				bytesTcpToWs.addAndGet(read);
				ByteBuffer bb = ByteBuffer.wrap(buf, 0, read);
				WebSocket ws = webSocket;
				if (ws == null || closing.get()) {
					break;
				}
				try {
					ws.sendBinary(bb, true).join();
				} catch (Exception e) {
					if (!closing.get()) {
						LOGGER.error("[WSSWarp] sendBinary failed: {}", e.toString());
					}
					break;
				}
			}
			if (!closing.get()) {
				LOGGER.info("[WSSWarp] Local TCP input ended (tcp→ws {} bytes)", bytesTcpToWs.get());
			}
		} catch (IOException e) {
			if (!closing.get()) {
				LOGGER.warn("[WSSWarp] Local TCP read error: {}", e.toString());
			}
		} finally {
			requestClose("tcp_reader_done");
		}
	}

	private void writeTcp(byte[] chunk) {
		if (closing.get()) {
			return;
		}
		try {
			OutputStream out = tcpSocket.getOutputStream();
			synchronized (out) {
				out.write(chunk);
				out.flush();
			}
		} catch (IOException e) {
			if (!closing.get()) {
				LOGGER.warn("[WSSWarp] Local TCP write error: {}", e.toString());
			}
			requestClose("tcp_write_failed");
		}
	}

	private void startPeriodicPing() {
		pingFuture = pingScheduler.scheduleAtFixedRate(() -> {
			WebSocket ws = webSocket;
			if (ws == null || closing.get()) {
				return;
			}
			try {
				ws.sendPing(ByteBuffer.allocate(0));
				LOGGER.debug("[WSSWarp] Sent WebSocket ping");
			} catch (Exception e) {
				if (!closing.get()) {
					LOGGER.warn("[WSSWarp] WebSocket ping failed: {}", e.toString());
				}
			}
		}, WSSWarpConstants.PING_INTERVAL_SECONDS, WSSWarpConstants.PING_INTERVAL_SECONDS, TimeUnit.SECONDS);
	}

	private void closeImpl(String reason) {
		if (!resourcesReleased.compareAndSet(false, true)) {
			return;
		}
		try {
			if (pingFuture != null) {
				pingFuture.cancel(false);
			}
			pingScheduler.shutdownNow();

			WebSocket ws = webSocket;
			if (ws != null) {
				try {
					ws.sendClose(WebSocket.NORMAL_CLOSURE, "wsswarp");
				} catch (Exception ignored) {
					try {
						ws.abort();
					} catch (Exception ignored2) {
						// ignore
					}
				}
			}

			if (tcpReaderThread != null) {
				tcpReaderThread.interrupt();
			}
			try {
				tcpSocket.close();
			} catch (IOException ignored) {
				// ignore
			}

			wsToTcpWriter.shutdownNow();
		} finally {
			bridge.onSessionEnded(this);
			LOGGER.info("[WSSWarp] Session closed ({}) — totals: tcp→ws {} bytes, ws→tcp {} bytes",
					reason, bytesTcpToWs.get(), bytesWsToTcp.get());
		}
	}
}
