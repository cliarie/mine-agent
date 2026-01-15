package dev.replaycraft.mcap.mixin;

import dev.replaycraft.mcap.replay.ReplayState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.MathHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class ClientPlayerEntityMixin {
    
    /**
     * During replay, ensure getHandSwingProgress returns the actual swing progress
     * instead of 0, so the first-person arm swing animation is visible.
     */
    @Inject(method = "getHandSwingProgress", at = @At("HEAD"), cancellable = true)
    private void mcap_getHandSwingProgress(float tickDelta, CallbackInfoReturnable<Float> cir) {
        if (!ReplayState.isReplayActive()) return;
        
        LivingEntity self = (LivingEntity) (Object) this;
        MinecraftClient client = MinecraftClient.getInstance();
        
        // Only apply to the local player during replay
        if (self != client.player) return;
        
        // Calculate the actual hand swing progress
        if (self.handSwinging) {
            float progress = (self.handSwingTicks + tickDelta) / 6.0f; // 6 ticks is typical swing duration
            cir.setReturnValue(MathHelper.clamp(progress, 0.0f, 1.0f));
        } else {
            cir.setReturnValue(0.0f);
        }
    }
}
