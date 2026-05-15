package me.jizhengh.client.wsswarp;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Client config loaded once per game session from Fabric's config directory.
 */
public final class WSSWarpClientConfig {
	private static final Logger LOGGER = LoggerFactory.getLogger("wsswarp");
	private static final String FILE_NAME = "wsswarp-client.properties";
	private static final String KEY_LOCAL_PORT = "localPort";

	private static volatile boolean loaded;
	private static int localPort = WSSWarpConstants.DEFAULT_LOCAL_PORT;

	private WSSWarpClientConfig() {}

	public static synchronized void load() {
		if (loaded) {
			return;
		}
		Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
		Properties properties = new Properties();
		boolean changed = false;

		if (Files.exists(path)) {
			try (Reader reader = Files.newBufferedReader(path)) {
				properties.load(reader);
			} catch (IOException e) {
				LOGGER.warn("[WSSWarp] Failed to read config {}; using defaults. {}", path, e.toString());
				changed = true;
			}
		} else {
			changed = true;
		}

		int parsed = parsePort(properties.getProperty(KEY_LOCAL_PORT), WSSWarpConstants.DEFAULT_LOCAL_PORT);
		if (!String.valueOf(parsed).equals(properties.getProperty(KEY_LOCAL_PORT))) {
			changed = true;
		}
		localPort = parsed;

		if (changed) {
			properties.setProperty(KEY_LOCAL_PORT, Integer.toString(localPort));
			try {
				Files.createDirectories(path.getParent());
				try (Writer writer = Files.newBufferedWriter(path)) {
					properties.store(writer, "WSSWarp client configuration");
				}
			} catch (IOException e) {
				LOGGER.warn("[WSSWarp] Failed to write config {}; continuing with in-memory values. {}", path, e.toString());
			}
		}

		loaded = true;
	}

	public static int getLocalPort() {
		if (!loaded) {
			load();
		}
		return localPort;
	}

	public static Path getConfigPath() {
		return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
	}

	private static int parsePort(String raw, int fallback) {
		if (raw == null || raw.isBlank()) {
			return fallback;
		}
		try {
			int value = Integer.parseInt(raw.trim());
			if (value < 1 || value > 65535) {
				LOGGER.warn("[WSSWarp] Invalid localPort {} in config; expected 1-65535. Using {}.", raw, fallback);
				return fallback;
			}
			return value;
		} catch (NumberFormatException e) {
			LOGGER.warn("[WSSWarp] Invalid localPort {} in config; using {}.", raw, fallback);
			return fallback;
		}
	}
}
