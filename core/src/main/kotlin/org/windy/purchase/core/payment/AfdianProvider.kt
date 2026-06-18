package org.windy.purchase.core.payment

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.windy.purchase.core.model.Order
import org.windy.purchase.core.model.OrderStatus
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest

/**
 * 爱发电 (Afdian) 支付提供者。
 *
 * 双模式：
 * 1. 轮询模式 — 定时调 /open/query-order 查未处理订单（默认，不需要公网）
 * 2. Webhook 模式 — 爱发电主动推送订单到 webhook URL（实时，需公网端点或 CF Worker 中转）
 *
 * 签名算法：MD5(token + "params" + JSON(params_sorted_by_key))
 * 爱发电 API: https://afdian.com/api/open/...
 */
class AfdianProvider(
    private val userId: String,
    private val token: String,
    private val baseUrl: String = "https://afdian.com/api"
) : PaymentProvider {

    override val name = "afdian"

    private val log = LoggerFactory.getLogger(AfdianProvider::class.java)
    private val mapper = ObjectMapper()
    private val httpClient = HttpClient.newHttpClient()

    // ==================== PaymentProvider ====================

    /**
     * 爱发电不支持直接创建订单（用户在爱发电网站下单）。
     * 这里返回 null，由轮询/webhook 模式处理。
     */
    override fun createOrder(order: Order): PaymentResult? {
        // 爱发电不支持 API 创建订单
        // 用户在爱发电页面下单，留言填写玩家名
        // 返回一个跳转到爱发电赞助页面的链接
        val url = "https://afdian.net/order/create?user_id=$userId&custom_order_id=${order.orderId}"
        log.info("Afdian order created (redirect): {} -> {}", order.orderId, url)
        return PaymentResult(qrCodeUrl = null, rawUrl = url)
    }

    override fun queryOrder(orderId: String): OrderStatus {
        // 单个订单查询通过 queryOrderList 实现
        return OrderStatus.PENDING
    }

    override fun closeOrder(orderId: String): Boolean {
        // 爱发电不支持关闭订单
        return true
    }

    // ==================== 爱发电 API ====================

    /**
     * 查询订单列表（轮询模式核心方法）。
     * @param page 页码，从 1 开始
     * @return 订单列表 JSON
     */
    fun queryOrderList(page: Int = 1): AfdianOrderPage? {
        return try {
            val params = mapOf("page" to page)
            val body = signedRequest("/open/query-order", params)
            val json = mapper.readTree(body)

            if (json["ec"].asInt() != 200) {
                log.error("Afdian query-order failed: {}", json["em"].asText())
                return null
            }

            val data = json["data"]
            val total = data["total"].asInt()
            val totalPages = data["total_page"].asInt()
            val list = mutableListOf<AfdianOrder>()

            data["list"]?.forEach { item ->
                list.add(parseOrder(item))
            }

            AfdianOrderPage(total, totalPages, list)
        } catch (e: Exception) {
            log.error("Afdian query-order exception", e)
            null
        }
    }

    /**
     * 查询赞助记录（另一种查询方式）。
     */
    fun querySponsor(page: Int = 1): AfdianOrderPage? {
        return try {
            val params = mapOf("page" to page)
            val body = signedRequest("/open/query-sponsor", params)
            val json = mapper.readTree(body)

            if (json["ec"].asInt() != 200) {
                log.error("Afdian query-sponsor failed: {}", json["em"].asText())
                return null
            }

            val data = json["data"]
            val total = data["total"].asInt()
            val totalPages = data["total_page"].asInt()
            val list = mutableListOf<AfdianOrder>()

            data["list"]?.forEach { item ->
                list.add(parseOrder(item))
            }

            AfdianOrderPage(total, totalPages, list)
        } catch (e: Exception) {
            log.error("Afdian query-sponsor exception", e)
            null
        }
    }

    // ==================== Webhook ====================

    /**
     * 验证 Webhook 请求是否来自爱发电。
     * 爱发电 webhook 通过 body 中的 sign 字段验证。
     *
     * @param body 请求体 JSON
     * @return 验证成功返回 AfdianOrder，否则 null
     */
    fun verifyWebhook(body: String): AfdianOrder? {
        return try {
            val json = mapper.readTree(body)
            val sign = json["sign"]?.asText() ?: return null

            // 签名验证：MD5(token + "params" + JSON(params))
            val dataNode = json["data"] ?: return null
            val paramsJson = mapper.writeValueAsString(dataNode)
            val expectedSign = md5(token + "params" + paramsJson)

            if (sign != expectedSign) {
                log.warn("Afdian webhook sign mismatch: expected={} actual={}", expectedSign, sign)
                return null
            }

            val order = parseOrder(dataNode)
            log.info("Afdian webhook verified: out_trade_no={} amount={}", order.outTradeNo, order.totalAmount)
            order
        } catch (e: Exception) {
            log.error("Afdian webhook verify exception", e)
            null
        }
    }

    // ==================== 内部方法 ====================

    /**
     * 签名请求。
     * 签名算法：MD5(token + "params" + JSON(params_sorted_by_key))
     */
    private fun signedRequest(endpoint: String, params: Map<String, Any>): String {
        // 参数排序后 JSON 序列化
        val sortedParams = params.toSortedMap()
        val paramsJson = mapper.writeValueAsString(sortedParams)

        // 签名
        val sign = md5(token + "params" + paramsJson)

        // 构造请求 body
        val requestBody = mapper.createObjectNode().apply {
            put("user_id", userId)
            put("params", paramsJson)
            put("sign", sign)
        }

        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl$endpoint"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        return response.body()
    }

    private fun parseOrder(node: com.fasterxml.jackson.databind.JsonNode): AfdianOrder {
        return AfdianOrder(
            outTradeNo = node["out_trade_no"]?.asText() ?: "",
            customOrderId = node["custom_order_id"]?.asText() ?: "",
            userId = node["user_id"]?.asText() ?: "",
            planId = node["plan_id"]?.asText() ?: "",
            title = node["title"]?.asText() ?: "",
            month = node["month"]?.asInt() ?: 0,
            totalAmount = node["total_amount"]?.asText()?.toDoubleOrNull()?.let { Math.round(it * 100).toInt() } ?: 0,
            showAmount = node["show_amount"]?.asText() ?: "",
            status = node["status"]?.asText() ?: "",
            remark = node["remark"]?.asText() ?: "",
            redeemId = node["redeem_id"]?.asText() ?: "",
            productType = node["product_type"]?.asInt() ?: 0,
            skuDetail = node["sku_detail"]?.toString() ?: "[]"
        )
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}

/**
 * 爱发电订单。
 */
data class AfdianOrder(
    val outTradeNo: String,
    val customOrderId: String,
    val userId: String,
    val planId: String,
    val title: String,
    val month: Int,
    /** 金额，单位：分 */
    val totalAmount: Int,
    val showAmount: String,
    val status: String,
    val remark: String,
    val redeemId: String,
    val productType: Int,
    val skuDetail: String
)

/**
 * 爱发电订单分页。
 */
data class AfdianOrderPage(
    val total: Int,
    val totalPages: Int,
    val list: List<AfdianOrder>
)
