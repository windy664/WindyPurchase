package org.windy.purchase.core.payment

import com.wechat.pay.java.core.Config
import com.wechat.pay.java.core.RSAAutoCertificateConfig
import com.wechat.pay.java.core.notification.NotificationConfig
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
     * 验签并解密微信支付异步回调通知。
     *
     * 真实 APIv3 回调的 resource 是 AES-256-GCM 密文，且带 Wechatpay-* 验签头。
     * 这里走 SDK 的 NotificationParser：内部完成 RSA 验签 + APIv3 密钥解密，
     * 验签失败会抛异常 → 返回 null → 上层拒单（fails closed）。
     *
     * @return 验签且解密成功返回结果，否则 null（视为非法回调拒绝）
     */
    fun parseNotify(headers: Map<String, String>, body: String): SimpleNotifyResult? {
        return try {
            fun header(name: String): String? =
                headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

            val serial = header("Wechatpay-Serial")
            val nonce = header("Wechatpay-Nonce")
            val signature = header("Wechatpay-Signature")
            val timestamp = header("Wechatpay-Timestamp")
            if (serial == null || nonce == null || signature == null || timestamp == null) {
                log.warn("微信回调缺少验签头，拒绝: serial={} nonce存在={} sig存在={} ts={}", serial, nonce != null, signature != null, timestamp)
                return null
            }

            val requestParam = RequestParam.Builder()
                .serialNumber(serial)
                .nonce(nonce)
                .signature(signature)
                .timestamp(timestamp)
                .body(body)
                .build()

            val parser = NotificationParser(config as NotificationConfig)
            // parse 内部完成验签 + 解密；验签失败直接抛异常
            val tx: Transaction = parser.parse(requestParam, Transaction::class.java)

            SimpleNotifyResult(
                outTradeNo = tx.outTradeNo ?: return null,
                tradeState = tx.tradeState?.name ?: "",
                totalAmount = tx.amount?.total ?: 0
            )
        } catch (e: Exception) {
            log.error("微信回调验签/解析失败（已拒单）", e)
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
