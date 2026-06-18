package org.windy.purchase.core.payment

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.windy.purchase.core.model.Order
import org.windy.purchase.core.model.OrderStatus
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64

/**
 * PayPal REST API 支付（Orders V2）。
 *
 * 流程与支付宝/微信不同：
 * 1. 创建订单 → 返回 approve URL（PayPal 结账页面）
 * 2. 玩家打开 URL → 在 PayPal 页面完成支付
 * 3. PayPal 重定向回 notifyUrl（GET 带 token）
 * 4. 后端捕获订单（capture）→ 轮询确认
 *
 * 没有二维码，给玩家的是一个可点击链接。
 */
class PayPalProvider(
    private val clientId: String,
    private val clientSecret: String,
    private val currency: String = "USD",
    private val sandbox: Boolean = true,
    private val returnUrl: String = "",
    private val cancelUrl: String = ""
) : PaymentProvider {

    override val name = "paypal"

    private val log = LoggerFactory.getLogger(PayPalProvider::class.java)
    private val mapper = ObjectMapper()
    private val httpClient = HttpClient.newHttpClient()

    private val baseUrl: String
        get() = if (sandbox) "https://api-m.sandbox.paypal.com" else "https://api-m.paypal.com"

    // ==================== Token 缓存 ====================

    /** paypalOrderId → localOrderId 映射，capture 后反查用 */
    private val orderMapping = java.util.concurrent.ConcurrentHashMap<String, String>()

    @Volatile
    private var cachedToken: String? = null
    @Volatile
    private var tokenExpiry: Long = 0

    private fun getToken(): String {
        val now = System.currentTimeMillis()
        cachedToken?.let { if (now < tokenExpiry) return it }

        val auth = Base64.getEncoder().encodeToString("$clientId:$clientSecret".toByteArray())
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/v1/oauth2/token"))
            .header("Authorization", "Basic $auth")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials"))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            throw RuntimeException("PayPal token request failed: ${response.statusCode()} ${response.body()}")
        }

        val json = mapper.readTree(response.body())
        val token = json["access_token"].asText()
        val expiresIn = json["expires_in"].asInt() // 秒
        cachedToken = token
        tokenExpiry = now + (expiresIn - 60) * 1000L // 提前 60 秒刷新
        return token
    }

    // ==================== PaymentProvider ====================

    override fun createOrder(order: Order): PaymentResult? {
        return try {
            val token = getToken()
            val amountStr = String.format("%.2f", order.amount / 100.0)

            val body = mapper.createObjectNode().apply {
                put("intent", "CAPTURE")
                putArray("purchase_units").addObject().apply {
                    put("reference_id", order.orderId)
                    putObject("amount").apply {
                        put("currency_code", currency)
                        put("value", amountStr)
                    }
                    put("description", "WindyPurchase-${order.orderId.take(8)}")
                }
                putObject("application_context").apply {
                    put("return_url", returnUrl.ifBlank { "https://example.com/success" })
                    put("cancel_url", cancelUrl.ifBlank { "https://example.com/cancel" })
                    put("user_action", "PAY_NOW")
                }
            }

            val request = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/v2/checkout/orders"))
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                log.error("PayPal create order failed: {} {}", response.statusCode(), response.body())
                return null
            }

            val json = mapper.readTree(response.body())
            val paypalOrderId = json["id"].asText()

            // 从 links 中找 approve URL
            val approveUrl = json["links"]?.find { it["rel"].asText() == "approve" }?.get("href")?.asText()

            // 存储映射：paypalOrderId → 我们的 orderId
            orderMapping[paypalOrderId] = order.orderId

            log.info("PayPal order created: {} -> approve URL: {}", paypalOrderId, approveUrl)

            // PayPal 返回的不是二维码，是一个结账页面链接
            PaymentResult(
                qrCodeUrl = null,  // PayPal 没有二维码
                rawUrl = approveUrl
            )
        } catch (e: Exception) {
            log.error("PayPal create order exception: {}", order.orderId, e)
            null
        }
    }

    override fun queryOrder(orderId: String): OrderStatus {
        return try {
            // PayPal 用的是自己的 order ID（不是我们的 orderId），需要额外存储映射
            // 这里用 reference_id 搜索不太方便，实际使用中应在 createOrder 时存储 paypalOrderId
            // 简化实现：返回 PENDING，靠 capture 回调确认
            OrderStatus.PENDING
        } catch (e: Exception) {
            log.error("PayPal query order exception: {}", orderId, e)
            OrderStatus.PENDING
        }
    }

    /**
     * 捕获（确认）PayPal 订单。在玩家完成支付后调用。
     * @param paypalOrderId PayPal 返回的订单 ID（不是我们的 orderId）
     */
    fun captureOrder(paypalOrderId: String): Pair<Boolean, String?> {
        return try {
            val token = getToken()
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/v2/checkout/orders/$paypalOrderId/capture"))
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(""))
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() in 200..299) {
                val json = mapper.readTree(response.body())
                val status = json["status"]?.asText()
                val units = json["purchase_units"]
                val captureId = if (units != null && units.isArray && units.size() > 0)
                    units[0]?.get("payments")?.get("captures")?.let { if (it.isArray && it.size() > 0) it[0]?.get("id")?.asText() else null }
                else null

                log.info("PayPal capture: {} status={} captureId={}", paypalOrderId, status, captureId)
                (status == "COMPLETED") to captureId
            } else {
                log.error("PayPal capture failed: {} {}", response.statusCode(), response.body())
                false to null
            }
        } catch (e: Exception) {
            log.error("PayPal capture exception: {}", paypalOrderId, e)
            false to null
        }
    }

    /**
     * 通过 PayPal 订单 ID 获取本地订单 ID。
     */
    fun getLocalOrderId(paypalOrderId: String): String? = orderMapping[paypalOrderId]

    override fun closeOrder(orderId: String): Boolean {
        // PayPal 订单超时自动关闭，无需手动操作
        return true
    }
}
