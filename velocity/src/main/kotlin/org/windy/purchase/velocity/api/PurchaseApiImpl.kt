package org.windy.purchase.velocity.api

import org.windy.purchase.core.api.*
import org.windy.purchase.core.db.Database
import org.windy.purchase.core.model.Order
import org.windy.purchase.core.model.OrderStatus
import org.windy.purchase.core.model.Product
import org.windy.purchase.core.payment.PaymentManager
import org.windy.purchase.velocity.notify.PaymentNotifier
import java.io.File
import java.nio.file.Path
import java.util.UUID

/**
 * PurchaseApi 实现 — 桥接 core 接口到 velocity 模块的实际组件。
 */
class PurchaseApiImpl(
    private val manager: PaymentManager,
    private val db: Database,
    private val notifier: PaymentNotifier?,
    private val dataDir: Path
) : PurchaseApi {

    // ==================== 订单 ====================

    override fun createOrder(playerId: String, playerName: String, amountFen: Int, paymentMethod: String, productId: String?, commands: List<String>): Order? {
        val order = manager.createOrder(playerId, playerName, amountFen, paymentMethod, productId, commands)
        if (order != null) notifier?.onOrderCreated(order)
        return order
    }

    override fun getOrder(orderId: String): Order? = manager.getOrder(orderId)

    override fun getOrdersByPlayer(playerId: String, limit: Int): List<Order> = db.getOrdersByPlayer(playerId, limit)

    override fun getOrders(page: Int, size: Int, status: String?, method: String?, player: String?): List<Order> =
        db.getOrdersFiltered(page, size, status, method, player)

    override fun countOrders(status: String?, method: String?, player: String?): Int =
        db.countOrders(status, method, player)

    override fun cancelOrder(orderId: String): Boolean {
        val order = manager.getOrder(orderId) ?: return false
        if (order.status != OrderStatus.PENDING) return false
        manager.getProvider(order.paymentMethod)?.closeOrder(orderId)
        db.updateOrderStatusCAS(orderId, OrderStatus.PENDING, OrderStatus.CANCELLED, null)
        return true
    }

    // ==================== 商品 ====================

    override fun getProducts(): List<Product> = db.getAllProducts()
    override fun getProduct(id: String): Product? = db.getProduct(id)
    override fun saveProduct(product: Product) { db.insertProduct(product) }
    override fun deleteProduct(id: String): Boolean = db.deleteProduct(id)

    // ==================== 统计 ====================

    override fun getRevenue(): RevenueData {
        val stats = db.getRevenue()
        return RevenueData(stats.totalAmount, stats.todayAmount, stats.totalOrders)
    }

    override fun getRevenueRanking(limit: Int): List<PlayerRevenueData> =
        db.getRevenueRanking(limit).map { PlayerRevenueData(it.playerId, it.playerName, it.totalAmount) }

    override fun getRevenueSince(days: Int): Int = db.getRevenueSince(days)

    // ==================== 玩家画像 ====================

    override fun getPlayerTotalFen(playerId: String): Int = db.getPlayerStats(playerId).totalFen
    override fun getPlayerTodayFen(playerId: String): Int = db.getPlayerStats(playerId).todayFen
    override fun getPlayerOrderCount(playerId: String): Int = db.getPlayerStats(playerId).orderCount

    // ==================== 管理 ====================

    override fun grantOrder(playerName: String, amountFen: Int, commands: List<String>): Order {
        val orderId = UUID.randomUUID().toString().replace("-", "")
        val order = Order(
            orderId = orderId, playerId = playerName, playerName = playerName,
            productId = null, amount = amountFen, paymentMethod = "admin-grant",
            status = OrderStatus.PAID, commands = commands.map { it.replace("%player%", playerName) }
        )
        order.paidAt = System.currentTimeMillis()
        db.insertOrder(order)
        db.updateOrderStatus(orderId, OrderStatus.PAID, order.paidAt)
        return order
    }

    override fun reloadProducts() {
        loadProductsFromFile(db, dataDir.resolve("products.yml").toFile())
    }

    companion object {
        /** 从 YAML 文件加载商品到数据库，返回加载数量 */
        fun loadProductsFromFile(db: Database, file: File): Int {
            if (!file.exists()) return 0
            val yaml = org.yaml.snakeyaml.Yaml()
            val data = file.inputStream().use { yaml.load<Map<String, Any?>>(it) } ?: return 0
            @Suppress("UNCHECKED_CAST")
            val products = data["products"] as? List<Map<String, Any?>> ?: return 0
            for (p in products) {
                val id = p["id"]?.toString() ?: continue
                db.insertProduct(Product(id, p["name"]?.toString() ?: id,
                    ((p["price"] as? Number)?.toInt() ?: 0) * 100,
                    p["description"]?.toString() ?: "",
                    (p["commands"] as? List<String>) ?: emptyList(),
                    p["permission"]?.toString(),
                    (p["cooldown"] as? Number)?.toInt() ?: 0))
            }
            return products.size
        }
    }
}
