package dev.replaycraft.mcap.mixin;

import dev.replaycraft.mcap.capture.RecordingEventHandler;
import dev.replaycraft.mcap.replay.ReplayState;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures block breaking progress events from the WorldRenderer.
 *
 * Mirrors ReplayMod's MixinRenderGlobal which hooks into
 * WorldRenderer.setBlockBreakingInfo() to capture block break animations.
 *
 * The server never sends BlockBreakingProgressS2CPacket to the client that is
 * breaking the block (only to other clients). During replay we need this data
 * to show the block breaking animation, so we capture it here and inject a
 * synthetic packet into the recording.
 */
@Mixin(WorldRenderer.class)
public class WorldRendererMixin {

    @Inject(method = "setBlockBreakingInfo", at = @At("HEAD"))
    private void mcap_onBlockBreakingInfo(int entityId, BlockPos pos, int progress, CallbackInfo ci) {
        // Don't re-capture during replay — the packet is already in the capture data
        // and re-injecting would corrupt the recording if one were active
        if (ReplayState.isReplayActive()) return;
        RecordingEventHandler.INSTANCE.onBlockBreakAnim(entityId, pos, progress);
    }
}
