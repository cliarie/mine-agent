package dev.replaycraft.mcap.mixin;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.item.BlockItem;
import net.minecraft.registry.Registries;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Captures block placement for analytics (bed tracking in the End).
 */
@Mixin(ClientPlayerInteractionManager.class)
public abstract class ClientPlayerInteractionManagerMixin {

    @Inject(method = "interactBlock", at = @At("HEAD"))
    private void mcap_onInteractBlock(
            ClientPlayerEntity player,
            Hand hand,
            BlockHitResult hitResult,
            CallbackInfoReturnable<ActionResult> cir
    ) {
        try {
            net.minecraft.item.ItemStack stack = player.getStackInHand(hand);
            if (!stack.isEmpty() && stack.getItem() instanceof BlockItem) {
                String blockId = Registries.BLOCK.getId(((BlockItem) stack.getItem()).getBlock()).toString();
                dev.replaycraft.mcap.capture.RecordingEventHandler.INSTANCE.onBlockPlaced(blockId);
            }
        } catch (Exception ignored) {}
    }
}
