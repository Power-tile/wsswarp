package me.jizhengh.client;

import me.jizhengh.client.wsswarp.WSSWarpConstants;
import me.jizhengh.client.wsswarp.WSSWarpClientConfig;
import me.jizhengh.client.wsswarp.WSSWarpLocalBridge;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WSSWarpClient implements ClientModInitializer {
	private static final Logger LOGGER = LoggerFactory.getLogger("wsswarp");
	private final WSSWarpLocalBridge bridge = new WSSWarpLocalBridge();

	@Override
	public void onInitializeClient() {
		WSSWarpClientConfig.load();
		LOGGER.info("[WSSWarp] Mod initialized (client entrypoint); starting mock server on {}:{}",
				WSSWarpConstants.LOCAL_HOST, WSSWarpClientConfig.getLocalPort());
		bridge.start();
	}
}
