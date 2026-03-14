package dev.replaycraft.mcenv.mixin;

import dev.replaycraft.mcenv.input.AgentInputState;
import dev.replaycraft.mcenv.socket.ActionMessage;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayerEntity.class)
public class ClientPlayerEntityMixin {

    @Inject(method = "tickMovement", at = @At("HEAD"))
    private void mine_env_injectAgentInput(CallbackInfo ci) {
        if (!AgentInputState.INSTANCE.getActive()) return;

        ActionMessage action = AgentInputState.INSTANCE.getCurrent();
        if (action == null) return;

        ClientPlayerEntity player = (ClientPlayerEntity) (Object) this;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.options == null) return;

        // movement keys
        setKeyState(client.options.forwardKey, action.getForward());
        setKeyState(client.options.backKey, action.getBack());
        setKeyState(client.options.leftKey, action.getLeft());
        setKeyState(client.options.rightKey, action.getRight());
        setKeyState(client.options.jumpKey, action.getJump());
        setKeyState(client.options.sneakKey, action.getSneak());
        setKeyState(client.options.sprintKey, action.getSprint());

        // camera
        player.setYaw(player.getYaw() + action.getDeltaYaw());
        player.setPitch(
            Math.max(-90f, Math.min(90f, player.getPitch() + action.getDeltaPitch()))
        );

        // hotbar slot
        if (action.getInventorySlot() >= 0 && action.getInventorySlot() <= 8) {
            player.getInventory().selectedSlot = action.getInventorySlot();
        }
    }

    private static void setKeyState(KeyBinding key, boolean pressed) {
        key.setPressed(pressed);
    }
}
