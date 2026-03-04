package dev.replaycraft.mcap.mixin;

import dev.replaycraft.mcap.capture.PacketCapture;
import dev.replaycraft.mcap.capture.RawPacketCapture;
import io.netty.channel.Channel;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.ClientConnection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public class ClientPlayNetworkHandlerMixin {

    @Shadow
    private ClientConnection connection;

    @Inject(method = "onGameJoin", at = @At("HEAD"))
    private void mcap_onGameJoin(CallbackInfo ci) {
        PacketCapture.onGameJoin();
        RawPacketCapture.INSTANCE.onGameJoin();

        // Install Netty pipeline packet recorder for comprehensive S2C capture.
        // This captures ALL server-to-client packets at the network level,
        // matching ReplayMod's architecture for faithful replay.
        try {
            Channel channel = ((ClientConnectionAccessor) connection).mcap_getChannel();
            if (channel != null) {
                RawPacketCapture.INSTANCE.installOnChannel(channel);
            }
        } catch (Exception e) {
            System.err.println("[MCAP] Failed to install pipeline packet recorder: " + e.getMessage());
        }
    }
}
