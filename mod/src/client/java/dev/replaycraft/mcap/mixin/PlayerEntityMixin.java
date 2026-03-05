package dev.replaycraft.mcap.mixin;

import dev.replaycraft.mcap.replay.ReplayState;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to adjust player behavior during replay mode.
 *
 * With the ReplayHandler (fake connection) approach, we ARE connected to a fake
 * ClientConnection via an EmbeddedChannel - there is no real server. So we CAN
 * safely suppress sendMovementPackets() and tickMovement() during replay.
 *
 * tickMovement() MUST be cancelled during replay because it applies client-side
 * physics (gravity, collision, movement input). Without cancelling it, the player
 * falls through terrain between tick dispatches because MC applies gravity every
 * client tick. ReplayMod solves this with a CameraEntity (spectator-like entity);
 * we solve it by simply cancelling the local physics and letting ReplayHandler
 * set position/rotation from the captured tick records.
 */
@Mixin(ClientPlayerEntity.class)
public class PlayerEntityMixin {

    /**
     * Suppress outgoing movement packets during replay.
     * With the fake connection there's no server to receive them anyway,
     * and they would just accumulate in the EmbeddedChannel's outbound buffer.
     */
    @Inject(method = "sendMovementPackets", at = @At("HEAD"), cancellable = true)
    private void mcap_onSendMovementPackets(CallbackInfo ci) {
        if (ReplayState.INSTANCE.isReplayActive()) {
            ci.cancel();
        }
    }

    /**
     * Suppress client-side physics (gravity, collision, movement input) during replay.
     * Without this, the player falls through terrain because MC applies gravity every
     * client tick, and our position override only runs at END_CLIENT_TICK (after physics).
     * The ReplayHandler sets position/rotation from captured tick records instead.
     */
    @Inject(method = "tickMovement", at = @At("HEAD"), cancellable = true)
    private void mcap_onTickMovement(CallbackInfo ci) {
        if (ReplayState.INSTANCE.isReplayActive()) {
            ci.cancel();
        }
    }
}
