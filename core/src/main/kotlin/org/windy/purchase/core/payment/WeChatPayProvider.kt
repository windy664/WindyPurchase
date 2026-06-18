package org.windy.purchase.core.payment

import com.wechat.pay.java.core.Config
import com.wechat.pay.java.core.RSAAutoCertificateConfig
import com.wechat.pay.java.core.notification.NotificationParser
import com.wechat.pay.java.core.notification.RequestParam
import com.wechat.pay.java.service.payments.model.Transaction
import com.wechat.pay.java.service.payments.nativepay.NativePayService
import com.wechat.pay.java.service.payments.nativepay.model.PrepayRequest
import com.wechat.pay.java.service.payments.nativepay.model.PrepayResponse
import com.wechat.pay.java.service.payments.nativepay.model.QueryOrderByOutTradeNoRequest
import com.wechat.pay.java.service.payments.nativepay.model.CloseOrderRequest
import org.slf4j.LoggerFactory
import org.windy.purchase.core.model.Order
import org.windy.purchase.core.model.OrderStatus

/**
 * 微信支付 Native 支付（扫码支付）。
 * 预下单 → 返回 code_url → 生成二维码 → 异步回调/轮询确认。
 */
class WeChatPayProvider(
    private val appId: String,
    private val mchId: String,
    private val mchSerialNumber: String,
    private val apiV3Key: String,
    private val privateKeyPath: String,
    private val notifyUrl: String
) : PaymentProvider {

    override val name = "wechat"

    private val log = LoggerFactory.getLogger(WeChatPayProvider::class.java)

    private val config: Config by lazy {
        RSAAutoCertificateConfig.Builder()
            .merchantId(mchId)
            .privateKeyFromPath(privateKeyPath)
            .merchantSerialNumber(mchSerialNumber)
            .apiV3Key(apiV3Key)
            .build()
    }

    private val payService: NativePayService by lazy {
        NativePayService.Builder().config(config).build()
    }

    override fun createOrder(order: Order): PaymentResult? {
        return try {
            val request = PrepayRequest()
            request.appid = appId
            request.mchid = mchId
            request.description = "WindyPurchase-${order.orderId.take(8)}"
            request.outTradeNo = order.orderId
            request.notifyUrl = notifyUrl

            val amount = com.wechat.pay.java.service.payments.nativepay.model.Amount()
            amount.total = order.amount
            amount.currency = "CNY"
            request.amount = amount

            val response: PrepayResponse = payService.prepay(request)
            log.info("微信下单成功: {} -> code_url {}", order.orderId, response.codeUrl)
            PaymentResult(qrCodeUrl = response.codeUrl)
        } catch (e: Exception) {
            log.error("微信下单异常: {}", order.orderId, e)
            null
        }
    }

    override fun queryOrder(orderId: String): OrderStatus {
        return try {
            val request = QueryOrderByOutTradeNoRequest()
            request.outTradeNo = orderId
            request.mchid = mchId

            val transaction: Transaction = payService.queryOrderByOutTradeNo(request)
            when (transaction.tradeState) {
                Transaction.TradeStateEnum.SUCCESS -> OrderStatus.PAID
                Transaction.TradeStateEnum.CLOSED -> OrderStatus.CANCELLED
                else -> OrderStatus.PENDING
            }
        } catch (e: Exception) {
            log.error("微信查单异常: {}", orderId, e)
            OrderStatus.PENDING
        }
    }

    override fun closeOrder(orderId: String): Boolean {
        return try {
            val request = CloseOrderRequest()
            request.outTradeNo = orderId
            request.mchid = mchId
            payService.closeOrder(request)
            true
        } catch (e: Exception) {
            log.error("微信关单异常: {}", orderId, e)
            false
        }
    }

    /**
     * 解析微信支付异步回调通知。
     * @return 解析成功返回 Transaction，否则 null
     */
    /**
     * 解析微信支付回调通知。
     * 简化实现：直接解析 JSON body，不走 SDK 的签名验证（由上层验签）。
     */
    fun parseNotify(headers: Map<String, String>, body: String): SimpleNotifyResult? {
        return try {
            val json = com.fasterxml.jackson.databind.ObjectMapper().readTree(body)
            val resource = json["resource"] ?: return null
            SimpleNotifyResult(
                outTradeNo = resource["out_trade_no"]?.asText() ?: return null,
                tradeState = resource["trade_state"]?.asText() ?: "",
                totalAmount = resource["amount"]?.get("total")?.asInt() ?: 0
            )
        } catch (e: Exception) {
            log.error("微信回调解析失败", e)
            null
        }
    }
}

/** 简化的回调通知结果（不依赖 SDK 的 Transaction 类） */
data class SimpleNotifyResult(
    val outTradeNo: String,
    val tradeState: String,
    val totalAmount: Int
)
