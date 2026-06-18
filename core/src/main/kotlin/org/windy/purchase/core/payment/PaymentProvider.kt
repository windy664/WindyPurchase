package org.windy.purchase.core.payment

import org.windy.purchase.core.model.Order
import org.windy.purchase.core.model.OrderStatus

data class PaymentResult(
    /** 二维码内容（URL 或二维码图片链接） */
    val qrCodeUrl: String?,
    /** 备用：可直接打开的支付链接 */
    val rawUrl: String? = null
)

/**
 * 支付方式抽象。每个实现对应一种支付渠道。
 * 所有方法均为同步阻塞调用（支付 SDK 本身是同步的）。
 */
interface PaymentProvider {
    val name: String

    /**
     * 创建支付订单，返回二维码/链接。
     * @return null 表示创建失败
     */
    fun createOrder(order: Order): PaymentResult?

    /**
     * 查询订单状态（轮询用）。
     */
    fun queryOrder(orderId: String): OrderStatus

    /**
     * 关闭/取消订单。
     */
    fun closeOrder(orderId: String): Boolean
}
