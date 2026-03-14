package dev.replaycraft.mcenv.mixin;

import dev.replaycraft.mcenv.input.AgentInputState;
import dev.replaycraft.mcenv.socket.ActionMessage;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.Input;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayerEntity.class)
public abstract class ClientPlayerEntityMixin {

    @Shadow public Input input;

    // runs at HEAD: set key states for sprint, camera, hotbar
    @Inject(method = "tickMovement", at = @At("HEAD"))
    private void mine_env_injectAgentInput(CallbackInfo ci) {
        if (!AgentInputState.INSTANCE.getActive()) return;

        ActionMessage action = AgentInputState.INSTANCE.getCurrent();
        if (action == null) return;

        ClientPlayerEntity player = (ClientPlayerEntity) (Object) this;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.options == null) return;

        // set key states (needed for sprint detection and other key-based checks)
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

    // runs AFTER input.tick(): override movement input fields directly
    @Inject(method = "tickMovement", at = @At(
        value = "INVOKE",
        target = "Lnet/minecraft/client/input/Input;tick(ZF)V",
        shift = At.Shift.AFTER
    ))
    private void mine_env_overrideMovementInput(CallbackInfo ci) {
        if (!AgentInputState.INSTANCE.getActive()) return;

        ActionMessage action = AgentInputState.INSTANCE.getCurrent();
        if (action == null) return;

        if (this.input == null) return;

        this.input.pressingForward = action.getForward();
        this.input.pressingBack = action.getBack();
        this.input.pressingLeft = action.getLeft();
        this.input.pressingRight = action.getRight();
        this.input.jumping = action.getJump();
        this.input.sneaking = action.getSneak();

        float forward = 0f;
        if (action.getForward()) forward += 1f;
        if (action.getBack()) forward -= 1f;

        float sideways = 0f;
        if (action.getLeft()) sideways += 1f;
        if (action.getRight()) sideways -= 1f;

        this.input.movementForward = forward;
        this.input.movementSideways = sideways;
    }

    private static void setKeyState(KeyBinding key, boolean pressed) {
        key.setPressed(pressed);
    }
}
