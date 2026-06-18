package org.windy.purchase.velocity.http

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.slf4j.Logger
import org.windy.purchase.core.db.Database
import org.windy.purchase.core.db.PlayerStatsCache
import org.windy.purchase.core.model.Order
import org.windy.purchase.core.model.OrderStatus
import org.windy.purchase.core.model.Product
import org.windy.purchase.core.payment.*
import org.windy.purchase.velocity.notify.PaymentNotifier
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 内嵌 HTTP 服务端 — REST API + 支付回调 + 静态资源。
 * JDK 内置 HttpServer，零外部依赖。
 */
class HttpServer(
    private val host: String,
    private val port: Int,
    private val token: String,
    private val manager: PaymentManager,
    private val db: Database,
    private val logger: Logger,
    private val notifier: PaymentNotifier? = null,
    private val statsCache: PlayerStatsCache? = null,
    private val dataDir: Path? = null,
    /** 是否服务内嵌静态页（前端面板）。默认关；REST API 始终可用，不受此影响。 */
    private val dashboardEnabled: Boolean = false,
    private val threadPoolSize: Int = Runtime.getRuntime().availableProcessors(),
    var commandExecutor: ((String) -> Unit)? = null
) {
    private val mapper = ObjectMapper()
    private var server: HttpServer? = null

    fun start() {
        val s = HttpServer.create(InetSocketAddress(host, port), 0)
        val counter = AtomicInteger()
        s.executor = ThreadPoolExecutor(
            threadPoolSize, threadPoolSize * 4, 60L, TimeUnit.SECONDS,
            LinkedBlockingQueue(256), ThreadPoolExecutor.CallerRunsPolicy()
        ).apply {
            threadFactory = java.util.concurrent.ThreadFactory { r ->
                Thread(r, "wp-http-${counter.incrementAndGet()}").apply { isDaemon = true }
            }
        }

        // 路由
        s.createContext("/api/orders") { h(::handleOrders) }
        s.createContext("/api/orders/player") { h(::handlePlayerOrders) }
        s.createContext("/api/products") { h(::handleProducts) }
        s.createContext("/api/revenue") { h(::handleRevenue) }
        s.createContext("/api/txlog") { h(::handleTxLog) }
        s.createContext("/api/grant") { h(::handleGrant) }
        s.createContext("/api/reload") { h(::handleReload) }
        s.createContext("/api/notify/alipay") { h(::handleAlipayNotify) }
        s.createContext("/api/notify/wechat") { h(::handleWechatNotify) }
        s.createContext("/api/notify/paypal") { h(::handlePaypalReturn) }
        s.createContext("/api/notify/afdian") { h(::handleAfdianWebhook) }
        s.createContext("/api/papi") { h(::handlePapiQuery) }
        s.createContext("/api/mock/confirm") { h(::handleMockConfirm) }
        s.createContext("/api/health") { respond(it, 200, """{"status":"ok"}""") }
        s.createContext("/") { h(::serveStatic) }

        s.start()
        server = s
        if (token.isBlank()) logger.warn("HTTP token is empty — API unauthenticated!")
        logger.info("HTTP server started on {}:{}", host, port)
    }

    fun stop() { server?.stop(0); server = null }

    /** 包装 handler，捕获未处理异常 */
    private fun h(block: (HttpExchange) -> Unit) = { ex: HttpExchange ->
        try { block(ex) } catch (e: Exception) { logger.error("HTTP error: {}", ex.requestURI, e); respond(ex, 500, err("Internal error")) }
    }

    // ==================== 订单 ====================

    private fun handleOrders(ex: HttpExchange) {
        val path = ex.requestURI.path.removePrefix("/api/orders")
        when {
            ex.requestMethod == "GET" && (path.isBlank() || path == "/") -> {
                if (!auth(ex)) return
                val q = query(ex)
                val page = q["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                val size = q["size"]?.toIntOrNull()?.coerceIn(1, 100) ?: 20
                val orders = db.getOrdersFiltered(page, size, q["status"], q["method"], q["player"])
                val total = db.countOrders(q["status"], q["method"], q["player"])
                json(ex, 200, obj { put("total", total); put("page", page); put("size", size); set<JsonNode>("data", arr(orders.map(::orderJson))) })
            }
            ex.requestMethod == "POST" && path.isBlank() -> {
                if (!auth(ex)) return
                val b = body(ex)
                val playerId = b["playerId"]?.asText() ?: return respond(ex, 400, err("playerId required"))
                val playerName = b["playerName"]?.asText() ?: "Unknown"
                val amount = b["amount"]?.asInt()?.takeIf { it > 0 } ?: return respond(ex, 400, err("amount must be positive"))
                val method = b["paymentMethod"]?.asText() ?: return respond(ex, 400, err("paymentMethod required"))
                val cmds = b["commands"]?.map { it.asText() } ?: emptyList()
                val order = manager.createOrder(playerId, playerName, amount, method, b["productId"]?.asText(), cmds)
                if (order != null) { notifier?.onOrderCreated(order); json(ex, 200, orderJson(order)) }
                else respond(ex, 500, err("Failed to create order"))
            }
            ex.requestMethod == "GET" && path.isNotBlank() -> {
                if (!auth(ex)) return
                val order = manager.getOrder(path.removePrefix("/"))
                if (order != null) json(ex, 200, orderJson(order)) else respond(ex, 404, err("Not found"))
            }
            ex.requestMethod == "POST" && path.endsWith("/close") -> {
                if (!auth(ex)) return
                val orderId = path.removeSuffix("/close").removePrefix("/")
                val order = manager.getOrder(orderId)
                if (order != null && order.status == OrderStatus.PENDING) {
                    manager.getProvider(order.paymentMethod)?.closeOrder(orderId)
                    respond(ex, 200, OK)
                } else respond(ex, 404, err("Not found or not pending"))
            }
            else -> respond(ex, 405, err("Method not allowed"))
        }
    }

    private fun handlePlayerOrders(ex: HttpExchange) {
        if (!auth(ex)) return
        val playerId = ex.requestURI.path.removePrefix("/api/orders/player/").takeWhile { it != '/' && it != '?' }
        if (playerId.isBlank()) return respond(ex, 400, err("playerId required"))
        val limit = query(ex)["limit"]?.toIntOrNull() ?: 20
        json(ex, 200, arr(db.getOrdersByPlayer(playerId, limit).map(::orderJson)))
    }

    // ==================== 商品 ====================

    private fun handleProducts(ex: HttpExchange) {
        if (!auth(ex)) return
        val path = ex.requestURI.path.removePrefix("/api/products").trim('/')
        when {
            ex.requestMethod == "GET" && path.isBlank() -> json(ex, 200, arr(db.getAllProducts().map(::productJson)))
            ex.requestMethod == "GET" && path.isNotBlank() -> {
                val p = db.getProduct(path)
                if (p != null) json(ex, 200, productJson(p)) else respond(ex, 404, err("Not found"))
            }
            ex.requestMethod == "POST" -> {
                val b = body(ex)
                val id = b["id"]?.asText() ?: return respond(ex, 400, err("id required"))
                val price = b["price"]?.asInt()?.takeIf { it > 0 } ?: return respond(ex, 400, err("price must be positive"))
                val product = Product(id, b["name"]?.asText() ?: id, price, b["description"]?.asText() ?: "",
                    b["commands"]?.map { it.asText() } ?: emptyList(), b["permission"]?.asText(), b["cooldown"]?.asInt() ?: 0)
                db.insertProduct(product)
                json(ex, 200, productJson(product))
            }
            ex.requestMethod == "DELETE" && path.isNotBlank() -> {
                if (db.deleteProduct(path)) respond(ex, 200, OK) else respond(ex, 404, err("Not found"))
            }
            else -> respond(ex, 405, err("Method not allowed"))
        }
    }

    // ==================== 统计 ====================

    private fun handleRevenue(ex: HttpExchange) {
        if (!auth(ex)) return
        val path = ex.requestURI.path.removePrefix("/api/revenue").trim('/')
        when (path) {
            "", "/" -> {
                val s = db.getRevenue()
                json(ex, 200, obj { put("totalAmount", s.totalAmount); put("todayAmount", s.todayAmount); put("totalOrders", s.totalOrders) })
            }
            "ranking", "ranking/" -> json(ex, 200, arr(db.getRevenueRanking(10).map { r ->
                obj { put("playerId", r.playerId); put("playerName", r.playerName); put("totalAmount", r.totalAmount) }
            }))
            else -> respond(ex, 404, err("Not found"))
        }
    }

    // ==================== 支付回调 ====================

    private fun handleAlipayNotify(ex: HttpExchange) {
        if (ex.requestMethod != "POST") return respond(ex, 405, "Method not allowed")
        try {
            val params = formBody(ex)
            val provider = manager.getProvider("alipay") as? AlipayProvider ?: return respond(ex, 500, "Alipay not configured")
            if (!provider.verifyNotify(params)) { logger.warn("Alipay signature failed"); return respond(ex, 400, "fail") }
            val orderId = params["out_trade_no"] ?: return respond(ex, 400, "fail")
            if (params["trade_status"] in listOf("TRADE_SUCCESS", "TRADE_FINISHED")) {
                manager.handleNotify(orderId, params["total_amount"]?.let { Math.round(it.toDouble() * 100).toInt() })
                logger.info("Alipay notify: {} paid", orderId)
            }
            respond(ex, 200, "success")
        } catch (e: Exception) { logger.error("Alipay notify error", e); respond(ex, 500, "fail") }
    }

    private fun handleWechatNotify(ex: HttpExchange) {
        if (ex.requestMethod != "POST") return respond(ex, 405, "Method not allowed")
        try {
            val headers = mutableMapOf<String, String>().apply { ex.requestHeaders.forEach { (k, v) -> this[k] = v.joinToString(",") } }
            val provider = manager.getProvider("wechat") as? WeChatPayProvider ?: return respond(ex, 500, "WeChat not configured")
            val result = provider.parseNotify(headers, readRawBody(ex)) ?: return respond(ex, 400, jsonStr("code" to "FAIL", "message" to "Parse failed"))
            if (result.tradeState == "SUCCESS") { manager.handleNotify(result.outTradeNo, result.totalAmount); logger.info("WeChat notify: {} paid", result.outTradeNo) }
            respond(ex, 200, jsonStr("code" to "SUCCESS", "message" to "OK"))
        } catch (e: Exception) { logger.error("WeChat notify error", e); respond(ex, 500, jsonStr("code" to "FAIL", "message" to "Error")) }
    }

    private fun handlePaypalReturn(ex: HttpExchange) {
        val paypalId = query(ex)["token"] ?: return respond(ex, 400, err("Missing token"))
        val provider = manager.getProvider("paypal") as? PayPalProvider ?: return respond(ex, 500, err("PayPal not configured"))
        try {
            val (success, captureId) = provider.captureOrder(paypalId)
            if (success) {
                provider.getLocalOrderId(paypalId)?.let { manager.handleNotify(it, null) }
                logger.info("PayPal capture: {} -> {}", paypalId, captureId)
                respond(ex, 200, """{"status":"success"}""")
            } else respond(ex, 400, err("Capture failed"))
        } catch (e: Exception) { logger.error("PayPal error", e); respond(ex, 500, err("Error")) }
    }

    private fun handleAfdianWebhook(ex: HttpExchange) {
        if (ex.requestMethod != "POST") return respond(ex, 405, "Method not allowed")
        try {
            val provider = manager.getProvider("afdian") as? AfdianProvider ?: return respond(ex, 500, err("Afdian not configured"))
            val afdianOrder = provider.verifyWebhook(readRawBody(ex)) ?: return respond(ex, 400, """{"code":400}""")
            val matched = (if (afdianOrder.customOrderId.isNotBlank()) manager.getOrder(afdianOrder.customOrderId) else null)
                ?: if (afdianOrder.remark.isNotBlank()) db.getPendingOrderByPlayerName(afdianOrder.remark.trim(), "afdian") else null
            if (matched != null) { manager.handleNotify(matched.orderId, afdianOrder.totalAmount); logger.info("Afdian: matched {} -> {}", afdianOrder.outTradeNo, matched.orderId) }
            respond(ex, 200, """{"code":200}""")
        } catch (e: Exception) { logger.error("Afdian error", e); respond(ex, 500, """{"code":500}""") }
    }

    // ==================== 工具 API ====================

    private fun handlePapiQuery(ex: HttpExchange) {
        if (!auth(ex)) return
        val q = query(ex)
        val playerId = q["player"] ?: return respond(ex, 400, err("player required"))
        val placeholder = q["placeholder"] ?: return respond(ex, 400, err("placeholder required"))
        val cache = statsCache ?: return respond(ex, 500, err("Cache unavailable"))
        val value = org.windy.purchase.velocity.papi.PurchasePapiHelper(cache).resolve(playerId, placeholder)
        if (value != null) json(ex, 200, obj { put("player", playerId); put("placeholder", placeholder); put("value", value) })
        else respond(ex, 404, err("Unknown placeholder"))
    }

    private fun handleMockConfirm(ex: HttpExchange) {
        if (ex.requestMethod != "POST") return respond(ex, 405, "Method not allowed")
        if (!auth(ex)) return
        val orderId = body(ex)["orderId"]?.asText() ?: return respond(ex, 400, err("orderId required"))
        val mock = manager.getProvider("mock") as? MockProvider ?: return respond(ex, 500, err("Mock not available"))
        if (mock.confirmOrder(orderId)) respond(ex, 200, OK) else respond(ex, 404, err("Not found"))
    }

    private fun handleTxLog(ex: HttpExchange) {
        if (!auth(ex)) return
        val count = query(ex)["lines"]?.toIntOrNull()?.coerceIn(1, 500) ?: 50
        val file = File(dataDir?.toFile() ?: return, "transactions.log")
        if (!file.exists()) return respond(ex, 200, "[]")
        json(ex, 200, strArr(file.readLines().filter { !it.startsWith("#") }.takeLast(count)))
    }

    private fun handleGrant(ex: HttpExchange) {
        if (ex.requestMethod != "POST") return respond(ex, 405, err("POST required"))
        if (!auth(ex)) return
        val b = body(ex)
        val player = b["player"]?.asText() ?: return respond(ex, 400, err("player required"))
        val amount = b["amount"]?.asInt()?.takeIf { it > 0 } ?: return respond(ex, 400, err("amount required"))
        val cmds = b["commands"]?.map { it.asText() } ?: emptyList()
        val orderId = "grant_" + UUID.randomUUID().toString().replace("-", "")
        val order = Order(orderId, player, player, null, amount, "CNY", "admin-grant", OrderStatus.PAID, commands = cmds)
        order.paidAt = System.currentTimeMillis()
        db.insertOrder(order)  // 已带 status=PAID
        // 统一走发奖管线（执行命令 + 通知玩家）；notifier 缺失时退回直接执行命令
        notifier?.onPaid(order) ?: cmds.forEach { commandExecutor?.invoke(it.replace("%player%", player)) }
        json(ex, 200, obj { put("orderId", orderId); put("player", player); put("amount", amount) })
    }

    private fun handleReload(ex: HttpExchange) {
        if (ex.requestMethod != "POST") return respond(ex, 405, err("POST required"))
        if (!auth(ex)) return
        val file = File(dataDir?.toFile() ?: return, "products.yml")
        if (!file.exists()) return respond(ex, 404, err("products.yml not found"))
        val count = org.windy.purchase.velocity.api.PurchaseApiImpl.loadProductsFromFile(db, file)
        respond(ex, 200, """{"ok":true,"products":$count}""")
    }

    // ==================== 静态资源 ====================

    private fun serveStatic(ex: HttpExchange) {
        if (!dashboardEnabled) return respond(ex, 404, err("Dashboard disabled"))
        val path = ex.requestURI.path.let { if (it == "/") "/index.html" else it }
        if (path.contains("..")) return respond(ex, 403, err("Forbidden"))
        val bytes = loadRes(path) ?: loadRes("/index.html") ?: return respond(ex, 404, err("Not found"))
        val ct = when { path.endsWith(".html") -> "text/html"; path.endsWith(".js") -> "application/javascript"; path.endsWith(".css") -> "text/css"; path.endsWith(".png") -> "image/png"; else -> "application/octet-stream" }
        ex.responseHeaders.set("Content-Type", "$ct; charset=utf-8")
        ex.responseHeaders.set("Cache-Control", "public, max-age=300")
        ex.sendResponseHeaders(200, bytes.size.toLong())
        try { ex.responseBody.write(bytes) } finally { ex.close() }
    }

    private fun loadRes(path: String): ByteArray? = javaClass.getResourceAsStream("/static$path")?.use { it.readBytes() }

    // ==================== 底层工具 ====================

    private fun auth(ex: HttpExchange): Boolean {
        if (token.isBlank()) return true
        val t = query(ex)["token"] ?: ex.requestHeaders.getFirst("X-Token")
        if (t == token) return true
        respond(ex, 401, err("Unauthorized")); return false
    }

    private fun body(ex: HttpExchange): JsonNode {
        val raw = readRawBody(ex)
        return if (raw.isBlank()) mapper.createObjectNode() else mapper.readTree(raw)
    }

    private fun readRawBody(ex: HttpExchange): String {
        val buf = ex.requestBody.readNBytes(1024 * 1024 + 1)
        if (buf.size > 1024 * 1024) { ex.requestBody.close(); return "" }
        return buf.toString(StandardCharsets.UTF_8)
    }

    private fun respond(ex: HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        ex.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        try { ex.responseBody.write(bytes) } finally { ex.close() }
    }

    private fun json(ex: HttpExchange, code: Int, node: JsonNode) = respond(ex, code, node.toString())

    private fun query(ex: HttpExchange): Map<String, String> {
        val q = ex.requestURI.query ?: return emptyMap()
        return q.split("&").associate { val p = it.split("=", limit = 2); urlDec(p[0]) to urlDec(p.getOrElse(1) { "" }) }
    }

    private fun formBody(ex: HttpExchange): Map<String, String> {
        val body = readRawBody(ex)
        if (body.isBlank()) return emptyMap()
        return body.split("&").associate { val p = it.split("=", limit = 2); urlDec(p[0]) to urlDec(p.getOrElse(1) { "" }) }
    }

    private fun urlDec(s: String) = URLDecoder.decode(s, "UTF-8")
    private fun err(msg: String) = """{"error":"${msg.replace("\\", "\\\\").replace("\"", "\\\"")}"}"""
    private val OK = """{"ok":true}"""
    private fun obj(block: ObjectNode.() -> Unit): ObjectNode = mapper.createObjectNode().apply(block)
    private fun arr(items: List<JsonNode>): ArrayNode = mapper.createArrayNode().apply { items.forEach { add(it) } }
    private fun strArr(items: List<String>): ArrayNode = mapper.createArrayNode().apply { items.forEach { add(it) } }
    private fun jsonStr(vararg pairs: Pair<String, String>): String = obj { pairs.forEach { (k, v) -> put(k, v) } }.toString()

    private fun orderJson(o: Order): ObjectNode = obj {
        put("orderId", o.orderId); put("playerId", o.playerId); put("playerName", o.playerName)
        put("productId", o.productId); put("amount", o.amount); put("currency", o.currency)
        put("paymentMethod", o.paymentMethod); put("status", o.status.name)
        put("qrCodeUrl", o.qrCodeUrl); put("rawUrl", o.rawUrl)
        put("createdAt", o.createdAt); put("paidAt", o.paidAt)
        set<ArrayNode>("commands", strArr(o.commands))
    }

    private fun productJson(p: Product): ObjectNode = obj {
        put("id", p.id); put("name", p.name); put("price", p.price)
        put("description", p.description); put("permission", p.permission); put("cooldown", p.cooldown)
        set<ArrayNode>("commands", strArr(p.commands))
    }
}
