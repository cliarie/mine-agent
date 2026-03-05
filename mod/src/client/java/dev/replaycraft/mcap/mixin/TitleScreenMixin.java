package dev.replaycraft.mcap.mixin;

import dev.replaycraft.mcap.replay.ReplayViewerScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Injects a "Replay Center" button into the Minecraft title screen,
 * positioned between the Realms row and the Options/Quit row.
 *
 * Similar to ReplayMod's Mixin_MoveRealmsButton which adjusts button
 * positions to make room for the replay button.
 *
 * Vanilla layout (1.20.1 TitleScreen.initWidgetsNormal):
 *   Row 0: Singleplayer  → height/4 + 48        (y0)
 *   Row 1: Multiplayer   → height/4 + 48 + 24   (y0 + 24)
 *   Row 2: Realms        → height/4 + 48 + 48   (y0 + 48)
 *   (gap of 24px)
 *   Row 3: Options/Quit  → height/4 + 48 + 72   (y0 + 72)
 *
 * After our modification:
 *   Row 0: Singleplayer  → y0         (unchanged)
 *   Row 1: Multiplayer   → y0 + 24    (unchanged)
 *   Row 2: Realms        → y0 + 48    (unchanged)
 *   Row 3: Replay Center → y0 + 72    (NEW - in the gap)
 *   Row 4: Options/Quit  → y0 + 96    (shifted down by 24)
 */
@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {

    protected TitleScreenMixin(Text title) {
        super(title);
    }

    @Inject(method = "init", at = @At("RETURN"))
    private void mcap_addReplayCenterButton(CallbackInfo ci) {
        int y0 = this.height / 4 + 48;

        // The Options and Quit buttons are at y0 + 72 in vanilla.
        // Push them down by 24px to make room for the Replay Center button.
        int optionsQuitY = y0 + 72;
        for (var element : this.children()) {
            if (element instanceof ClickableWidget widget) {
                if (widget.getY() >= optionsQuitY) {
                    widget.setY(widget.getY() + 24);
                }
            }
        }

        // Place Replay Center in the gap between Realms and Options/Quit
        int replayY = y0 + 72; // where Options used to be; Options is now at y0 + 96
        this.addDrawableChild(
            ButtonWidget.builder(
                Text.literal("Replay Center"),
                button -> {
                    if (this.client != null) {
                        this.client.setScreen(new ReplayViewerScreen((TitleScreen)(Object)this));
                    }
                }
            ).dimensions(this.width / 2 - 100, replayY, 200, 20).build()
        );
    }
}
