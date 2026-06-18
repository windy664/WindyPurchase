package org.windy.purchase.velocity.notify

import com.velocitypowered.api.proxy.ProxyServer
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import org.windy.purchase.core.config.Messages
import org.windy.purchase.core.config.PurchaseConfig
import org.windy.purchase.core.model.Order
import org.windy.purchase.velocity.channel.PurchaseChannel
import org.windy.purchase.velocity.render.MapPacketSender

/**
 * 支付结果通知 — 三种投递方式：
 * 1. 聊天可点击链接（默认，零依赖）
 * 2. Plugin Message 通道（Mod API，Mod 自行渲染二维码）
 * 3. 地图数据包（Map Data，需子服配合给地图物品）
 */
class PaymentNotifier(
    private val server: ProxyServer,
    private val config: PurchaseConfig,
    private val channel: PurchaseChannel,
    private val msg: Messages
) {
    /**
     * 订单创建后通知玩家。
     */
    fun onOrderCreated(order: Order) {
        val player = server.getPlayer(order.playerId).orElse(null)
            ?: server.allPlayers.find { it.username.equals(order.playerName, ignoreCase = true) }
            ?: return

        // 1. 聊天可点击链接（默认方式）
        sendChatPaymentUrl(player, order)

        // 2. Plugin Message 通知 Mod
        val paymentUrl = order.qrCodeUrl ?: order.rawUrl
        if (paymentUrl != null) {
            channel.sendQrCode(player, order.orderId, paymentUrl)
        }

        // 3. 地图数据（仅二维码模式，PayPal 不适用）
        if (order.qrCodeUrl != null) {
            sendMapData(player, order)
        }
    }

    /**
     * 支付成功后通知玩家。
     */
    fun onPaid(order: Order) {
        // 先按 UUID 找，找不到按玩家名找（爱发电虚拟订单用玩家名作 ID）
        val player = server.getPlayer(order.playerId).orElse(null)
            ?: server.allPlayers.find { it.username.equals(order.playerName, ignoreCase = true) }

        // 通知玩家
        if (player != null) {
            val paidMsg = this.msg.orderPaid()
                .replace("%amount%", String.format("%.2f", order.amount / 100.0))
            player.sendMessage(Component.text(paidMsg))

            // 通知 Mod：支付成功
            channel.sendPaid(player, order.orderId, String.format("%.2f", order.amount / 100.0))
        }

        // 执行奖励命令
        for (cmd in order.commands) {
            val finalCmd = cmd.replace("%player%", order.playerName)
            server.commandManager.executeAsync(server.consoleCommandSource, finalCmd)
        }

        // 广播（只有商品购买才广播）
        if (order.productId != null) {
            val broadcastMsg = this.msg.orderBroadcast()
                .replace("%player%", order.playerName)
                .replace("%amount%", String.format("%.2f", order.amount / 100.0))
            server.allPlayers.forEach { p ->
                p.sendMessage(Component.text(broadcastMsg))
            }
        }
    }

    /**
     * 订单过期/取消通知。
     */
    fun onCancelled(order: Order) {
        val player = server.getPlayer(order.playerId).orElse(null) ?: return
        player.sendMessage(Component.text(msg.orderCancelled()))
        channel.sendCancelled(player, order.orderId)
    }

    // ==================== 内部方法 ====================

    /**
     * 方式 1：聊天发送可点击 URL。
     * 根据支付方式区分：
     *   - 支付宝/微信：发送二维码 URL（扫码支付）
     *   - PayPal：发送结账页面链接（点击跳转）
     */
    private fun sendChatPaymentUrl(player: com.velocitypowered.api.proxy.Player, order: Order) {
        player.sendMessage(Component.text(msg.orderCreated()))

        val paymentUrl = order.qrCodeUrl ?: order.rawUrl

        // 二维码 URL（支付宝/微信）→ 在聊天中直接显示 ASCII 二维码
        if (order.qrCodeUrl != null) {
            player.sendMessage(Component.text("§7用手机扫描以下二维码："))
            player.sendMessage(Component.text("§8§m                                        "))
            val qrLines = org.windy.purchase.core.util.AsciiQrCode.generate(order.qrCodeUrl!!)
            for (line in qrLines) {
                player.sendMessage(Component.text("§f$line"))
            }
            player.sendMessage(Component.text("§8§m                                        "))
        }

        if (paymentUrl != null) {
            val label = msg.payLabel(order.paymentMethod)
            val hoverText = msg.payHover(order.paymentMethod)

            // 可点击链接 + hover 提示
            val urlMsg = Component.text(label)
                .clickEvent(ClickEvent.openUrl(paymentUrl))
                .hoverEvent(HoverEvent.showText(Component.text(hoverText)))
            player.sendMessage(urlMsg)

            // 纯文本 URL（供复制）
            player.sendMessage(Component.text(msg.link(paymentUrl)))
        }

        // 爱发电特殊提示
        if (order.paymentMethod == "afdian") {
            player.sendMessage(Component.text(msg.afdianRemark(order.playerName)))
            player.sendMessage(Component.text(msg.afdianRemarkHint()))
        }

        player.sendMessage(Component.text(msg.orderId(order.orderId)))
    }

    /**
     * 方式 3：发送地图数据。
     * 通过 plugin message 通知子服给玩家发地图物品，同时发地图像素数据。
     * 需要子服有对应的 mod/plugin 接收并执行 /give + 转发地图包。
     */
    private fun sendMapData(player: com.velocitypowered.api.proxy.Player, order: Order) {
        val qrUrl = order.qrCodeUrl ?: return
        val mapId = order.orderId.hashCode() and 0x7FFFFFFF
        try {
            MapPacketSender.sendMapQrCode(player, qrUrl, mapId)
        } catch (e: Exception) {
            org.slf4j.LoggerFactory.getLogger(PaymentNotifier::class.java)
                .debug("Map QR send failed: {}", e.message)
        }
    }
}
