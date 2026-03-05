package dev.replaycraft.mcap.mixin;

import dev.replaycraft.mcap.replay.ReplayState;
import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppress mouse input during replay to prevent the player from
 * accidentally moving the camera with their mouse.
 *
 * During replay, the camera position and rotation are controlled
 * entirely by the captured tick records. Any mouse movement would
 * fight against the recorded rotation values, causing jitter.
 *
 * We suppress:
 * - onCursorPos (prevents mouse-driven camera rotation / yaw+pitch changes)
 * - onMouseScroll (prevents scroll wheel hotbar changes during replay)
 *
 * We DO NOT suppress:
 * - onMouseButton (allows mouse clicks for UI interaction / Escape menu)
 */
@Mixin(Mouse.class)
public class MouseMixin {

    /**
     * Cancel ALL mouse-driven cursor movement during replay.
     * This prevents:
     * - Camera rotation changes (yaw/pitch) when no screen is open
     * - Live cursor interfering with replayed inventory/container screens
     *
     * The user exits replay via Escape key (handled by McapReplayClient),
     * so cursor movement for UI interaction is not needed during replay.
     */
    @Inject(method = "onCursorPos", at = @At("HEAD"), cancellable = true)
    private void mcap_onCursorPos(long window, double x, double y, CallbackInfo ci) {
        if (ReplayState.isReplayActive()) {
            ci.cancel();
        }
    }

    /**
     * Cancel mouse clicks during replay when a handled screen (inventory, chest,
     * crafting, etc.) is open. This prevents the user from accidentally moving
     * items during replayed inventory views. Mouse clicks are still allowed
     * when no screen is open (no effect anyway since cursor is suppressed).
     */
    @Inject(method = "onMouseButton", at = @At("HEAD"), cancellable = true)
    private void mcap_onMouseButton(long window, int button, int action, int mods, CallbackInfo ci) {
        if (ReplayState.isReplayActive()) {
            net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
            if (client.currentScreen instanceof net.minecraft.client.gui.screen.ingame.HandledScreen) {
                ci.cancel();
            }
        }
    }

    /**
     * Cancel mouse scroll during replay.
     * Prevents scroll wheel from changing the hotbar slot during replay
     * (the hotbar is controlled by captured tick records).
     */
    @Inject(method = "onMouseScroll", at = @At("HEAD"), cancellable = true)
    private void mcap_onMouseScroll(long window, double horizontal, double vertical, CallbackInfo ci) {
        if (ReplayState.isReplayActive()) {
            ci.cancel();
        }
    }
}
