package dev.replaycraft.mcenv.socket

import com.google.gson.annotations.SerializedName

// agent -> mod: sent each step
data class ActionMessage(
    @SerializedName("forward")       val forward: Boolean = false,
    @SerializedName("back")          val back: Boolean = false,
    @SerializedName("left")          val left: Boolean = false,
    @SerializedName("right")         val right: Boolean = false,
    @SerializedName("jump")          val jump: Boolean = false,
    @SerializedName("sneak")         val sneak: Boolean = false,
    @SerializedName("sprint")        val sprint: Boolean = false,
    @SerializedName("attack")        val attack: Boolean = false,
    @SerializedName("use")           val use: Boolean = false,
    @SerializedName("drop")          val drop: Boolean = false,
    @SerializedName("inventory_slot") val inventorySlot: Int = -1,
    @SerializedName("delta_yaw")     val deltaYaw: Float = 0f,
    @SerializedName("delta_pitch")   val deltaPitch: Float = 0f
)

// mod -> agent: sent after each tick
data class ObservationMessage(
    @SerializedName("x")             val x: Double,
    @SerializedName("y")             val y: Double,
    @SerializedName("z")             val z: Double,
    @SerializedName("yaw")           val yaw: Float,
    @SerializedName("pitch")         val pitch: Float,
    @SerializedName("health")        val health: Float,
    @SerializedName("food")          val food: Int,
    @SerializedName("alive")         val alive: Boolean,
    @SerializedName("on_ground")     val onGround: Boolean,
    @SerializedName("inventory")     val inventory: List<ItemStackInfo>,
    @SerializedName("hotbar_slot")   val hotbarSlot: Int,
    @SerializedName("dimension")     val dimension: String,
    @SerializedName("tick")          val tick: Long,
    @SerializedName("reward")        val reward: Double = 0.0,
    @SerializedName("done")          val done: Boolean = false
)

data class ItemStackInfo(
    @SerializedName("slot")          val slot: Int,
    @SerializedName("id")            val id: String,
    @SerializedName("count")         val count: Int
)
