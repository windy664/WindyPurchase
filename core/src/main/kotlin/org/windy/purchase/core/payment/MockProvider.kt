package org.windy.purchase.core.payment

import org.slf4j.LoggerFactory
import org.windy.purchase.core.model.Order
import org.windy.purchase.core.model.OrderStatus
import java.util.Timer
import java.util.TimerTask

/**
 * Mock 支付 — 测试用，下单后 N 秒自动确认支付。
 */
class MockProvider(
    private val autoConfirmDelayMs: Long = 5000L
) : PaymentProvider {

    override val name = "mock"

    private val log = LoggerFactory.getLogger(MockProvider::class.java)
    private val pendingOrders = java.util.concurrent.ConcurrentHashMap<String, Order>()
    private val timer = Timer("mock-confirm", true)  // 共享一个 Timer

    var onConfirm: ((String) -> Unit)? = null

    override fun createOrder(order: Order): PaymentResult? {
        pendingOrders[order.orderId] = order
        log.info("[MOCK] 订单创建: {} 金额={}分 延迟={}ms", order.orderId, order.amount, autoConfirmDelayMs)

        if (autoConfirmDelayMs > 0) {
            timer.schedule(object : TimerTask() {
                override fun run() {
                    confirmOrder(order.orderId)
                }
            }, autoConfirmDelayMs)
        }

        return PaymentResult(
            qrCodeUrl = null,
            rawUrl = "mock://payment/${order.orderId}"
        )
    }

    override fun queryOrder(orderId: String): OrderStatus {
        return if (pendingOrders.containsKey(orderId)) OrderStatus.PENDING else OrderStatus.PAID
    }

    override fun closeOrder(orderId: String): Boolean {
        pendingOrders.remove(orderId)
        return true
    }

    fun confirmOrder(orderId: String): Boolean {
        val order = pendingOrders.remove(orderId) ?: return false
        log.info("[MOCK] 订单确认: {} 金额={}分", orderId, order.amount)
        onConfirm?.invoke(orderId)
        return true
    }

    fun getPendingOrders(): List<String> = pendingOrders.keys.toList()
}
