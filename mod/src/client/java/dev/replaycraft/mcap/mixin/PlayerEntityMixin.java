package dev.replaycraft.mcap.mixin;

import dev.replaycraft.mcap.replay.ReplayState;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to adjust player behavior during replay mode.
 *
 * IMPORTANT: We do NOT cancel tick() or sendMovementPackets() because doing so
 * prevents critical entity bookkeeping and causes the integrated server to
 * disconnect the player ("save and quit" behavior). Instead, the replay system
 * overrides position/rotation every tick via ReplayController.applyRecordedTick(),
 * which runs at END_CLIENT_TICK (after the normal tick processing).
 *
 * The normal tick still processes keyboard input, but the position is immediately
 * overridden by the replay data, so the player visually follows the recording.
 */
@Mixin(ClientPlayerEntity.class)
public class PlayerEntityMixin {
    // Intentionally empty - tick() and sendMovementPackets() must NOT be cancelled.
    // See class javadoc for explanation.
}
