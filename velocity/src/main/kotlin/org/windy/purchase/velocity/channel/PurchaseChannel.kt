package org.windy.purchase.velocity.channel

import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.messages.ChannelIdentifier
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier
import org.slf4j.Logger

/**
 * Plugin Message 通道 — 给 Mod 端预留的 API。
 *
 * 通道名：windypurchase:api
 *
 * 子服 Mod 可通过监听此通道接收二维码数据，自行在客户端渲染。
 * 也可以通过此通道向代理端发送请求（如确认支付）。
 *
 * 消息格式（代理 → Mod）：
 *   action:1 | orderId_len:2 | orderId:UTF-8 | qrUrl_len:2 | qrUrl:UTF-8
 *   action = 0x01: 二维码数据
 *   action = 0x02: 支付成功通知
 *   action = 0x03: 订单过期/取消
 *
 * 消息格式（Mod → 代理）：
 *   action:1 | data...
 *   action = 0x10: 请求重新发送二维码（携带 orderId）
 */
class PurchaseChannel(
    private val server: ProxyServer,
    private val logger: Logger
) {
    companion object {
        val CHANNEL_ID: ChannelIdentifier = MinecraftChannelIdentifier.create("windypurchase", "api")

        // 代理 → Mod
        const val ACTION_QR_CODE: Byte = 0x01
        const val ACTION_PAID: Byte = 0x02
        const val ACTION_CANCELLED: Byte = 0x03

        // Mod → 代理
        const val ACTION_REQUEST_QR: Byte = 0x10
        const val ACTION_CANCEL_GESTURE: Byte = 0x11  // 玩家手势取消（Shift+F）
    }

    /**
     * 向玩家发送二维码数据（通过 plugin message）。
     * Mod 端收到后可自行渲染（地图、GUI、自定义 HUD 等）。
     */
    fun sendQrCode(player: Player, orderId: String, qrUrl: String) {
        val payload = buildPayload(ACTION_QR_CODE, orderId, qrUrl)
        player.sendPluginMessage(CHANNEL_ID, payload)
    }

    /**
     * 向玩家发送支付成功通知。
     */
    fun sendPaid(player: Player, orderId: String, amount: String) {
        val payload = buildPayload(ACTION_PAID, orderId, amount)
        player.sendPluginMessage(CHANNEL_ID, payload)
    }

    /**
     * 向玩家发送订单取消/过期通知。
     */
    fun sendCancelled(player: Player, orderId: String) {
        val payload = buildPayload(ACTION_CANCELLED, orderId, orderId)
        player.sendPluginMessage(CHANNEL_ID, payload)
    }

    /**
     * 注册 plugin message listener（处理 Mod → 代理 的消息）。
     */
    fun register() {
        server.channelRegistrar.register(CHANNEL_ID)
        // Plugin message 入站通过 event 处理，需在 plugin 主类注册 @Subscribe
        logger.info("Plugin message channel registered: windypurchase:api")
    }

    fun unregister() {
        server.channelRegistrar.unregister(CHANNEL_ID)
    }

    /**
     * 构造发送 payload。
     * 格式：action(1) + orderId_len(2) + orderId(UTF-8) + data_len(2) + data(UTF-8)
     */
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

    /**
     * 解析 Mod 发来的 payload。
     */
    fun parsePayload(payload: ByteArray): Pair<Byte, String>? {
        if (payload.size < 3) return null
        val action = payload[0]
        val len = (payload[1].toInt() and 0xFF shl 8) or (payload[2].toInt() and 0xFF)
        if (payload.size < 3 + len) return null
        val data = String(payload, 3, len, Charsets.UTF_8)
        return action to data
    }
}
