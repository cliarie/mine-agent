package dev.replaycraft.mcap.mixin;

import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.GameJoinS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Resets analytics state on game join so tracking starts fresh each session.
 */
@Mixin(ClientPlayNetworkHandler.class)
public class ClientPlayNetworkHandlerMixin {

    @Inject(method = "onGameJoin", at = @At("HEAD"))
    private void mcap_onGameJoin(GameJoinS2CPacket packet, CallbackInfo ci) {
        dev.replaycraft.mcap.capture.RecordingEventHandler.INSTANCE.reset();
    }
}
