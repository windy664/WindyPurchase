package org.windy.purchase.core.model

/**
 * 支付订单。
 * amount 单位为「分」（整数），避免浮点精度问题。
 */
data class Order(
    val orderId: String,
    val playerId: String,
    val playerName: String,
    /** 商品 ID；null 表示自由金额充值 */
    val productId: String?,
    /** 金额，单位：分 */
    val amount: Int,
    val currency: String = "CNY",
    val paymentMethod: String,   // alipay / wechat
    var status: OrderStatus = OrderStatus.PENDING,
    /** 支付平台返回的二维码 URL（支付宝/微信） */
    var qrCodeUrl: String? = null,
    /** 支付平台返回的原始链接（PayPal 结账页面等，无二维码场景） */
    var rawUrl: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    var paidAt: Long? = null,
    /** 支付成功后执行的命令列表 */
    val commands: List<String> = emptyList()
)
