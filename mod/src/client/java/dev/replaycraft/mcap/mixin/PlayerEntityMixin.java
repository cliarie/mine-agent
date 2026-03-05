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
 * safely suppress sendMovementPackets() to avoid unnecessary outgoing packets.
 *
 * We do NOT cancel tick() because the player entity still needs its bookkeeping
 * to function (animations, interpolation, etc.). The ReplayHandler overrides
 * position/rotation every tick after the normal tick processing.
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
}
