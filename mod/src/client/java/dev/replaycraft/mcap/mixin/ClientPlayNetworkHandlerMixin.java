package dev.replaycraft.mcap.mixin;

import dev.replaycraft.mcap.capture.PacketCapture;
import dev.replaycraft.mcap.capture.RawPacketCapture;
import io.netty.channel.Channel;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.s2c.play.GameJoinS2CPacket;
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
    private void mcap_onGameJoin(GameJoinS2CPacket packet, CallbackInfo ci) {
        // Don't interfere with capture system during replay - would corrupt active capture
        // session by resetting timing, writing replay packets as new data, and installing
        // pipeline handler on the fake EmbeddedChannel
        if (dev.replaycraft.mcap.replay.ReplayState.isReplayActive()) return;

        PacketCapture.onGameJoin();
        RawPacketCapture.INSTANCE.onGameJoin();
        // Reset recording state so spawnExistingEntities() and injectInventorySnapshot()
        // fire for each new session (not just the first one after client start)
        dev.replaycraft.mcap.capture.RecordingEventHandler.INSTANCE.reset();

        // Manually capture the GameJoinS2CPacket BEFORE installing the pipeline handler.
        // The pipeline handler only captures packets that arrive AFTER it's installed,
        // but GameJoinS2CPacket has already passed through the pipeline by this point.
        // Without this, the world is never created during replay (null world NPEs).
        RawPacketCapture.INSTANCE.captureDecodedPacket(packet);

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
