package me.jizhengh.client.wsswarp;

public interface WSSWarpServerDataExt {
	boolean wsswarp$isWarped();

	void wsswarp$setWarped(boolean warped);

	String wsswarp$getRemoteWsUrl();

	void wsswarp$setRemoteWsUrl(String remoteWsUrl);
}
