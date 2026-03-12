package dev.replaycraft.mcap.mixin;

import dev.replaycraft.mcap.capture.PacketCapture;
import io.netty.buffer.Unpooled;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.util.math.BlockPos;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.s2c.play.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to capture specific S2C packets for replay.
 * Focus on inventory, container, and player state packets.
 */
@Mixin(ClientPlayNetworkHandler.class)
public class PacketCaptureMixin {

    // Packet IDs (these are arbitrary identifiers for our capture format)
    private static final int PKT_SCREEN_HANDLER_SLOT = 1;
    private static final int PKT_INVENTORY = 2;
    private static final int PKT_OPEN_SCREEN = 3;
    private static final int PKT_CLOSE_SCREEN = 4;
    private static final int PKT_PLAYER_POSITION = 5;
    private static final int PKT_ENTITY_POSITION = 6;
    private static final int PKT_BLOCK_UPDATE = 7;
    private static final int PKT_HELD_ITEM_CHANGE = 8;
    private static final int PKT_SET_CAMERA_ENTITY = 9;
    private static final int PKT_PLAYER_ACTION_RESPONSE = 10;
    private static final int PKT_HEALTH_UPDATE = 11;
    private static final int PKT_EXPERIENCE_UPDATE = 12;
    private static final int PKT_WORLD_TIME = 13;
    private static final int PKT_ENTITY_ANIMATION = 14;
    private static final int PKT_BLOCK_BREAK_PROGRESS = 15;
    private static final int PKT_ENTITY_STATUS = 19;
    private static final int PKT_ENTITIES_DESTROY = 20;

    // Inventory slot updates (chest, crafting, etc.)
    @Inject(method = "onScreenHandlerSlotUpdate", at = @At("HEAD"))
    private void mcap_onScreenHandlerSlotUpdate(ScreenHandlerSlotUpdateS2CPacket packet, CallbackInfo ci) {
        if (!PacketCapture.isCapturing()) return;
        try {
            PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
            packet.write(buf);
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            buf.release();
            PacketCapture.capturePacket(PKT_SCREEN_HANDLER_SLOT, data);
        } catch (Exception e) {
            // Ignore serialization errors
        }
    }

    // Full inventory sync
    @Inject(method = "onInventory", at = @At("HEAD"))
    private void mcap_onInventory(InventoryS2CPacket packet, CallbackInfo ci) {
        if (!PacketCapture.isCapturing()) return;
        try {
            PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
            packet.write(buf);
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            buf.release();
            PacketCapture.capturePacket(PKT_INVENTORY, data);
        } catch (Exception e) {
            // Ignore
        }
    }

    // Container/screen opened (chest, crafting table, etc.)
    @Inject(method = "onOpenScreen", at = @At("HEAD"))
    private void mcap_onOpenScreen(OpenScreenS2CPacket packet, CallbackInfo ci) {
        if (!PacketCapture.isCapturing()) return;
        try {
            PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
            packet.write(buf);
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            buf.release();
            PacketCapture.capturePacket(PKT_OPEN_SCREEN, data);
        } catch (Exception e) {
            // Ignore
        }
    }

    // Screen closed
    @Inject(method = "onCloseScreen", at = @At("HEAD"))
    private void mcap_onCloseScreen(CloseScreenS2CPacket packet, CallbackInfo ci) {
        if (!PacketCapture.isCapturing()) return;
        try {
            PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
            packet.write(buf);
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            buf.release();
            PacketCapture.capturePacket(PKT_CLOSE_SCREEN, data);
        } catch (Exception e) {
            // Ignore
        }
    }

    // Player position/look from server
    @Inject(method = "onPlayerPositionLook", at = @At("HEAD"))
    private void mcap_onPlayerPositionLook(PlayerPositionLookS2CPacket packet, CallbackInfo ci) {
        if (!PacketCapture.isCapturing()) return;
        try {
            PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
            packet.write(buf);
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            buf.release();
            PacketCapture.capturePacket(PKT_PLAYER_POSITION, data);
        } catch (Exception e) {
            // Ignore
        }
    }

    // Entity position updates
    @Inject(method = "onEntityPosition", at = @At("HEAD"))
    private void mcap_onEntityPosition(EntityPositionS2CPacket packet, CallbackInfo ci) {
        if (!PacketCapture.isCapturing()) return;
        try {
            PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
            packet.write(buf);
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            buf.release();
            PacketCapture.capturePacket(PKT_ENTITY_POSITION, data);
        } catch (Exception e) {
            // Ignore
        }
    }

    // Block updates - only capture blocks within 64 blocks of player
    private static final int BLOCK_CAPTURE_RADIUS = 64;
    
    @Inject(method = "onBlockUpdate", at = @At("HEAD"))
    private void mcap_onBlockUpdate(BlockUpdateS2CPacket packet, CallbackInfo ci) {
        if (!PacketCapture.isCapturing()) return;
        try {
            // Only capture block updates near the player to avoid capturing distant world updates
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null) return;
            
            BlockPos playerPos = client.player.getBlockPos();
            BlockPos blockPos = packet.getPos();
            double distance = Math.sqrt(playerPos.getSquaredDistance(blockPos));
            if (distance > BLOCK_CAPTURE_RADIUS) return;
            
            // Debug: log block updates (especially air blocks which indicate destruction)
            if (packet.getState().isAir()) {
                System.out.println("[MCAP] Block destroyed at " + blockPos);
            }
            
            PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
            packet.write(buf);
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            buf.release();
            PacketCapture.capturePacket(PKT_BLOCK_UPDATE, data);
        } catch (Exception e) {
            // Ignore
        }
    }

    // Held item change (hotbar selection from server)
    @Inject(method = "onUpdateSelectedSlot", at = @At("HEAD"))
    private void mcap_onUpdateSelectedSlot(UpdateSelectedSlotS2CPacket packet, CallbackInfo ci) {
        if (!PacketCapture.isCapturing()) return;
        try {
            PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
            packet.write(buf);
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            buf.release();
            PacketCapture.capturePacket(PKT_HELD_ITEM_CHANGE, data);
        } catch (Exception e) {
            // Ignore
        }
    }

    // Health update
    @Inject(method = "onHealthUpdate", at = @At("HEAD"))
    private void mcap_onHealthUpdate(HealthUpdateS2CPacket packet, CallbackInfo ci) {
        if (!PacketCapture.isCapturing()) return;
        try {
            PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
            packet.write(buf);
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            buf.release();
            PacketCapture.capturePacket(PKT_HEALTH_UPDATE, data);
        } catch (Exception e) {
            // Ignore
        }
    }

    // Experience update
    @Inject(method = "onExperienceBarUpdate", at = @At("HEAD"))
    private void mcap_onExperienceBarUpdate(ExperienceBarUpdateS2CPacket packet, CallbackInfo ci) {
        if (!PacketCapture.isCapturing()) return;
        try {
            PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
            packet.write(buf);
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            buf.release();
            PacketCapture.capturePacket(PKT_EXPERIENCE_UPDATE, data);
        } catch (Exception e) {
            // Ignore
        }
    }

    // World time update (day/night cycle)
    @Inject(method = "onWorldTimeUpdate", at = @At("HEAD"))
    private void mcap_onWorldTimeUpdate(WorldTimeUpdateS2CPacket packet, CallbackInfo ci) {
        if (!PacketCapture.isCapturing()) return;
        try {
            PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
            packet.write(buf);
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            buf.release();
            PacketCapture.capturePacket(PKT_WORLD_TIME, data);
        } catch (Exception e) {
            // Ignore
        }
    }
    
    // Entity animation (arm swing for attacking, damage, etc.)
    @Inject(method = "onEntityAnimation", at = @At("HEAD"))
    private void mcap_onEntityAnimation(EntityAnimationS2CPacket packet, CallbackInfo ci) {
        if (!PacketCapture.isCapturing()) return;
        try {
            PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
            packet.write(buf);
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            buf.release();
            PacketCapture.capturePacket(PKT_ENTITY_ANIMATION, data);
        } catch (Exception e) {
            // Ignore
        }
    }
    
    // Block breaking progress (mining animation)
    @Inject(method = "onBlockBreakingProgress", at = @At("HEAD"))
    private void mcap_onBlockBreakingProgress(BlockBreakingProgressS2CPacket packet, CallbackInfo ci) {
        if (!PacketCapture.isCapturing()) return;
        try {
            PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
            packet.write(buf);
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            buf.release();
            System.out.println("[MCAP] Capturing block break: entityId=" + packet.getEntityId() + ", pos=" + packet.getPos() + ", progress=" + packet.getProgress());
            PacketCapture.capturePacket(PKT_BLOCK_BREAK_PROGRESS, data);
        } catch (Exception e) {
            // Ignore
        }
    }
    
    // Entity status (death, damage effects, etc.)
    @Inject(method = "onEntityStatus", at = @At("HEAD"))
    private void mcap_onEntityStatus(EntityStatusS2CPacket packet, CallbackInfo ci) {
        // Forward to RecordingEventHandler for analytics entity kill detection
        try {
            dev.replaycraft.mcap.capture.RecordingEventHandler.INSTANCE.onEntityStatus(
                packet.getEntity(MinecraftClient.getInstance().world) != null
                    ? packet.getEntity(MinecraftClient.getInstance().world).getId() : -1,
                packet.getStatus()
            );
        } catch (Exception ignored) {}

        if (!PacketCapture.isCapturing()) return;
        try {
            PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
            packet.write(buf);
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            buf.release();
            PacketCapture.capturePacket(PKT_ENTITY_STATUS, data);
        } catch (Exception e) {
            // Ignore
        }
    }
    
    // Entity destruction (when entities are removed/killed)
    @Inject(method = "onEntitiesDestroy", at = @At("HEAD"))
    private void mcap_onEntitiesDestroy(EntitiesDestroyS2CPacket packet, CallbackInfo ci) {
        if (!PacketCapture.isCapturing()) return;
        try {
            PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
            packet.write(buf);
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            buf.release();
            PacketCapture.capturePacket(PKT_ENTITIES_DESTROY, data);
        } catch (Exception e) {
            // Ignore
        }
    }
}
