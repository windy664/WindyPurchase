package org.windy.purchase.core.db

import org.slf4j.LoggerFactory
import org.windy.purchase.core.model.Order
import org.windy.purchase.core.model.OrderStatus
import org.windy.purchase.core.model.Product
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * SQLite 数据库 — 线程安全版。
 *
 * 关键：JDBC Connection 不是线程安全的。
 * 所有操作（读+写）必须通过同一把锁序列化。
 * SQLite + WAL 模式下单连接读写延迟在微秒级，全锁不会成为瓶颈。
 */
class Database(dbPath: String) {
    private val log = LoggerFactory.getLogger(Database::class.java)
    private val conn: Connection
    private val lock = ReentrantLock()

    init {
        Class.forName("org.sqlite.JDBC")
        conn = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        conn.createStatement().use { stmt ->
            stmt.execute("PRAGMA journal_mode=WAL")
            stmt.execute("PRAGMA synchronous=NORMAL")
            stmt.execute("PRAGMA busy_timeout=5000")
            stmt.execute("PRAGMA cache_size=-8000")
        }
        conn.autoCommit = true
        createTables()
        log.info("数据库已连接 (WAL mode): {}", dbPath)
    }

    private fun createTables() {
        conn.createStatement().use { stmt ->
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS orders (
                    order_id    TEXT PRIMARY KEY,
                    player_id   TEXT NOT NULL,
                    player_name TEXT NOT NULL,
                    product_id  TEXT,
                    amount      INTEGER NOT NULL,
                    currency    TEXT NOT NULL DEFAULT 'CNY',
                    payment_method TEXT NOT NULL,
                    status      TEXT NOT NULL DEFAULT 'PENDING',
                    qr_code_url TEXT,
                    raw_url     TEXT,
                    created_at  INTEGER NOT NULL,
                    paid_at     INTEGER,
                    commands    TEXT DEFAULT '[]',
                    version     INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS products (
                    id          TEXT PRIMARY KEY,
                    name        TEXT NOT NULL,
                    price       INTEGER NOT NULL,
                    description TEXT DEFAULT '',
                    commands    TEXT DEFAULT '[]',
                    permission  TEXT,
                    cooldown    INTEGER DEFAULT 0
                )
            """.trimIndent())
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_orders_player ON orders(player_id)")
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_orders_status ON orders(status)")
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_orders_method ON orders(payment_method, status)")
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_orders_name_method ON orders(player_name, payment_method, status)")
        }
    }

    // ==================== Orders ====================

    fun insertOrder(order: Order) = lock.withLock {
        conn.prepareStatement(
            "INSERT INTO orders (order_id, player_id, player_name, product_id, amount, currency, payment_method, status, qr_code_url, raw_url, created_at, paid_at, commands, version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)"
        ).use { ps ->
            ps.setString(1, order.orderId); ps.setString(2, order.playerId); ps.setString(3, order.playerName)
            ps.setString(4, order.productId); ps.setInt(5, order.amount); ps.setString(6, order.currency)
            ps.setString(7, order.paymentMethod); ps.setString(8, order.status.name); ps.setString(9, order.qrCodeUrl)
            ps.setString(10, order.rawUrl); ps.setLong(11, order.createdAt); ps.setObject(12, order.paidAt)
            ps.setString(13, order.commands.joinToString("|§|"))
            ps.executeUpdate()
        }
    }

    fun updateOrderStatusCAS(orderId: String, expectedStatus: OrderStatus, newStatus: OrderStatus, paidAt: Long?): Boolean = lock.withLock {
        conn.prepareStatement("UPDATE orders SET status = ?, paid_at = ?, version = version + 1 WHERE order_id = ? AND status = ?").use { ps ->
            ps.setString(1, newStatus.name); ps.setObject(2, paidAt); ps.setString(3, orderId); ps.setString(4, expectedStatus.name)
            ps.executeUpdate() > 0
        }
    }

    fun updateOrderStatus(orderId: String, status: OrderStatus, paidAt: Long?) = lock.withLock {
        conn.prepareStatement("UPDATE orders SET status = ?, paid_at = ? WHERE order_id = ?").use { ps ->
            ps.setString(1, status.name); ps.setObject(2, paidAt); ps.setString(3, orderId)
            ps.executeUpdate()
        }
    }

    fun getOrder(orderId: String): Order? = lock.withLock {
        conn.prepareStatement("SELECT * FROM orders WHERE order_id = ?").use { ps ->
            ps.setString(1, orderId)
            ps.executeQuery().use { rs -> if (rs.next()) resultSetToOrder(rs) else null }
        }
    }

    fun getOrdersByPlayer(playerId: String, limit: Int = 20): List<Order> = lock.withLock {
        conn.prepareStatement("SELECT * FROM orders WHERE player_id = ? ORDER BY created_at DESC LIMIT ?").use { ps ->
            ps.setString(1, playerId); ps.setInt(2, limit)
            ps.executeQuery().use { rs -> generateSequence { if (rs.next()) resultSetToOrder(rs) else null }.toList() }
        }
    }

    fun getPendingOrders(method: String): List<Order> = lock.withLock {
        conn.prepareStatement("SELECT * FROM orders WHERE payment_method = ? AND status = 'PENDING'").use { ps ->
            ps.setString(1, method)
            ps.executeQuery().use { rs -> generateSequence { if (rs.next()) resultSetToOrder(rs) else null }.toList() }
        }
    }

    fun getPendingOrderByPlayerName(playerName: String, method: String): Order? = lock.withLock {
        conn.prepareStatement("SELECT * FROM orders WHERE player_name = ? AND payment_method = ? AND status = 'PENDING' LIMIT 1").use { ps ->
            ps.setString(1, playerName); ps.setString(2, method)
            ps.executeQuery().use { rs -> if (rs.next()) resultSetToOrder(rs) else null }
        }
    }

    fun getOrdersFiltered(page: Int, size: Int, status: String?, method: String?, player: String?): List<Order> = lock.withLock {
        val conditions = mutableListOf<String>()
        val params = mutableListOf<Any>()
        if (status != null) { conditions.add("status = ?"); params.add(status) }
        if (method != null) { conditions.add("payment_method = ?"); params.add(method) }
        if (player != null) { conditions.add("(player_id = ? OR player_name = ?)"); params.add(player); params.add(player) }
        val where = if (conditions.isEmpty()) "" else "WHERE " + conditions.joinToString(" AND ")
        conn.prepareStatement("SELECT * FROM orders $where ORDER BY created_at DESC LIMIT ? OFFSET ?").use { ps ->
            params.forEachIndexed { i, p -> ps.setObject(i + 1, p) }
            ps.setInt(params.size + 1, size); ps.setInt(params.size + 2, (page - 1) * size)
            ps.executeQuery().use { rs -> generateSequence { if (rs.next()) resultSetToOrder(rs) else null }.toList() }
        }
    }

    fun countOrders(status: String?, method: String?, player: String?): Int = lock.withLock {
        val conditions = mutableListOf<String>()
        val params = mutableListOf<Any>()
        if (status != null) { conditions.add("status = ?"); params.add(status) }
        if (method != null) { conditions.add("payment_method = ?"); params.add(method) }
        if (player != null) { conditions.add("(player_id = ? OR player_name = ?)"); params.add(player); params.add(player) }
        val where = if (conditions.isEmpty()) "" else "WHERE " + conditions.joinToString(" AND ")
        conn.prepareStatement("SELECT COUNT(*) FROM orders $where").use { ps ->
            params.forEachIndexed { i, p -> ps.setObject(i + 1, p) }
            ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
    }

    fun getPlayerStats(playerId: String): CachedStats = lock.withLock {
        val todayStart = todayStartMs()
        var totalFen = 0; var todayFen = 0; var orderCount = 0; var lastAmount: Int? = null; var lastTime: Long? = null

        conn.prepareStatement("SELECT COALESCE(SUM(amount), 0) as total, COUNT(*) as cnt FROM orders WHERE player_id = ? AND status = 'PAID'").use { ps ->
            ps.setString(1, playerId)
            ps.executeQuery().use { rs -> if (rs.next()) { totalFen = rs.getInt("total"); orderCount = rs.getInt("cnt") } }
        }
        conn.prepareStatement("SELECT COALESCE(SUM(amount), 0) FROM orders WHERE player_id = ? AND status = 'PAID' AND paid_at >= ?").use { ps ->
            ps.setString(1, playerId); ps.setLong(2, todayStart)
            ps.executeQuery().use { rs -> if (rs.next()) todayFen = rs.getInt(1) }
        }
        conn.prepareStatement("SELECT amount, paid_at FROM orders WHERE player_id = ? AND status = 'PAID' ORDER BY paid_at DESC LIMIT 1").use { ps ->
            ps.setString(1, playerId)
            ps.executeQuery().use { rs -> if (rs.next()) { lastAmount = rs.getInt("amount"); lastTime = rs.getObject("paid_at") as? Long } }
        }
        CachedStats(playerId, totalFen, todayFen, orderCount, lastAmount, lastTime, RevenueStats(), System.currentTimeMillis())
    }

    fun getRevenue(): RevenueStats = lock.withLock {
        val stats = RevenueStats()
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT COALESCE(SUM(amount), 0) FROM orders WHERE status = 'PAID'").use { rs -> if (rs.next()) stats.totalAmount = rs.getInt(1) }
            val todayStart = todayStartMs()
            conn.prepareStatement("SELECT COALESCE(SUM(amount), 0) FROM orders WHERE status = 'PAID' AND paid_at >= ?").use { ps ->
                ps.setLong(1, todayStart)
                ps.executeQuery().use { rs -> if (rs.next()) stats.todayAmount = rs.getInt(1) }
            }
            stmt.executeQuery("SELECT COUNT(*) FROM orders WHERE status = 'PAID'").use { rs -> if (rs.next()) stats.totalOrders = rs.getInt(1) }
        }
        stats
    }

    fun getRevenueSince(days: Int): Int = lock.withLock {
        val since = System.currentTimeMillis() - days * 86400_000L
        conn.prepareStatement("SELECT COALESCE(SUM(amount), 0) FROM orders WHERE status = 'PAID' AND paid_at >= ?").use { ps ->
            ps.setLong(1, since)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
    }

    fun getRevenueRanking(limit: Int = 10): List<PlayerRevenue> = lock.withLock {
        conn.prepareStatement("SELECT player_id, player_name, SUM(amount) as total FROM orders WHERE status = 'PAID' GROUP BY player_id ORDER BY total DESC LIMIT ?").use { ps ->
            ps.setInt(1, limit)
            ps.executeQuery().use { rs -> generateSequence { if (rs.next()) PlayerRevenue(rs.getString("player_id"), rs.getString("player_name"), rs.getInt("total")) else null }.toList() }
        }
    }

    // ==================== Products ====================

    fun insertProduct(product: Product) = lock.withLock {
        conn.prepareStatement("INSERT OR REPLACE INTO products (id, name, price, description, commands, permission, cooldown) VALUES (?, ?, ?, ?, ?, ?, ?)").use { ps ->
            ps.setString(1, product.id); ps.setString(2, product.name); ps.setInt(3, product.price)
            ps.setString(4, product.description); ps.setString(5, product.commands.joinToString("|§|"))
            ps.setString(6, product.permission); ps.setInt(7, product.cooldown)
            ps.executeUpdate()
        }
    }

    fun getProduct(id: String): Product? = lock.withLock {
        conn.prepareStatement("SELECT * FROM products WHERE id = ?").use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { rs -> if (rs.next()) resultSetToProduct(rs) else null }
        }
    }

    fun getAllProducts(): List<Product> = lock.withLock {
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT * FROM products ORDER BY price").use { rs ->
                generateSequence { if (rs.next()) resultSetToProduct(rs) else null }.toList()
            }
        }
    }

    fun deleteProduct(id: String): Boolean = lock.withLock {
        conn.prepareStatement("DELETE FROM products WHERE id = ?").use { ps ->
            ps.setString(1, id); ps.executeUpdate() > 0
        }
    }

    // ==================== 内部 ====================

    private fun resultSetToOrder(rs: java.sql.ResultSet): Order = Order(
        orderId = rs.getString("order_id"), playerId = rs.getString("player_id"),
        playerName = rs.getString("player_name"), productId = rs.getString("product_id"),
        amount = rs.getInt("amount"), currency = rs.getString("currency"),
        paymentMethod = rs.getString("payment_method"), status = OrderStatus.valueOf(rs.getString("status")),
        qrCodeUrl = rs.getString("qr_code_url"), rawUrl = rs.getString("raw_url"),
        createdAt = rs.getLong("created_at"), paidAt = rs.getObject("paid_at") as? Long,
        commands = rs.getString("commands")?.split("|§|")?.filter { it.isNotBlank() } ?: emptyList()
    )

    private fun resultSetToProduct(rs: java.sql.ResultSet): Product = Product(
        id = rs.getString("id"), name = rs.getString("name"), price = rs.getInt("price"),
        description = rs.getString("description") ?: "",
        commands = rs.getString("commands")?.split("|§|")?.filter { it.isNotBlank() } ?: emptyList(),
        permission = rs.getString("permission"), cooldown = rs.getInt("cooldown")
    )

    fun close() { conn.close(); log.info("数据库已关闭") }

    companion object {
        fun todayStartMs(): Long {
            val cal = java.util.Calendar.getInstance()
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0); cal.set(java.util.Calendar.MINUTE, 0)
            cal.set(java.util.Calendar.SECOND, 0); cal.set(java.util.Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }
    }
}

data class RevenueStats(var totalAmount: Int = 0, var todayAmount: Int = 0, var totalOrders: Int = 0)
data class PlayerRevenue(val playerId: String, val playerName: String, val totalAmount: Int)
