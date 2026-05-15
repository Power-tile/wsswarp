package me.jizhengh.client.mixin;

import me.jizhengh.client.wsswarp.WSSWarpConstants;
import me.jizhengh.client.wsswarp.WSSWarpRuntimeConfig;
import me.jizhengh.client.wsswarp.WSSWarpServerDataExt;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerStatusPinger;
import net.minecraft.server.network.EventLoopGroupHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerStatusPinger.class)
public class ServerStatusPingerMixin {
	@Unique
	private static final ThreadLocal<ServerData> WSSWARP_PING_CONTEXT = new ThreadLocal<>();
	@Unique
	private static final ThreadLocal<Boolean> WSSWARP_PING_MUTEX_HELD = ThreadLocal.withInitial(() -> false);

	@Inject(method = "pingServer", at = @At("HEAD"))
	private void wsswarp$setPingContext(
			ServerData serverData,
			Runnable onPersistentDataChange,
			Runnable onPongResponse,
			EventLoopGroupHolder eventLoopGroupHolder,
			CallbackInfo ci
	) {
		WSSWARP_PING_CONTEXT.set(serverData);
		WSSWarpServerDataExt ext = (WSSWarpServerDataExt) serverData;
		if (!ext.wsswarp$isWarped()) {
			WSSWarpRuntimeConfig.resetActiveSharedSecret();
			return;
		}
		WSSWarpRuntimeConfig.acquireWarpedPingMutex();
		WSSWARP_PING_MUTEX_HELD.set(true);
		String configured = ext.wsswarp$getRemoteWsUrl();
		if (configured == null || configured.isBlank()) {
			configured = serverData.ip;
		}
		WSSWarpRuntimeConfig.setActiveSharedSecret(ext.wsswarp$getSharedSecret());
		WSSWarpRuntimeConfig.setActiveRemoteWsUrl(configured);
	}

	@ModifyArg(
			method = "pingServer",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/client/multiplayer/resolver/ServerAddress;parseString(Ljava/lang/String;)Lnet/minecraft/client/multiplayer/resolver/ServerAddress;"
			),
			index = 0
	)
	private String wsswarp$redirectPingAddress(String originalIp) {
		ServerData serverData = WSSWARP_PING_CONTEXT.get();
		if (serverData == null) {
			return originalIp;
		}
		WSSWarpServerDataExt ext = (WSSWarpServerDataExt) serverData;
		if (!ext.wsswarp$isWarped()) {
			return originalIp;
		}
		return WSSWarpConstants.LOCAL_HOST + ":" + WSSWarpConstants.LOCAL_PORT;
	}

	@Inject(method = "pingServer", at = @At("RETURN"))
	private void wsswarp$clearPingContext(
			ServerData serverData,
			Runnable onPersistentDataChange,
			Runnable onPongResponse,
			EventLoopGroupHolder eventLoopGroupHolder,
			CallbackInfo ci
	) {
		WSSWARP_PING_CONTEXT.remove();
		if (WSSWARP_PING_MUTEX_HELD.get()) {
			WSSWarpRuntimeConfig.releaseWarpedPingMutex();
		}
		WSSWARP_PING_MUTEX_HELD.remove();
	}
}
