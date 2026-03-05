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

    @Inject(method = "initWidgetsNormal", at = @At("RETURN"))
    private void mcap_addReplayCenterButton(int y, int spacingY, CallbackInfo ci) {
        // Add "Replay Center" button below the existing buttons
        // Position it centered, below the row of Singleplayer/Multiplayer/Realms buttons
        this.addDrawableChild(
            ButtonWidget.builder(
                Text.literal("Replay Center"),
                button -> {
                    if (this.client != null) {
                        this.client.setScreen(new ReplayViewerScreen((TitleScreen)(Object)this));
                    }
                }
            ).dimensions(this.width / 2 - 100, y + spacingY * 3, 200, 20).build()
        );
    }
}
