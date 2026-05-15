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
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One local TCP socket ↔ one WebSocket, raw byte forwarding only (no Minecraft protocol parsing).
 */
public final class WSSWarpSession {
	private static final Logger LOGGER = LoggerFactory.getLogger("wsswarp");
	private static final int TCP_READ_BUFFER_SIZE = 64 * 1024;

	private final WSSWarpLocalBridge bridge;
	private final long sessionId;
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
	private final AtomicReference<String> closeTrigger = new AtomicReference<>();
	private final AtomicLong bytesTcpToWs = new AtomicLong();
	private final AtomicLong bytesWsToTcp = new AtomicLong();

	private volatile WebSocket webSocket;
	private volatile Thread tcpReaderThread;
	private volatile Future<?> pingFuture;

	WSSWarpSession(WSSWarpLocalBridge bridge, long sessionId, java.net.Socket tcpSocket, HttpClient httpClient) {
		this.bridge = bridge;
		this.sessionId = sessionId;
		this.tcpSocket = tcpSocket;
		this.httpClient = httpClient;
	}

	/**
	 * Request teardown from any thread; socket cleanup runs on the bridge cleanup executor
	 * so this never blocks the Minecraft client thread.
	 */
	void requestClose(String reason) {
		closeTrigger.compareAndSet(null, reason);
		closing.set(true);
		if (!teardownScheduled.compareAndSet(false, true)) {
			LOGGER.debug("[WSSWarp][session={}] Close already scheduled (new reason={} first reason={})",
					sessionId, reason, closeTrigger.get());
			return;
		}
		LOGGER.info("[WSSWarp][session={}] Scheduling teardown (reason={})", sessionId, reason);
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

		String remoteWsUrl = WSSWarpRuntimeConfig.getActiveRemoteWsUrl();
		String sharedSecret = WSSWarpRuntimeConfig.getActiveSharedSecret();
		URI wsUri = URI.create(remoteWsUrl);
		LOGGER.info("[WSSWarp][session={}] Opening WebSocket to {} (local TCP peer {})",
				sessionId, wsUri, tcpSocket.getRemoteSocketAddress());

		WebSocket.Listener listener = new WebSocket.Listener() {
			@Override
			public void onOpen(WebSocket webSocket) {
				WSSWarpSession.this.webSocket = webSocket;
				// java.net.http.WebSocket uses demand-based delivery.
				// Without request(1), no inbound frames are dispatched to onBinary.
				webSocket.request(1);
				LOGGER.info("[WSSWarp][session={}] WebSocket connected to {}", sessionId, wsUri);
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
				LOGGER.info("[WSSWarp][session={}] WebSocket closed by remote/local-close-propagation: status={} reason={} (ws→tcp {} bytes, tcp→ws {} bytes, close-trigger={})",
						sessionId, statusCode, reason, bytesWsToTcp.get(), bytesTcpToWs.get(), closeTrigger.get());
				requestClose("websocket_on_close");
				return CompletableFuture.completedFuture(null);
			}

			@Override
			public void onError(WebSocket webSocket, Throwable error) {
				LOGGER.error("[WSSWarp][session={}] WebSocket error: {} (ws→tcp {} bytes, tcp→ws {} bytes)",
						sessionId, error.toString(), bytesWsToTcp.get(), bytesTcpToWs.get());
				requestClose("websocket_on_error");
			}
		};

		httpClient.newWebSocketBuilder()
				.connectTimeout(Duration.ofSeconds(15))
				.header(WSSWarpConstants.SECRET_HEADER_NAME, sharedSecret)
				.buildAsync(wsUri, listener)
				.whenComplete((ws, err) -> {
					if (err != null) {
						LOGGER.error("[WSSWarp][session={}] WebSocket connect failed: {}", sessionId, err.toString());
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
				LOGGER.info("[WSSWarp][session={}] Local TCP input EOF (client closed write side) (tcp→ws {} bytes)",
						sessionId, bytesTcpToWs.get());
			}
		} catch (IOException e) {
			if (!closing.get()) {
				LOGGER.warn("[WSSWarp][session={}] Local TCP read error: {}", sessionId, e.toString());
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
				LOGGER.warn("[WSSWarp][session={}] Local TCP write error: {}", sessionId, e.toString());
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
				LOGGER.debug("[WSSWarp][session={}] Sent WebSocket ping", sessionId);
			} catch (Exception e) {
				if (!closing.get()) {
					LOGGER.warn("[WSSWarp][session={}] WebSocket ping failed: {}", sessionId, e.toString());
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
					LOGGER.info("[WSSWarp][session={}] Sending WebSocket close frame (status=1000, reason=wsswarp) due to {}",
							sessionId, closeTrigger.get());
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
			LOGGER.info("[WSSWarp][session={}] Session closed (requested-reason={}, first-trigger={}) — totals: tcp→ws {} bytes, ws→tcp {} bytes",
					sessionId, reason, closeTrigger.get(), bytesTcpToWs.get(), bytesWsToTcp.get());
		}
	}
}
