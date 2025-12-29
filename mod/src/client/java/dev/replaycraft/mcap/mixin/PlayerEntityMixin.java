package dev.replaycraft.mcap.mixin;

import dev.replaycraft.mcap.replay.ReplayState;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to freeze player updates during replay mode.
 * Prevents the game from overriding replayed position/state.
 */
@Mixin(ClientPlayerEntity.class)
public class PlayerEntityMixin {

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void mcap_onTick(CallbackInfo ci) {
        if (ReplayState.INSTANCE.isReplayActive()) {
            ci.cancel();
        }
    }

    @Inject(method = "sendMovementPackets", at = @At("HEAD"), cancellable = true)
    private void mcap_onSendMovementPackets(CallbackInfo ci) {
        if (ReplayState.INSTANCE.isReplayActive()) {
            ci.cancel();
        }
    }
}
