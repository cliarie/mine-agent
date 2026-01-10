package dev.replaycraft.mcap.mixin;

import dev.replaycraft.mcap.replay.ReplayState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.block.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin to freeze world state during replay mode.
 * Prevents the world from overriding replayed time and blocks.
 */
@Mixin(ClientWorld.class)
public class WorldMixin {

    @Inject(method = "setTimeOfDay", at = @At("HEAD"), cancellable = true)
    private void mcap_onSetTimeOfDay(long time, CallbackInfo ci) {
        if (ReplayState.INSTANCE.isReplayActive()) {
            ci.cancel();
        }
    }
    
    // Block updates during replay are now handled differently - we don't block them
    // because we want to allow replayed block updates to be applied
}
