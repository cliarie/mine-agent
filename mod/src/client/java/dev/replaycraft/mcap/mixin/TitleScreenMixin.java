package dev.replaycraft.mcap.mixin;

import dev.replaycraft.mcap.replay.ReplayViewerScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Injects a "Replay Center" button into the Minecraft title screen,
 * similar to ReplayMod's title screen button.
 */
@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {

    protected TitleScreenMixin(Text title) {
        super(title);
    }

    @Inject(method = "init", at = @At("RETURN"))
    private void mcap_addReplayCenterButton(CallbackInfo ci) {
        // Add "Replay Center" button on the title screen
        // Place it just below the main button group (Singleplayer/Multiplayer/Realms)
        // Main buttons start at height/4 + 48, each row is 24px apart
        // Row 0: Singleplayer (height/4 + 48)
        // Row 1: Multiplayer (height/4 + 72)
        // Row 2: Realms (height/4 + 96)
        // We place our button right after at row 3
        int buttonY = this.height / 4 + 48 + 24 * 3;
        this.addDrawableChild(
            ButtonWidget.builder(
                Text.literal("Replay Center"),
                button -> {
                    if (this.client != null) {
                        this.client.setScreen(new ReplayViewerScreen((TitleScreen)(Object)this));
                    }
                }
            ).dimensions(this.width / 2 - 100, buttonY, 200, 20).build()
        );
    }
}
