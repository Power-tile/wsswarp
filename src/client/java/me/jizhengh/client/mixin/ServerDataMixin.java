package me.jizhengh.client.mixin;

import me.jizhengh.client.wsswarp.WSSWarpServerDataExt;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerData.class)
public class ServerDataMixin implements WSSWarpServerDataExt {
	@Unique
	private static final String WSSWARP_WARPED_TAG = "wsswarpWarped";
	@Unique
	private static final String WSSWARP_REMOTE_WS_URL_TAG = "wsswarpRemoteWsUrl";
	@Unique
	private static final String WSSWARP_SHARED_SECRET_TAG = "wsswarpSharedSecret";

	@Unique
	private boolean wsswarp$warped;
	@Unique
	private String wsswarp$remoteWsUrl = "";
	@Unique
	private String wsswarp$sharedSecret = "";

	@Inject(method = "write", at = @At("RETURN"))
	private void wsswarp$writeCustomTags(CallbackInfoReturnable<CompoundTag> cir) {
		// Persist WSSWarp-specific fields alongside vanilla server data.
		CompoundTag tag = cir.getReturnValue();
		tag.putBoolean(WSSWARP_WARPED_TAG, this.wsswarp$warped);
		if (this.wsswarp$remoteWsUrl != null && !this.wsswarp$remoteWsUrl.isEmpty()) {
			tag.putString(WSSWARP_REMOTE_WS_URL_TAG, this.wsswarp$remoteWsUrl);
		}
		if (this.wsswarp$sharedSecret != null && !this.wsswarp$sharedSecret.isEmpty()) {
			tag.putString(WSSWARP_SHARED_SECRET_TAG, this.wsswarp$sharedSecret);
		}
	}

	@Inject(method = "read", at = @At("RETURN"))
	private static void wsswarp$readCustomTags(CompoundTag tag, CallbackInfoReturnable<ServerData> cir) {
		ServerData data = cir.getReturnValue();
		if (data == null) {
			return;
		}
		WSSWarpServerDataExt ext = (WSSWarpServerDataExt) data;
		ext.wsswarp$setWarped(tag.getBoolean(WSSWARP_WARPED_TAG).orElse(false));
		ext.wsswarp$setRemoteWsUrl(tag.getString(WSSWARP_REMOTE_WS_URL_TAG).orElse(""));
		ext.wsswarp$setSharedSecret(tag.getString(WSSWARP_SHARED_SECRET_TAG).orElse(""));
	}

	@Inject(method = "copyNameIconFrom", at = @At("TAIL"))
	private void wsswarp$copyNameIconFrom(ServerData other, CallbackInfo ci) {
		// Minecraft uses in-memory copies while editing entries; mirror custom fields too.
		WSSWarpServerDataExt ext = (WSSWarpServerDataExt) other;
		this.wsswarp$warped = ext.wsswarp$isWarped();
		this.wsswarp$remoteWsUrl = ext.wsswarp$getRemoteWsUrl();
		this.wsswarp$sharedSecret = ext.wsswarp$getSharedSecret();
	}

	@Inject(method = "copyFrom", at = @At("TAIL"))
	private void wsswarp$copyFrom(ServerData other, CallbackInfo ci) {
		WSSWarpServerDataExt ext = (WSSWarpServerDataExt) other;
		this.wsswarp$warped = ext.wsswarp$isWarped();
		this.wsswarp$remoteWsUrl = ext.wsswarp$getRemoteWsUrl();
		this.wsswarp$sharedSecret = ext.wsswarp$getSharedSecret();
	}

	@Override
	public boolean wsswarp$isWarped() {
		return this.wsswarp$warped;
	}

	@Override
	public void wsswarp$setWarped(boolean warped) {
		this.wsswarp$warped = warped;
	}

	@Override
	public String wsswarp$getRemoteWsUrl() {
		return this.wsswarp$remoteWsUrl;
	}

	@Override
	public void wsswarp$setRemoteWsUrl(String remoteWsUrl) {
		this.wsswarp$remoteWsUrl = remoteWsUrl == null ? "" : remoteWsUrl;
	}

	@Override
	public String wsswarp$getSharedSecret() {
		return this.wsswarp$sharedSecret;
	}

	@Override
	public void wsswarp$setSharedSecret(String sharedSecret) {
		this.wsswarp$sharedSecret = sharedSecret == null ? "" : sharedSecret;
	}
}
