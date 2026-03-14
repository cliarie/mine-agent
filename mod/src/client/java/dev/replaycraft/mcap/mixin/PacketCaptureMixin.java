package dev.replaycraft.mcap.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Forwards entity status events to analytics for kill detection (e.g. dragon kill).
 */
@Mixin(ClientPlayNetworkHandler.class)
public class PacketCaptureMixin {

    @Inject(method = "onEntityStatus", at = @At("HEAD"))
    private void mcap_onEntityStatus(EntityStatusS2CPacket packet, CallbackInfo ci) {
        try {
            dev.replaycraft.mcap.capture.RecordingEventHandler.INSTANCE.onEntityStatus(
                packet.getEntity(MinecraftClient.getInstance().world) != null
                    ? packet.getEntity(MinecraftClient.getInstance().world).getId() : -1,
                packet.getStatus()
            );
        } catch (Exception ignored) {}
    }
}
