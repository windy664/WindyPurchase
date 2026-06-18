package org.windy.purchase.core.payment

import org.slf4j.LoggerFactory
import org.windy.purchase.core.model.Order
import org.windy.purchase.core.model.OrderStatus
import org.windy.purchase.core.db.Database
import org.windy.purchase.core.db.PlayerStatsCache
import java.util.Timer
import java.util.TimerTask
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 支付管理器 — 维护 provider 注册表 + 订单生命周期 + 轮询确认。
 */
class PaymentManager(
    private val db: Database,
    private val orderTimeoutMs: Long = 300_000L,
    private val pollIntervalMs: Long = 3000L,
    private val statsCache: PlayerStatsCache? = null
) {
    private val log = LoggerFactory.getLogger(PaymentManager::class.java)
    private val providers = ConcurrentHashMap<String, PaymentProvider>()
    private val activeOrders = ConcurrentHashMap<String, Order>()
    private val playerLocks = ConcurrentHashMap<String, Any>()  // 玩家级锁，防并发下单
    private val timer = Timer("windypurchase-poll", true)

    /** 支付成功回调：(order) -> Unit */
    var onPaymentSuccess: ((Order) -> Unit)? = null

    fun registerProvider(provider: PaymentProvider) {
        providers[provider.name] = provider
        log.info("注册支付方式: {}", provider.name)
    }

    fun getProvider(name: String): PaymentProvider? = providers[name]

    fun availableProviders(): List<String> = providers.keys.toList()

    /**
     * 创建订单并发起支付。
     * 玩家级锁防止并发重复下单。
     */
    fun createOrder(
        playerId: String,
        playerName: String,
        amountFen: Int,
        paymentMethod: String,
        productId: String? = null,
        commands: List<String> = emptyList()
    ): Order? {
        // 玩家级锁：同一玩家串行创建订单
        val lock = playerLocks.computeIfAbsent(playerId) { Any() }
        synchronized(lock) {
            try {
                return createOrderInternal(playerId, playerName, amountFen, paymentMethod, productId, commands)
            } finally {
                // 清理无活跃订单的锁，防内存泄漏
                if (activeOrders.none { it.value.playerId == playerId }) {
                    playerLocks.remove(playerId)
                }
            }
        }
    }

    private fun createOrderInternal(
        playerId: String,
        playerName: String,
        amountFen: Int,
        paymentMethod: String,
        productId: String?,
        commands: List<String>
    ): Order? {
        // 防重复：同一玩家不能同时有两个 PENDING 订单
        val existing = activeOrders.values.find {
            it.playerId == playerId && it.status == OrderStatus.PENDING
        }
        if (existing != null) {
            log.warn("玩家 {} 已有待支付订单 {}", playerName, existing.orderId)
            return existing
        }

        val provider = providers[paymentMethod] ?: run {
            log.error("未知支付方式: {}", paymentMethod)
            return null
        }

        val orderId = UUID.randomUUID().toString().replace("-", "")
        val order = Order(
            orderId = orderId,
            playerId = playerId,
            playerName = playerName,
            productId = productId,
            amount = amountFen,
            paymentMethod = paymentMethod,
            commands = commands
        )

        // 先存库再调支付平台（防止支付成功但入库失败导致丢单）
        db.insertOrder(order)
        activeOrders[orderId] = order

        try {
            val result = provider.createOrder(order)
            if (result == null) {
                // 支付平台下单失败，回滚本地订单
                db.updateOrderStatusCAS(orderId, OrderStatus.PENDING, OrderStatus.CANCELLED, null)
                activeOrders.remove(orderId)
                log.error("支付平台下单失败，已回滚: {}", orderId)
                return null
            }
            order.qrCodeUrl = result.qrCodeUrl
            order.rawUrl = result.rawUrl
        } catch (e: Exception) {
            // 支付平台异常，回滚本地订单
            db.updateOrderStatusCAS(orderId, OrderStatus.PENDING, OrderStatus.CANCELLED, null)
            activeOrders.remove(orderId)
            log.error("支付平台下单异常，已回滚: {}", orderId, e)
            return null
        }

        startPolling(order)
        log.info("订单创建成功: {} 玩家={} 金额={}分 方式={}", orderId, playerName, amountFen, paymentMethod)
        return order
    }

    private fun startPolling(order: Order) {
        val task = object : TimerTask() {
            override fun run() {
                try {
                    val current = activeOrders[order.orderId]
                    if (current == null || current.status != OrderStatus.PENDING) {
                        cancel()
                        return
                    }

                    // 超时检查
                    if (System.currentTimeMillis() - current.createdAt > orderTimeoutMs) {
                        handleTimeout(current)
                        cancel()
                        return
                    }

                    // 轮询查单
                    val provider = providers[current.paymentMethod]
                    if (provider == null) {
                        log.warn("订单 {} 的支付方式 {} 已不可用，取消轮询", current.orderId, current.paymentMethod)
                        cancel()
                        return
                    }
                    val status = provider.queryOrder(current.orderId)
                    if (status == OrderStatus.PAID) {
                        handlePaid(current)
                        cancel()
                    } else if (status == OrderStatus.CANCELLED) {
                        handleCancelled(current)
                        cancel()
                    }
                } catch (e: Exception) {
                    log.error("轮询订单 {} 异常", order.orderId, e)
                    // 异常不 cancel，下次重试（可能是临时网络问题）
                }
            }
        }
        timer.schedule(task, pollIntervalMs, pollIntervalMs)
    }

    /**
     * 支付回调通知（由 HTTP 端点调用）。
     * 验签通过后调用此方法更新订单状态。
     */
    fun handleNotify(orderId: String, paidAmount: Int?) {
        val order = activeOrders[orderId] ?: db.getOrder(orderId) ?: run {
            log.warn("回调收到未知订单: {}", orderId)
            return
        }
        if (order.status != OrderStatus.PENDING) return

        // 金额校验：支付宝/微信严格校验，爱发电/PayPal 允许差异
        if (paidAmount != null && order.amount > 0) {
            val diff = kotlin.math.abs(paidAmount - order.amount)
            val allowDiff = when (order.paymentMethod) {
                "afdian" -> order.amount / 10  // 爱发电允许 10% 误差（含手续费）
                "paypal" -> order.amount / 10  // PayPal 允许汇率差异
                else -> 1                      // 支付宝/微信精确到分
            }
            if (diff > allowDiff) {
                log.warn("订单 {} 金额不匹配: 期望={} 实付={} 允许误差={}", orderId, order.amount, paidAmount, allowDiff)
                return  // 金额不匹配，拒绝
            }
        }

        handlePaid(order)
    }

    /**
     * 乐观锁标记支付成功。
     */
    private fun handlePaid(order: Order) {
        val paidAt = System.currentTimeMillis()
        val ok = db.updateOrderStatusCAS(order.orderId, OrderStatus.PENDING, OrderStatus.PAID, paidAt)
        if (!ok) {
            log.debug("订单 {} 已被其他线程处理，跳过", order.orderId)
            return
        }
        order.status = OrderStatus.PAID
        order.paidAt = paidAt
        activeOrders.remove(order.orderId)
        statsCache?.invalidate(order.playerId)
        log.info("订单支付成功: {} 玩家={} 金额={}分", order.orderId, order.playerName, order.amount)
        onPaymentSuccess?.invoke(order)
    }

    private fun handleTimeout(order: Order) {
        val ok = db.updateOrderStatusCAS(order.orderId, OrderStatus.PENDING, OrderStatus.EXPIRED, null)
        if (!ok) return
        order.status = OrderStatus.EXPIRED
        activeOrders.remove(order.orderId)
        runCatching { providers[order.paymentMethod]?.closeOrder(order.orderId) }
        log.info("订单已过期: {}", order.orderId)
    }

    private fun handleCancelled(order: Order) {
        val ok = db.updateOrderStatusCAS(order.orderId, OrderStatus.PENDING, OrderStatus.CANCELLED, null)
        if (!ok) return
        order.status = OrderStatus.CANCELLED
        activeOrders.remove(order.orderId)
        log.info("订单已取消: {}", order.orderId)
    }

    fun getOrder(orderId: String): Order? = activeOrders[orderId] ?: db.getOrder(orderId)

    fun shutdown() {
        timer.cancel()
        activeOrders.values.filter { it.status == OrderStatus.PENDING }.forEach {
            runCatching { providers[it.paymentMethod]?.closeOrder(it.orderId) }
        }
        activeOrders.clear()
    }
}
