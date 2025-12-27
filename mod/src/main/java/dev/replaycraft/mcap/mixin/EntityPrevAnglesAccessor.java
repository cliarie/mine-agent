package dev.replaycraft.mcap.mixin;

import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Entity.class)
public interface EntityPrevAnglesAccessor {
    @Accessor("prevYaw")
    void mcap_setPrevYaw(float value);

    @Accessor("prevPitch")
    void mcap_setPrevPitch(float value);
}
