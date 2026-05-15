package me.jizhengh.client.mixin;

import me.jizhengh.client.wsswarp.WSSWarpRuntimeConfig;
import me.jizhengh.client.wsswarp.WSSWarpServerDataExt;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ManageServerScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import java.util.HashMap;
import java.util.Map;
import org.spongepowered.asm.mixin.Final;
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
	@Unique
	private static final Component WSSWARP_SECRET_LABEL = Component.literal("WSSWarp Shared Secret");
	@Unique
	private static final Component WSSWARP_SECRET_HINT = Component.literal("Optional, enter if provided");
	@Unique
	private static final int WSSWARP_AFTER_IP_GAP = 6;
	@Unique
	private static final int WSSWARP_AFTER_CHECKBOX_GAP = 20;

	@Shadow
	@Final
	private ServerData serverData;
	@Shadow
	private Button addButton;
	@Shadow
	private EditBox ipEdit;
	@Shadow
	private EditBox nameEdit;

	@Unique
	private Checkbox wsswarp$checkbox;
	@Unique
	private EditBox wsswarp$secretEdit;
	@Unique
	private final Map<AbstractWidget, Integer> wsswarp$baseWidgetY = new HashMap<>();
	@Unique
	private int wsswarp$bottomWidgetsStartY = Integer.MAX_VALUE;

	protected ManageServerScreenMixin(Component title) {
		super(title);
	}

	@Inject(method = "init", at = @At("TAIL"))
	private void wsswarp$addCheckbox(CallbackInfo ci) {
		WSSWarpServerDataExt ext = (WSSWarpServerDataExt) this.serverData;
		boolean selected = ext.wsswarp$isWarped();
		int x = this.ipEdit.getX();
		this.wsswarp$checkbox = this.addWidget(
				Checkbox.builder(WSSWARP_CHECKBOX_LABEL, this.font)
						.selected(selected)
						.onValueChange((checkbox, isChecked) -> this.wsswarp$reflowWarpControls(isChecked))
						.build());
		// Note these locations of edit box are placeholders, actual ones are set when
		// checkbox is checked and secret edit box is rendered.
		this.wsswarp$secretEdit = this.addWidget(
				new EditBox(this.font, x, this.ipEdit.getY(), this.ipEdit.getWidth(), this.ipEdit.getHeight(), WSSWARP_SECRET_LABEL));
		this.wsswarp$secretEdit.setHint(WSSWARP_SECRET_HINT);
		this.wsswarp$secretEdit.setMaxLength(256);
		String savedSecret = ext.wsswarp$getSharedSecret();
		this.wsswarp$secretEdit.setValue(savedSecret == null ? "" : savedSecret);
		this.wsswarp$captureBottomWidgetBaseline();
		this.wsswarp$reflowWarpControls(selected);
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
		if (this.wsswarp$secretEdit != null) {
			ext.wsswarp$setSharedSecret(this.wsswarp$secretEdit.getValue());
		}
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

	@Inject(method = "render", at = @At("TAIL"))
	private void wsswarp$renderWarpWidgets(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (this.wsswarp$secretEdit != null && this.wsswarp$isSecretVisible()) {
			guiGraphics.drawString(this.font, WSSWARP_SECRET_LABEL,
					this.wsswarp$secretEdit.getX() + 1, this.wsswarp$secretEdit.getY() - 13, -6250336);
			this.wsswarp$secretEdit.render(guiGraphics, mouseX, mouseY, partialTick);
		}
		if (this.wsswarp$checkbox != null) {
			this.wsswarp$checkbox.render(guiGraphics, mouseX, mouseY, partialTick);
		}
	}

	@Unique
	private void wsswarp$reflowWarpControls(boolean showSecret) {
		if (this.wsswarp$checkbox == null || this.wsswarp$secretEdit == null) {
			return;
		}
		int x = this.ipEdit.getX();
		int checkboxY = this.ipEdit.getY() + this.ipEdit.getHeight() + WSSWARP_AFTER_IP_GAP;
		this.wsswarp$checkbox.setX(x);
		this.wsswarp$checkbox.setY(checkboxY);

		this.wsswarp$secretEdit.visible = showSecret;
		this.wsswarp$secretEdit.active = showSecret;
		this.wsswarp$secretEdit.setX(x);
		this.wsswarp$secretEdit.setY(checkboxY + this.wsswarp$checkbox.getHeight() + WSSWARP_AFTER_CHECKBOX_GAP);
		if (!showSecret) {
			this.wsswarp$secretEdit.setFocused(false);
		}
		this.wsswarp$applyBottomWidgetShift();
	}

	@Unique
	private boolean wsswarp$isSecretVisible() {
		return this.wsswarp$checkbox != null && this.wsswarp$checkbox.selected();
	}

	@Unique
	private void wsswarp$captureBottomWidgetBaseline() {
		this.wsswarp$baseWidgetY.clear();
		this.wsswarp$bottomWidgetsStartY = Integer.MAX_VALUE;
		for (var child : this.children()) {
			if (!(child instanceof AbstractWidget widget)) {
				continue;
			}
			if (widget == this.nameEdit || widget == this.ipEdit || widget == this.wsswarp$checkbox || widget == this.wsswarp$secretEdit) {
				continue;
			}
			this.wsswarp$baseWidgetY.put(widget, widget.getY());
			if (widget.getY() > this.ipEdit.getY()) {
				this.wsswarp$bottomWidgetsStartY = Math.min(this.wsswarp$bottomWidgetsStartY, widget.getY());
			}
		}
	}

	@Unique
	private void wsswarp$applyBottomWidgetShift() {
		if (this.wsswarp$baseWidgetY.isEmpty() || this.wsswarp$bottomWidgetsStartY == Integer.MAX_VALUE) {
			return;
		}
		int controlsBottom = this.wsswarp$isSecretVisible()
				? this.wsswarp$secretEdit.getY() + this.wsswarp$secretEdit.getHeight()
				: this.wsswarp$checkbox.getY() + this.wsswarp$checkbox.getHeight();
		int overlap = controlsBottom + 4 - this.wsswarp$bottomWidgetsStartY;
		int shift = Math.max(0, overlap);
		for (Map.Entry<AbstractWidget, Integer> entry : this.wsswarp$baseWidgetY.entrySet()) {
			entry.getKey().setY(entry.getValue() + shift);
		}
		this.wsswarp$compressResourceToActionGap(controlsBottom);
	}

	@Unique
	private void wsswarp$compressResourceToActionGap(int controlsBottom) {
		if (this.addButton == null) {
			return;
		}
		AbstractWidget resourcePackWidget = null;
		for (var child : this.children()) {
			if (!(child instanceof AbstractWidget widget) || widget == this.wsswarp$checkbox || widget == this.wsswarp$secretEdit) {
				continue;
			}
			if (widget.getY() >= this.addButton.getY()) {
				continue;
			}
			if (resourcePackWidget == null || widget.getY() > resourcePackWidget.getY()) {
				resourcePackWidget = widget;
			}
		}
		if (resourcePackWidget == null) {
			return;
		}

		int desiredGap = 2;
		int targetAddY = resourcePackWidget.getY() + resourcePackWidget.getHeight() + desiredGap;
		int minAllowedAddY = controlsBottom + 4;
		targetAddY = Math.max(targetAddY, minAllowedAddY);

		int currentAddY = this.addButton.getY();
		int moveUp = currentAddY - targetAddY;
		if (moveUp <= 0) {
			return;
		}

		for (var child : this.children()) {
			if (!(child instanceof Button button)) {
				continue;
			}
			// Move only the Add/Cancel column (same x and width as the Add button).
			if (button.getX() == this.addButton.getX() && button.getWidth() == this.addButton.getWidth() && button.getY() >= currentAddY) {
				button.setY(button.getY() - moveUp);
			}
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
