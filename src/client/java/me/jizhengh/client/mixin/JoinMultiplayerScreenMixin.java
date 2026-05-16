package me.jizhengh.client.mixin;

import me.jizhengh.client.wsswarp.WSSWarpConstants;
import me.jizhengh.client.wsswarp.WSSWarpClientConfig;
import me.jizhengh.client.wsswarp.WSSWarpRuntimeConfig;
import me.jizhengh.client.wsswarp.WSSWarpServerDataExt;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(JoinMultiplayerScreen.class)
public class JoinMultiplayerScreenMixin {
	@Unique
	private ServerData wsswarp$joiningServerData;

	@Shadow
	private void refreshServerList() {}

	@Inject(method = "join", at = @At("HEAD"))
	private void wsswarp$selectRuntimeTarget(ServerData serverData, CallbackInfo ci) {
		this.wsswarp$joiningServerData = serverData;
		WSSWarpServerDataExt ext = (WSSWarpServerDataExt) serverData;
		if (!ext.wsswarp$isWarped()) {
			return;
		}
		String configured = ext.wsswarp$getRemoteWsUrl();
		if (configured == null || configured.isBlank()) {
			configured = serverData.ip;
		}
		WSSWarpRuntimeConfig.setActiveSharedSecret(ext.wsswarp$getSharedSecret());
		WSSWarpRuntimeConfig.setActiveRemoteWsUrl(configured);
	}

	@ModifyArg(
			method = "join",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/client/multiplayer/resolver/ServerAddress;parseString(Ljava/lang/String;)Lnet/minecraft/client/multiplayer/resolver/ServerAddress;"
			),
			index = 0
	)
	private String wsswarp$redirectJoinAddress(String originalIp) {
		ServerData serverData = this.wsswarp$joiningServerData;
		if (serverData == null) {
			return originalIp;
		}
		WSSWarpServerDataExt ext = (WSSWarpServerDataExt) serverData;
		if (!ext.wsswarp$isWarped()) {
			return originalIp;
		}
		return WSSWarpConstants.LOCAL_HOST + ":" + WSSWarpClientConfig.getLocalPort();
	}

	@Inject(method = "join", at = @At("RETURN"))
	private void wsswarp$clearJoinContext(ServerData serverData, CallbackInfo ci) {
		this.wsswarp$joiningServerData = null;
	}

	@Inject(method = "editServerCallback", at = @At("TAIL"))
	private void wsswarp$refreshAfterProfileEdit(boolean result, CallbackInfo ci) {
		if (result) {
			this.refreshServerList();
		}
	}
}
