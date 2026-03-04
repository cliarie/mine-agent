package dev.replaycraft.mcap.mixin;

import io.netty.channel.Channel;
import net.minecraft.network.ClientConnection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor to get the Netty Channel from ClientConnection
 * so we can inject our packet recorder into the pipeline.
 */
@Mixin(ClientConnection.class)
public interface ClientConnectionAccessor {
    @Accessor("channel")
    Channel mcap_getChannel();
}
