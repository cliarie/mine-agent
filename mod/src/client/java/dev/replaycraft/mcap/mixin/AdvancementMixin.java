package dev.replaycraft.mcap.mixin;

import dev.replaycraft.mcap.capture.RecordingEventHandler;
import dev.replaycraft.mcap.ml.MlSessionManager;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.AdvancementUpdateS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures advancement grants to detect speedrun split milestones:
 * - Bastion entry (minecraft:nether/find_bastion)
 * - Fortress entry (minecraft:nether/find_fortress)
 * - Stronghold / Eye Spy (minecraft:story/follow_ender_eye)
 * - Blind travel (minecraft:nether/fast_travel — 7km+ overworld via nether)
 *
 * Also forwards all earned advancements to MlSessionManager for event recording.
 */
@Mixin(ClientPlayNetworkHandler.class)
public class AdvancementMixin {

    @Inject(method = "onAdvancements", at = @At("RETURN"))
    private void mcap_onAdvancements(AdvancementUpdateS2CPacket packet, CallbackInfo ci) {
        try {
            var earned = packet.getAdvancementsToEarn();
            if (earned == null || earned.isEmpty()) return;

            for (var entry : earned.entrySet()) {
                String id = entry.getKey().toString();

                // Forward to analytics RunTracker for split detection
                switch (id) {
                    case "minecraft:nether/find_bastion":
                    case "minecraft:nether/find_fortress":
                    case "minecraft:story/follow_ender_eye":
                    case "minecraft:nether/fast_travel":
                        RecordingEventHandler.INSTANCE.onAdvancement(id);
                        break;
                }

                // Forward all advancements to ML event stream
                MlSessionManager.INSTANCE.onAdvancement(id);
            }
        } catch (Exception ignored) {}
    }
}
