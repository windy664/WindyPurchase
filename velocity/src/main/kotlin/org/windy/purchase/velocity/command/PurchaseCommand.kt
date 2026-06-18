package org.windy.purchase.velocity.command

import com.velocitypowered.api.command.RawCommand
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import net.kyori.adventure.text.Component
import org.slf4j.Logger
import org.windy.purchase.core.config.DailyRewardManager
import org.windy.purchase.core.config.Messages
import org.windy.purchase.core.config.PromoCodeManager
import org.windy.purchase.core.db.Database
import org.windy.purchase.core.model.OrderStatus
import org.windy.purchase.core.payment.PaymentManager
import org.windy.purchase.velocity.notify.PaymentNotifier
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * `/purchase` 命令。
 *
 * 玩家体验优化：
 * - 下单异步 + 异常兜底
 * - 超时倒计时提醒
 * - /purchase pending 查待支付订单
 * - /purchase cancel 取消待支付订单
 */
class PurchaseCommand(
    private val manager: PaymentManager,
    private val db: Database,
    private val server: ProxyServer,
    private val logger: Logger,
    private val notifier: PaymentNotifier,
    private val msg: Messages,
    private val promoManager: PromoCodeManager,
    private val dailyReward: DailyRewardManager
) : RawCommand {

    /** 玩家最近一笔待支付订单 ID（方便 /purchase pending 和 cancel） */
    private val recentPending = ConcurrentHashMap<String, String>()

    override fun execute(invocation: RawCommand.Invocation) {
        val source = invocation.source()
        val args = invocation.arguments().trim().split("\\s+".toRegex()).filter { it.isNotBlank() }

        if (args.isEmpty()) {
            sendHelp(source)
            return
        }

        when (args[0].lowercase()) {
            "list" -> handleList(source)
            "buy" -> handleBuy(source, args)
            "pay" -> handlePay(source, args)
            "history" -> handleHistory(source)
            "check" -> handleCheck(source, args)
            "top" -> handleTop(source)
            "pending" -> handlePending(source)
            "cancel" -> handleCancel(source)
            else -> sendHelp(source)
        }
    }

    // ==================== 商品列表 ====================

    private fun handleList(source: com.velocitypowered.api.command.CommandSource) {
        val products = db.getAllProducts()
        if (products.isEmpty()) {
            source.sendMessage(Component.text("§c暂无可购买的商品。"))
            return
        }
        source.sendMessage(Component.text("§6====== 商品列表 ======"))
        for (p in products) {
            source.sendMessage(Component.text("§e${p.id} §f- §a${p.name} §f| §6${String.format("%.2f", p.price / 100.0)} 元"))
            if (p.description.isNotBlank()) {
                source.sendMessage(Component.text("  §7${p.description}"))
            }
        }
        source.sendMessage(Component.text("§7使用 /purchase buy <商品ID> 购买"))
    }

    // ==================== 购买商品 ====================

    private fun handleBuy(source: com.velocitypowered.api.command.CommandSource, args: List<String>) {
        if (source !is Player) { source.sendMessage(Component.text("§c此命令只能由玩家执行。")); return }
        if (args.size < 2) { source.sendMessage(Component.text("§c用法: /purchase buy <商品ID> [alipay|wechat]")); return }

        val product = db.getProduct(args[1])
        if (product == null) { source.sendMessage(Component.text("§c商品不存在: ${args[1]}")); return }

        val perm = product.permission
        if (perm != null && perm.isNotBlank() && !source.hasPermission(perm)) {
            source.sendMessage(Component.text("§c你没有权限购买此商品。")); return
        }
        if (product.cooldown > 0 && isOnCooldown(source.uniqueId.toString(), product.id, product.cooldown)) {
            source.sendMessage(Component.text("§c购买冷却中，请稍后再试。（冷却 ${product.cooldown} 秒）")); return
        }

        val method = if (args.size >= 3) args[2] else resolvePaymentMethod(source) ?: return
        source.sendMessage(Component.text("§a正在创建订单..."))

        CompletableFuture.runAsync {
            try {
                val order = manager.createOrder(
                    playerId = source.uniqueId.toString(),
                    playerName = source.username,
                    amountFen = product.price,
                    paymentMethod = method,
                    productId = product.id,
                    commands = product.commands.map { it.replace("%player%", source.username) }
                )
                if (order != null) {
                    recentPending[source.uniqueId.toString()] = order.orderId
                    notifier.onOrderCreated(order)
                    scheduleTimeoutReminder(source, order.orderId, order.createdAt)
                } else {
                    source.sendMessage(Component.text("§c创建订单失败，请稍后重试。"))
                }
            } catch (e: Exception) {
                logger.error("创建订单异常: player={}", source.username, e)
                source.sendMessage(Component.text("§c创建订单出错: ${e.message}"))
            }
        }
    }

    // ==================== 直接充值 ====================

    private fun handlePay(source: com.velocitypowered.api.command.CommandSource, args: List<String>) {
        if (source !is Player) { source.sendMessage(Component.text("§c此命令只能由玩家执行。")); return }
        if (args.size < 2) { source.sendMessage(Component.text("§c用法: /purchase pay <金额(元)> [alipay|wechat] [优惠码]")); return }

        val amountYuan = args[1].toDoubleOrNull()
        if (amountYuan == null || amountYuan < 0.01) {
            source.sendMessage(Component.text("§c金额无效，最小 0.01 元。")); return
        }
        var amountFen = (amountYuan * 100).toInt()

        val methods = setOf("alipay", "wechat", "paypal", "afdian", "mock")
        var method: String? = null
        var promoCode: String? = null
        for (i in 2 until args.size) {
            val arg = args[i]
            if (arg.lowercase() in methods) method = arg else promoCode = arg
        }
        if (method == null) method = resolvePaymentMethod(source) ?: return

        if (promoCode != null) {
            val discounted = promoManager.apply(promoCode, amountFen, null)
            if (discounted != null) {
                val saved = amountFen - discounted
                amountFen = discounted
                source.sendMessage(Component.text("§a优惠码 §f${promoCode.uppercase()} §a生效！减免 §6${String.format("%.2f", saved / 100.0)} §a元"))
            } else {
                source.sendMessage(Component.text("§c优惠码无效或已过期。")); return
            }
        }

        source.sendMessage(Component.text("§a正在创建订单..."))

        CompletableFuture.runAsync {
            try {
                val order = manager.createOrder(
                    playerId = source.uniqueId.toString(),
                    playerName = source.username,
                    amountFen = amountFen,
                    paymentMethod = method
                )
                if (order != null) {
                    recentPending[source.uniqueId.toString()] = order.orderId
                    notifier.onOrderCreated(order)
                    scheduleTimeoutReminder(source, order.orderId, order.createdAt)
                } else {
                    source.sendMessage(Component.text("§c创建订单失败，请稍后重试。"))
                }
            } catch (e: Exception) {
                logger.error("创建订单异常: player={}", source.username, e)
                source.sendMessage(Component.text("§c创建订单出错: ${e.message}"))
            }
        }
    }

    // ==================== 待支付查询 ====================

    private fun handlePending(source: com.velocitypowered.api.command.CommandSource) {
        if (source !is Player) { source.sendMessage(Component.text("§c此命令只能由玩家执行。")); return }

        val orderId = recentPending[source.uniqueId.toString()]
        if (orderId == null) {
            source.sendMessage(Component.text("§7你没有待支付的订单。")); return
        }

        val order = manager.getOrder(orderId)
        if (order == null || order.status != OrderStatus.PENDING) {
            recentPending.remove(source.uniqueId.toString())
            source.sendMessage(Component.text("§7你没有待支付的订单。")); return
        }

        val remaining = (order.createdAt + 300_000 - System.currentTimeMillis()) / 1000
        val paymentUrl = order.qrCodeUrl ?: order.rawUrl

        source.sendMessage(Component.text("§6====== 待支付订单 ======"))
        source.sendMessage(Component.text("§f订单号: ${order.orderId}"))
        source.sendMessage(Component.text("§f金额: §6${String.format("%.2f", order.amount / 100.0)} 元"))
        source.sendMessage(Component.text("§f剩余时间: §e${remaining}秒"))
        if (paymentUrl != null) {
            source.sendMessage(Component.text("§e§l[点击打开支付页面]")
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.openUrl(paymentUrl)))
        }
        source.sendMessage(Component.text("§7/purchase cancel 取消订单"))
    }

    // ==================== 取消订单 ====================

    private fun handleCancel(source: com.velocitypowered.api.command.CommandSource) {
        if (source !is Player) { source.sendMessage(Component.text("§c此命令只能由玩家执行。")); return }

        val orderId = recentPending[source.uniqueId.toString()]
        if (orderId == null) { source.sendMessage(Component.text("§7你没有待支付的订单。")); return }

        val order = manager.getOrder(orderId)
        if (order == null || order.status != OrderStatus.PENDING) {
            recentPending.remove(source.uniqueId.toString())
            source.sendMessage(Component.text("§7你没有待支付的订单。")); return
        }

        // 调支付平台关单
        val provider = manager.getProvider(order.paymentMethod)
        provider?.closeOrder(orderId)

        // 更新本地状态
        db.updateOrderStatusCAS(orderId, OrderStatus.PENDING, OrderStatus.CANCELLED, null)
        recentPending.remove(source.uniqueId.toString())

        source.sendMessage(Component.text("§a订单已取消。"))
    }

    // ==================== 其他命令 ====================

    private fun handleHistory(source: com.velocitypowered.api.command.CommandSource) {
        if (source !is Player) { source.sendMessage(Component.text("§c此命令只能由玩家执行。")); return }
        val orders = db.getOrdersByPlayer(source.uniqueId.toString(), 10)
        if (orders.isEmpty()) { source.sendMessage(Component.text("§7暂无订单记录。")); return }
        source.sendMessage(Component.text("§6====== 最近订单 ======"))
        for (o in orders) {
            val status = msg.statusText(o.status.name)
            source.sendMessage(Component.text("§f${o.orderId.take(8)}... §7| §6${String.format("%.2f", o.amount / 100.0)} 元 §7| $status"))
        }
    }

    private fun handleCheck(source: com.velocitypowered.api.command.CommandSource, args: List<String>) {
        if (args.size < 2) { source.sendMessage(Component.text("§c用法: /purchase check <订单ID>")); return }
        val order = manager.getOrder(args[1])
        if (order == null) { source.sendMessage(Component.text("§c订单不存在。")); return }
        source.sendMessage(Component.text("§6====== 订单详情 ======"))
        source.sendMessage(Component.text("§f订单号: ${order.orderId}"))
        source.sendMessage(Component.text("§f金额: §6${String.format("%.2f", order.amount / 100.0)} 元"))
        source.sendMessage(Component.text("§f状态: ${msg.statusText(order.status.name)}"))
        source.sendMessage(Component.text("§f支付方式: ${order.paymentMethod}"))
    }

    private fun handleTop(source: com.velocitypowered.api.command.CommandSource) {
        val ranking = db.getRevenueRanking(10)
        if (ranking.isEmpty()) { source.sendMessage(Component.text("§7暂无充值记录。")); return }
        source.sendMessage(Component.text("§6====== 充值排行榜 ======"))
        ranking.forEachIndexed { i, r ->
            val medal = when (i) { 0 -> "§6🥇"; 1 -> "§7🥈"; 2 -> "§c🥉"; else -> "§e${i + 1}." }
            source.sendMessage(Component.text("$medal §f${r.playerName} §7- §6${String.format("%.2f", r.totalAmount / 100.0)} 元"))
        }
    }

    // ==================== 内部方法 ====================

    /**
     * 超时倒计时提醒：过期前 60 秒警告一次。
     */
    private fun scheduleTimeoutReminder(player: Player, orderId: String, createdAt: Long) {
        val timeoutMs = 300_000L  // 5 分钟
        val warnBeforeMs = 60_000L  // 提前 60 秒
        val delay = timeoutMs - warnBeforeMs - (System.currentTimeMillis() - createdAt)
        if (delay <= 0) return

        java.util.Timer("timeout-warn-$orderId", true).schedule(object : java.util.TimerTask() {
            override fun run() {
                val order = manager.getOrder(orderId)
                if (order != null && order.status == OrderStatus.PENDING) {
                    player.sendMessage(Component.text("§c§l⚠ 订单将在 60 秒后过期！请尽快完成支付。"))
                    player.sendMessage(Component.text("§7/purchase pending 查看订单 /purchase cancel 取消"))
                }
            }
        }, delay)
    }

    private fun isOnCooldown(playerId: String, productId: String, cooldownSeconds: Int): Boolean {
        if (cooldownSeconds <= 0) return false
        val lastOrder = db.getOrdersByPlayer(playerId, 50).find {
            it.productId == productId && it.status == OrderStatus.PAID
        } ?: return false
        return (System.currentTimeMillis() - (lastOrder.paidAt ?: 0)) / 1000 < cooldownSeconds
    }

    private fun resolvePaymentMethod(source: Player): String? {
        val available = manager.availableProviders()
        return when {
            available.size == 1 -> available.first()
            available.isEmpty() -> { source.sendMessage(Component.text("§c没有可用的支付方式，请联系管理员。")); null }
            else -> {
                source.sendMessage(Component.text("§e请选择支付方式: ${available.joinToString(", ")}"))
                source.sendMessage(Component.text("§7用法: /purchase pay <金额> <alipay|wechat>")); null
            }
        }
    }

    private fun sendHelp(source: com.velocitypowered.api.command.CommandSource) {
        source.sendMessage(Component.text("§6====== WindyPurchase ======"))
        source.sendMessage(Component.text("§e/purchase list §f- 查看商品"))
        source.sendMessage(Component.text("§e/purchase buy <ID> [方式] §f- 购买商品"))
        source.sendMessage(Component.text("§e/purchase pay <金额> [方式] [优惠码] §f- 充值"))
        source.sendMessage(Component.text("§e/purchase pending §f- 查看待支付订单"))
        source.sendMessage(Component.text("§e/purchase cancel §f- 取消待支付订单"))
        source.sendMessage(Component.text("§e/purchase history §f- 订单记录"))
        source.sendMessage(Component.text("§e/purchase check <ID> §f- 查看订单"))
        source.sendMessage(Component.text("§e/purchase top §f- 充值排行"))
    }

    override fun hasPermission(invocation: RawCommand.Invocation): Boolean = true
}
