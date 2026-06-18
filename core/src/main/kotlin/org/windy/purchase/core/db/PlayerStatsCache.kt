package org.windy.purchase.core.db

import java.util.concurrent.ConcurrentHashMap

/**
 * 玩家充值画像缓存。
 *
 * 优化：
 * - DB 层做聚合查询（SUM/COUNT），不加载全量订单
 * - computeIfAbsent 防缓存击穿
 * - TTL 兜底过期
 */
class PlayerStatsCache(
    private val db: Database,
    private val ttlMs: Long = 300_000L
) {
    private val cache = ConcurrentHashMap<String, CachedStats>()

    fun getPlayerStats(playerId: String): CachedStats {
        val cached = cache[playerId]
        if (cached != null && !cached.isExpired(ttlMs)) return cached
        // computeIfAbsent 防止多线程同时 miss 同一玩家
        return cache.computeIfAbsent(playerId) { loadFromDb(it) }.also {
            // 如果 computeIfAbsent 返回的是过期旧值，强制刷新
            if (it.isExpired(ttlMs)) {
                val fresh = loadFromDb(playerId)
                cache[playerId] = fresh
            }
        }
    }

    fun getServerStats(): RevenueStats {
        val cached = cache["__server__"]
        if (cached != null && !cached.isExpired(ttlMs)) return cached.serverStats
        val stats = db.getRevenue()
        cache["__server__"] = CachedStats("__server__", stats.totalAmount, stats.todayAmount, stats.totalOrders, null, null, stats, System.currentTimeMillis())
        return stats
    }

    fun invalidate(playerId: String) {
        cache.remove(playerId)
        cache.remove("__server__")
    }

    fun invalidateAll() = cache.clear()

    fun cleanup() {
        val now = System.currentTimeMillis()
        cache.entries.removeIf { it.value.isExpired(ttlMs, now) }
    }

    private fun loadFromDb(playerId: String): CachedStats {
        // 用 SQL 聚合，不加载全量订单
        return db.getPlayerStats(playerId)
    }
}

data class CachedStats(
    val playerId: String,
    val totalFen: Int,
    val todayFen: Int,
    val orderCount: Int,
    val lastAmountFen: Int?,
    val lastTime: Long?,
    val serverStats: RevenueStats,
    val cachedAt: Long
) {
    fun isExpired(ttlMs: Long, now: Long = System.currentTimeMillis()): Boolean = now - cachedAt > ttlMs
}
