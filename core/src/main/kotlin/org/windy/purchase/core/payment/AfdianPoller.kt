package org.windy.purchase.core.payment

import org.slf4j.LoggerFactory
import org.windy.purchase.core.config.AfdianPlanConfig
import org.windy.purchase.core.db.Database
import org.windy.purchase.core.model.Order
import org.windy.purchase.core.model.OrderStatus
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.ConcurrentHashMap

/**
 * 爱发电轮询器。
 *
 * 优化：
 * - processedOrders 用 ConcurrentHashMap.KeySet 线程安全
 * - handlePaid 用 CAS 防重复处理
 * - poll 只拉最近 3 页，不遍历全部历史
 */
class AfdianPoller(
    private val provider: AfdianProvider,
    private val db: Database,
    private val pollIntervalMs: Long = 15_000L,
    private val plans: List<AfdianPlanConfig> = emptyList()
) {
    private val log = LoggerFactory.getLogger(AfdianPoller::class.java)
    private val timer = Timer("afdian-poll", true)
    private val processedOrders: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** 防内存溢出：上限 5000 条，超出时清空（靠 CAS 防重复，不会丢数据） */
    private val maxProcessedSize = 5000

    var onPaymentSuccess: ((Order) -> Unit)? = null
    var createVirtualOrder: ((String, List<String>) -> Order?)? = null

    /** 每次轮询最多拉几页（避免全量遍历） */
    private val maxPagesPerPoll = 3

    fun start() {
        val task = object : TimerTask() {
            override fun run() {
                try { poll() } catch (e: Exception) { log.error("Afdian poll error", e) }
            }
        }
        timer.schedule(task, pollIntervalMs, pollIntervalMs)
        log.info("Afdian poller started, interval={}ms, plans={}", pollIntervalMs, plans.size)
    }

    fun stop() = timer.cancel()

    private fun poll() {
        // 防内存溢出：超出上限时清空（靠 CAS 防重复，不会丢数据）
        if (processedOrders.size > maxProcessedSize) {
            log.info("Afdian processedOrders reached {}, clearing (CAS protects against duplicates)", processedOrders.size)
            processedOrders.clear()
        }
        var page = 1
        var consecutiveEmpty = 0
        while (page <= maxPagesPerPoll) {
            val result = provider.queryOrderList(page) ?: break
            var foundNew = false
            for (afdianOrder in result.list) {
                if (afdianOrder.outTradeNo in processedOrders) continue
                if (afdianOrder.status != "TRADE_SUCCESS" && afdianOrder.status != "2") continue

                val matched = matchOrder(afdianOrder)
                if (matched != null) {
                    handlePaid(afdianOrder, matched)
                    foundNew = true
                } else {
                    // 已完成但无法匹配，标记已处理避免重复扫描
                    processedOrders.add(afdianOrder.outTradeNo)
                }
            }
            if (!foundNew) {
                consecutiveEmpty++
                if (consecutiveEmpty >= 2) break  // 连续两页无新订单，停止
            } else {
                consecutiveEmpty = 0
            }
            if (page >= result.totalPages) break
            page++
        }
    }

    private fun matchOrder(afdianOrder: AfdianOrder): Order? {
        // 方式 1：custom_order_id 匹配
        if (afdianOrder.customOrderId.isNotBlank()) {
            val local = db.getOrder(afdianOrder.customOrderId)
            if (local != null && local.status == OrderStatus.PENDING && local.paymentMethod == "afdian") {
                return local
            }
        }

        // 方式 2：plans 模式
        val playerName = afdianOrder.remark.trim()
        if (playerName.isBlank()) return null

        val matchedPlan = matchPlan(afdianOrder.title) ?: return null
        log.info("Afdian plan matched: title='{}' -> plan='{}' player={}", afdianOrder.title, matchedPlan.title, playerName)
        return createVirtualOrder?.invoke(playerName, matchedPlan.commands.map { it.replace("%player%", playerName) })
    }

    /**
     * 按方案标题匹配。精确优先，再模糊。
     */
    private fun matchPlan(title: String): AfdianPlanConfig? {
        // 精确匹配优先
        plans.firstOrNull { it.title.equals(title, ignoreCase = true) }?.let { return it }
        // 模糊匹配（双向包含）
        return plans.firstOrNull { title.contains(it.title, ignoreCase = true) || it.title.contains(title, ignoreCase = true) }
    }

    private fun handlePaid(afdianOrder: AfdianOrder, localOrder: Order) {
        // CAS 防止轮询和 webhook 双重处理
        val ok = db.updateOrderStatusCAS(localOrder.orderId, OrderStatus.PENDING, OrderStatus.PAID, System.currentTimeMillis())
        if (!ok) {
            log.debug("Afdian order {} already processed, skipping", localOrder.orderId)
            processedOrders.add(afdianOrder.outTradeNo)
            return
        }
        localOrder.status = OrderStatus.PAID
        localOrder.paidAt = System.currentTimeMillis()
        processedOrders.add(afdianOrder.outTradeNo)
        log.info("Afdian order paid: out_trade_no={} local={} player={} amount={}", afdianOrder.outTradeNo, localOrder.orderId, localOrder.playerName, afdianOrder.totalAmount)
        onPaymentSuccess?.invoke(localOrder)
    }
}
