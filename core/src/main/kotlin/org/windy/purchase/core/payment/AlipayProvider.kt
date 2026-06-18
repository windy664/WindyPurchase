package org.windy.purchase.core.payment

import com.alipay.api.AlipayApiException
import com.alipay.api.AlipayClient
import com.alipay.api.DefaultAlipayClient
import com.alipay.api.domain.AlipayTradePrecreateModel
import com.alipay.api.domain.AlipayTradeQueryModel
import com.alipay.api.domain.AlipayTradeCloseModel
import com.alipay.api.internal.util.AlipaySignature
import com.alipay.api.request.AlipayTradePrecreateRequest
import com.alipay.api.request.AlipayTradeQueryRequest
import com.alipay.api.request.AlipayTradeCloseRequest
import org.slf4j.LoggerFactory
import org.windy.purchase.core.model.Order
import org.windy.purchase.core.model.OrderStatus

/**
 * 支付宝当面付（预创建订单模式）。
 * 生成二维码 → 用户扫码 → 异步回调/轮询确认。
 */
class AlipayProvider(
    private val appId: String,
    private val privateKey: String,
    private val alipayPublicKey: String,
    private val notifyUrl: String,
    private val gateway: String = "https://openapi.alipay.com/gateway.do",
    private val sandbox: Boolean = false
) : PaymentProvider {

    override val name = "alipay"

    private val log = LoggerFactory.getLogger(AlipayProvider::class.java)

    private val client: AlipayClient by lazy {
        val gw = if (sandbox) "https://openapi-sandbox.dl.alipaydev.com/gateway.do" else gateway
        DefaultAlipayClient(gw, appId, privateKey, "json", "UTF-8", alipayPublicKey, "RSA2")
    }

    override fun createOrder(order: Order): PaymentResult? {
        return try {
            val request = AlipayTradePrecreateRequest()
            val model = AlipayTradePrecreateModel()
            model.outTradeNo = order.orderId
            model.totalAmount = String.format("%.2f", order.amount / 100.0)
            model.subject = "WindyPurchase-${order.orderId.take(8)}"
            model.productCode = "FACE_TO_FACE_PAYMENT"
            request.bizModel = model
            request.notifyUrl = notifyUrl

            val response = client.execute(request)
            if (response.isSuccess) {
                log.info("支付宝下单成功: {} -> 二维码 {}", order.orderId, response.qrCode)
                PaymentResult(qrCodeUrl = response.qrCode)
            } else {
                log.error("支付宝下单失败: {} - {} {}", order.orderId, response.subCode, response.subMsg)
                null
            }
        } catch (e: AlipayApiException) {
            log.error("支付宝下单异常: {}", order.orderId, e)
            null
        }
    }

    override fun queryOrder(orderId: String): OrderStatus {
        return try {
            val request = AlipayTradeQueryRequest()
            val model = AlipayTradeQueryModel()
            model.outTradeNo = orderId
            request.bizModel = model

            val response = client.execute(request)
            if (response.isSuccess) {
                when (response.tradeStatus) {
                    "TRADE_SUCCESS", "TRADE_FINISHED" -> OrderStatus.PAID
                    "TRADE_CLOSED" -> OrderStatus.CANCELLED
                    else -> OrderStatus.PENDING
                }
            } else {
                // TRADE_NOT_EXIST（未支付）或其他错误都视为 PENDING
                OrderStatus.PENDING
            }
        } catch (e: AlipayApiException) {
            log.error("支付宝查单异常: {}", orderId, e)
            OrderStatus.PENDING
        }
    }

    override fun closeOrder(orderId: String): Boolean {
        return try {
            val request = AlipayTradeCloseRequest()
            val model = AlipayTradeCloseModel()
            model.outTradeNo = orderId
            request.bizModel = model

            val response = client.execute(request)
            response.isSuccess || response.subCode == "ACQ.TRADE_NOT_EXIST"
        } catch (e: AlipayApiException) {
            log.error("支付宝关单异常: {}", orderId, e)
            false
        }
    }

    /**
     * 验证支付宝异步回调签名。
     * @return 验签成功返回 true
     */
    fun verifyNotify(params: Map<String, String>): Boolean {
        return try {
            AlipaySignature.rsaCheckV1(params, alipayPublicKey, "UTF-8", "RSA2")
        } catch (e: AlipayApiException) {
            log.error("支付宝回调验签失败", e)
            false
        }
    }
}
