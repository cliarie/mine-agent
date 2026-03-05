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
        // Place it below the quit button row: y = height/4 + 48 + 24*3 = height/4 + 120
        // The language/options/quit buttons end around height/4 + 48 + 72 + 12 + 24 = height/4 + 156
        // We place our button just below those at height/4 + 48 + 24*5 to avoid overlaps
        int buttonY = this.height / 4 + 48 + 24 * 5;
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
