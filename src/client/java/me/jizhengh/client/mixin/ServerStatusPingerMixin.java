package me.jizhengh.client.mixin;

import me.jizhengh.client.wsswarp.WSSWarpConstants;
import me.jizhengh.client.wsswarp.WSSWarpClientConfig;
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
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerStatusPinger.class)
public class ServerStatusPingerMixin {
	@Unique
	private static final ThreadLocal<ServerData> WSSWARP_PING_CONTEXT = new ThreadLocal<>();

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
			return;
		}
		WSSWarpRuntimeConfig.acquireWarpedPingMutex();
		String configured = ext.wsswarp$getRemoteWsUrl();
		if (configured == null || configured.isBlank()) {
			configured = serverData.ip;
		}
		WSSWarpRuntimeConfig.setActiveSharedSecret(ext.wsswarp$getSharedSecret());
		WSSWarpRuntimeConfig.setActiveRemoteWsUrl(configured);
	}

	@ModifyVariable(
			method = "pingServer",
			at = @At("HEAD"),
			argsOnly = true,
			index = 3
	)
	private Runnable wsswarp$wrapPongCallback(Runnable onPongResponse, ServerData serverData) {
		if (!this.wsswarp$isWarped(serverData)) {
			return onPongResponse;
		}
		return () -> {
			try {
				if (onPongResponse != null) {
					onPongResponse.run();
				}
			} finally {
				// Release after the ping result has been applied to the server entry.
				WSSWarpRuntimeConfig.releaseWarpedPingMutexIfHeld();
			}
		};
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
		return WSSWarpConstants.LOCAL_HOST + ":" + WSSWarpClientConfig.getLocalPort();
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
	}

	@Inject(method = "onPingFailed", at = @At("TAIL"))
	private void wsswarp$releaseMutexOnPingFailure(net.minecraft.network.chat.Component reason, ServerData serverData, CallbackInfo ci) {
		if (this.wsswarp$isWarped(serverData)) {
			WSSWarpRuntimeConfig.releaseWarpedPingMutexIfHeld();
		}
	}

	@Unique
	private boolean wsswarp$isWarped(ServerData serverData) {
		return serverData != null && ((WSSWarpServerDataExt) serverData).wsswarp$isWarped();
	}
}
