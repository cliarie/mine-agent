package dev.replaycraft.mcenv.observation

import dev.replaycraft.mcenv.socket.ItemStackInfo
import dev.replaycraft.mcenv.socket.ObservationMessage
import net.minecraft.client.MinecraftClient
import net.minecraft.registry.Registries

object ObservationCapture {

    fun capture(tick: Long): ObservationMessage? {
        val client = MinecraftClient.getInstance()
        val player = client.player ?: return null
        val world = client.world ?: return null

        val inventory = mutableListOf<ItemStackInfo>()
        for (i in 0 until player.inventory.size()) {
            val stack = player.inventory.getStack(i)
            if (!stack.isEmpty) {
                inventory.add(ItemStackInfo(
                    slot = i,
                    id = Registries.ITEM.getId(stack.item).toString(),
                    count = stack.count
                ))
            }
        }

        return ObservationMessage(
            x = player.x,
            y = player.y,
            z = player.z,
            yaw = player.yaw,
            pitch = player.pitch,
            health = player.health,
            food = player.hungerManager.foodLevel,
            alive = !player.isDead,
            onGround = player.isOnGround,
            inventory = inventory,
            hotbarSlot = player.inventory.selectedSlot,
            dimension = world.registryKey.value.toString(),
            tick = tick
        )
    }
}
