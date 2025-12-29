package dev.replaycraft.mcap.mixin;

import dev.replaycraft.mcap.capture.PacketCapture;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public class ClientPlayNetworkHandlerMixin {

    @Inject(method = "onGameJoin", at = @At("HEAD"))
    private void mcap_onGameJoin(CallbackInfo ci) {
        PacketCapture.INSTANCE.onGameJoin();
    }

    // Intercept all incoming S2C packets via the generic handler
    // We'll use a more targeted approach for specific packets
}
