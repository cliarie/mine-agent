package dev.replaycraft.mcap.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.network.ClientConnection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor to replace MinecraftClient's active network connection.
 * Used by ReplayHandler to swap in the fake replay connection.
 */
@Mixin(MinecraftClient.class)
public interface MinecraftClientAccessor {
    @Accessor("integratedServerConnection")
    ClientConnection mcap_getConnection();

    /**
     * WARNING: Only use this to swap between real and fake connections
     * for replay purposes. Setting this to null or an invalid connection
     * will crash the game.
     */
    @Accessor("integratedServerConnection")
    void mcap_setConnection(ClientConnection connection);
}
