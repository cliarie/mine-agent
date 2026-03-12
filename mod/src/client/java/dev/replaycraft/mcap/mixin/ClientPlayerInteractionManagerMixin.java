package dev.replaycraft.mcap.mixin;

import dev.replaycraft.mcap.capture.PacketCapture;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.registry.Registries;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Captures client-side interactions: block breaking progress, attacks, arm swings.
 * In singleplayer, these packets are only sent to OTHER players,
 * so we need to capture the local player's actions directly.
 */
@Mixin(ClientPlayerInteractionManager.class)
public abstract class ClientPlayerInteractionManagerMixin {
    
    private static final int PKT_CLIENT_BLOCK_BREAK_PROGRESS = 16;
    private static final int PKT_CLIENT_ARM_SWING = 17;
    private static final int PKT_CLIENT_ATTACK_ENTITY = 18;
    
    @Shadow
    private int blockBreakingCooldown;
    
    @Shadow
    private float currentBreakingProgress;
    
    @Shadow
    private BlockPos currentBreakingPos;
    
    // Track last swing tick to avoid spamming
    private int lastSwingTick = -10;
    
    // Capture block breaking progress updates
    @Inject(method = "updateBlockBreakingProgress", at = @At("RETURN"))
    private void mcap_onUpdateBlockBreakingProgress(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        if (!PacketCapture.isCapturing()) return;
        if (pos == null) return;
        
        // Convert progress (0.0-1.0) to stage (0-9, or -1 for no progress)
        int stage = (int)(currentBreakingProgress * 10.0f);
        if (stage > 9) stage = 9;
        if (stage < 0) stage = -1;
        
        // Pack data: x (4), y (4), z (4), stage (1) = 13 bytes
        byte[] data = new byte[13];
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(pos.getX());
        buf.putInt(pos.getY());
        buf.putInt(pos.getZ());
        buf.put((byte) stage);
        
        PacketCapture.capturePacket(PKT_CLIENT_BLOCK_BREAK_PROGRESS, data);
        
        // Capture arm swing every few ticks while mining (arm swings during mining)
        int currentTick = (int)(System.currentTimeMillis() / 50); // ~20 ticks per second
        if (currentTick - lastSwingTick >= 6) { // Swing every ~6 ticks (matches vanilla)
            captureArmSwing((byte) 0);
            lastSwingTick = currentTick;
        }
    }
    
    // Capture when block breaking is cancelled/stopped
    @Inject(method = "cancelBlockBreaking", at = @At("HEAD"))
    private void mcap_onCancelBlockBreaking(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (!PacketCapture.isCapturing()) return;
        if (currentBreakingPos == null) return;
        // Skip invalid positions (no active breaking)
        if (currentBreakingPos.getX() == -1 && currentBreakingPos.getY() == -1 && currentBreakingPos.getZ() == -1) return;
        
        // Send stage -1 to indicate breaking stopped
        byte[] data = new byte[13];
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(currentBreakingPos.getX());
        buf.putInt(currentBreakingPos.getY());
        buf.putInt(currentBreakingPos.getZ());
        buf.put((byte) -1);
        
        PacketCapture.capturePacket(PKT_CLIENT_BLOCK_BREAK_PROGRESS, data);
    }
    
    // Capture entity attacks (animals, mobs, players, etc.)
    @Inject(method = "attackEntity", at = @At("HEAD"))
    private void mcap_onAttackEntity(PlayerEntity player, Entity target, CallbackInfo ci) {
        if (!PacketCapture.isCapturing()) return;
        if (target == null) return;
        
        System.out.println("[MCAP] Attack entity: " + target.getType().getName().getString() + " id=" + target.getId());
        
        // Pack data: entityId (4), hand (1) = 5 bytes
        byte[] data = new byte[5];
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(target.getId());
        buf.put((byte) 0); // main hand
        
        PacketCapture.capturePacket(PKT_CLIENT_ATTACK_ENTITY, data);
        
        // Also capture arm swing since attacking always swings
        captureArmSwing((byte) 0);
    }
    
    // Helper to capture arm swing
    private void captureArmSwing(byte hand) {
        if (!PacketCapture.isCapturing()) return;
        
        // Pack data: hand (1) = 1 byte
        byte[] data = new byte[1];
        data[0] = hand;
        
        PacketCapture.capturePacket(PKT_CLIENT_ARM_SWING, data);
    }

    // Capture block placement for analytics (bed tracking in the End)
    @Inject(method = "interactBlock", at = @At("HEAD"))
    private void mcap_onInteractBlock(
            net.minecraft.client.network.ClientPlayerEntity player,
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
