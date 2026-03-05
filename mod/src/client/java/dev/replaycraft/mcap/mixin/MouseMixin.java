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
 * - onMouseMove (prevents mouse-driven camera rotation)
 * - onMouseButton (prevents click actions during replay)
 * - onMouseScroll (prevents scroll wheel hotbar changes during replay)
 */
@Mixin(Mouse.class)
public class MouseMixin {

    /**
     * Cancel mouse movement processing during replay.
     * This is the key fix: without this, mouse movements during replay
     * would call Entity.changeLookDirection() and override the recorded
     * yaw/pitch values, causing the camera to jitter or deviate from
     * the captured first-person perspective.
     */
    @Inject(method = "onMouseMove", at = @At("HEAD"), cancellable = true)
    private void mcap_onMouseMove(long window, double x, double y, CallbackInfo ci) {
        if (ReplayState.isReplayActive()) {
            ci.cancel();
        }
    }

    /**
     * Cancel mouse button processing during replay.
     * Prevents the player from clicking (attack/use) during replay,
     * which would cause unintended interactions with the replay world.
     */
    @Inject(method = "onMouseButton", at = @At("HEAD"), cancellable = true)
    private void mcap_onMouseButton(long window, int button, int action, int mods, CallbackInfo ci) {
        if (ReplayState.isReplayActive()) {
            ci.cancel();
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
