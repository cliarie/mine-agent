package dev.replaycraft.mcap.mixin;

import dev.replaycraft.mcap.capture.RecordingEventHandler;
import dev.replaycraft.mcap.replay.ReplayState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.math.BlockPos;
import net.minecraft.block.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.jetbrains.annotations.Nullable;

/**
 * Mixin on ClientWorld:
 * - Freezes world time during replay
 * - Captures syncWorldEvent (block break particles, sounds) for the local player
 *   during recording (matching ReplayMod's MixinWorldClient)
 * - Captures playSound for the local player during recording
 *
 * The server sends WorldEventS2CPacket (event 2001 = block break) to OTHER players,
 * not the player doing the breaking. Without this hook, block break particles and
 * sounds are missing from the replay.
 */
@Mixin(ClientWorld.class)
public class WorldMixin {

    @Inject(method = "setTimeOfDay", at = @At("HEAD"), cancellable = true)
    private void mcap_onSetTimeOfDay(long time, CallbackInfo ci) {
        if (ReplayState.INSTANCE.isReplayActive()) {
            ci.cancel();
        }
    }

    /**
     * Capture syncWorldEvent calls for the local player.
     * Matches ReplayMod's MixinWorldClient.replayModRecording_syncWorldEvent().
     *
     * When the local player breaks a block, the client calls syncWorldEvent with
     * event ID 2001 (BLOCK_BROKEN) at the break position. The server only sends
     * this to OTHER players. We inject a synthetic WorldEventS2CPacket so the
     * replay includes block break particles and sounds.
     */
    @Inject(method = "syncWorldEvent", at = @At("HEAD"))
    private void mcap_onSyncWorldEvent(@Nullable PlayerEntity source, int eventId, BlockPos pos, int data, CallbackInfo ci) {
        // Don't re-capture during replay
        if (ReplayState.isReplayActive()) return;
        // Only capture events caused by the local player
        if (source == null) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || source != client.player) return;
        RecordingEventHandler.INSTANCE.onClientWorldEvent(eventId, pos, data);
    }

    /**
     * Capture playSound calls for the local player.
     * Matches ReplayMod's MixinWorldClient.replayModRecording_playSound().
     *
     * When the local player causes a sound (block break, place, etc.), the client
     * plays it locally but the server only sends PlaySoundS2CPacket to OTHER players.
     * We inject a synthetic packet so the replay includes these sounds.
     */
    @Inject(method = "playSound(DDDLnet/minecraft/sound/SoundEvent;Lnet/minecraft/sound/SoundCategory;FFZ)V", at = @At("HEAD"))
    private void mcap_onPlaySound(double x, double y, double z, SoundEvent sound, SoundCategory category, float volume, float pitch, boolean useDistance, CallbackInfo ci) {
        // Don't re-capture during replay
        if (ReplayState.isReplayActive()) return;
        RecordingEventHandler.INSTANCE.onClientSound(sound, category, x, y, z, volume, pitch);
    }
}
