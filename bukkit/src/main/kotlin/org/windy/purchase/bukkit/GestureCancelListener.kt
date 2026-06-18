package org.windy.purchase.bukkit

import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.plugin.messaging.PluginMessageRecipient

/**
 * 手势取消监听器 — 检测 Shift+F（交换主副手）取消待支付订单。
 *
 * 使用方法（Bukkit 插件端）：
 *   1. 注册 Plugin Message 通道：getServer().getMessenger().registerOutgoingPluginChannel(this, "windypurchase:api")
 *   2. 注册事件监听器：getServer().getPluginManager().registerEvents(GestureCancelListener(), this)
 *   3. 玩家按 Shift+F 时，自动向 Velocity 发送取消指令
 *
 * 注意：此文件是 Bukkit 端参考实现，需要在 Bukkit 子服插件中使用。
 */
class GestureCancelListener : Listener {

    companion object {
        private const val CHANNEL = "windypurchase:api"
        private const val ACTION_CANCEL_GESTURE: Byte = 0x11
    }

    /**
     * 检测 Shift + F（交换主副手）。
     * 如果玩家有待支付订单，发送 plugin message 到 Velocity 取消。
     */
    @EventHandler
    fun onSwapHand(event: PlayerSwapHandItemsEvent) {
        val player = event.player
        if (!player.isSneaking) return  // 必须潜行

        // 通过 plugin message 通知 Velocity 取消订单
        // orderId 需要从某个地方获取（如玩家 metadata、聊天记录等）
        // 这里用一个简化方案：让 Velocity 端查找该玩家的 PENDING 订单
        val payload = buildPayload(ACTION_CANCEL_GESTURE, player.name, "")
        (player as PluginMessageRecipient).sendPluginMessage(
            org.bukkit.plugin.java.JavaPlugin.getProvidingPlugin(GestureCancelListener::class.java),
            CHANNEL,
            payload
        )
    }

    private fun buildPayload(action: Byte, orderId: String, data: String): ByteArray {
        val orderIdBytes = orderId.toByteArray(Charsets.UTF_8)
        val dataBytes = data.toByteArray(Charsets.UTF_8)
        val buf = java.io.ByteArrayOutputStream()
        buf.write(action.toInt())
        buf.write(orderIdBytes.size shr 8)
        buf.write(orderIdBytes.size and 0xFF)
        buf.write(orderIdBytes)
        buf.write(dataBytes.size shr 8)
        buf.write(dataBytes.size and 0xFF)
        buf.write(dataBytes)
        return buf.toByteArray()
    }
}
