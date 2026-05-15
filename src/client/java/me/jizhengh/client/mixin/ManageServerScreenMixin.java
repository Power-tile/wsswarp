package me.jizhengh.client.mixin;

import me.jizhengh.client.wsswarp.WSSWarpRuntimeConfig;
import me.jizhengh.client.wsswarp.WSSWarpServerDataExt;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ManageServerScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ManageServerScreen.class)
public abstract class ManageServerScreenMixin extends Screen {
	@Unique
	private static final String WSSWARP_SUFFIX = " §d§l[WSSWarped]§r§r";
	@Unique
	private static final Component WSSWARP_CHECKBOX_LABEL = Component.literal("This is a WebSocket server");

	@Shadow
	private ServerData serverData;
	@Shadow
	private Button addButton;
	@Shadow
	private EditBox ipEdit;
	@Shadow
	private EditBox nameEdit;

	@Unique
	private Checkbox wsswarp$checkbox;

	protected ManageServerScreenMixin(Component title) {
		super(title);
	}

	@Inject(method = "init", at = @At("TAIL"))
	private void wsswarp$addCheckbox(CallbackInfo ci) {
		WSSWarpServerDataExt ext = (WSSWarpServerDataExt) this.serverData;
		boolean selected = ext.wsswarp$isWarped();
		int x = this.ipEdit.getX();
		// Anchor near vanilla Add/Cancel controls so placement stays readable across GUI scales.
		int y = this.addButton.getY() - this.addButton.getHeight() - 2;
		this.wsswarp$checkbox = this.addRenderableWidget(
				Checkbox.builder(WSSWARP_CHECKBOX_LABEL, this.font)
						.pos(x, y)
						.selected(selected)
						.build());
		if (selected) {
			// While editing warped entries, present the clean base name in the input box.
			// The suffix is re-applied on save when the checkbox remains enabled.
			this.nameEdit.setValue(wsswarp$stripSuffix(this.nameEdit.getValue()));
		}
	}

	@Inject(method = "onAdd", at = @At("HEAD"))
	private void wsswarp$onAdd(CallbackInfo ci) {
		WSSWarpServerDataExt ext = (WSSWarpServerDataExt) this.serverData;
		boolean warped = this.wsswarp$checkbox != null && this.wsswarp$checkbox.selected();
		ext.wsswarp$setWarped(warped);
		if (warped) {
			// Reuse the server address field as the tunnel WebSocket endpoint for warped profiles.
			String configured = this.ipEdit.getValue();
			ext.wsswarp$setRemoteWsUrl(WSSWarpRuntimeConfig.normalizeWsUrl(configured));
			this.nameEdit.setValue(wsswarp$appendSuffix(this.nameEdit.getValue()));
		} else {
			ext.wsswarp$setRemoteWsUrl("");
			this.nameEdit.setValue(wsswarp$stripSuffix(this.nameEdit.getValue()));
		}
	}

	@Unique
	private static String wsswarp$appendSuffix(String raw) {
		String name = raw == null ? "" : raw;
		if (name.endsWith(WSSWARP_SUFFIX)) {
			return name;
		}
		return name + WSSWARP_SUFFIX;
	}

	@Unique
	private static String wsswarp$stripSuffix(String raw) {
		String name = raw == null ? "" : raw;
		if (name.endsWith(WSSWARP_SUFFIX)) {
			return name.substring(0, name.length() - WSSWARP_SUFFIX.length());
		}
		return name;
	}
}
