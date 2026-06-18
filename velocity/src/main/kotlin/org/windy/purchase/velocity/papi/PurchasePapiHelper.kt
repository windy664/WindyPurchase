package org.windy.purchase.velocity.papi

import org.windy.purchase.core.db.PlayerStatsCache

/**
 * PlaceholderAPI 变量查询助手。
 *
 * 走 PlayerStatsCache，高频查询零 DB 压力。
 * 缓存在订单支付时自动失效，查询 TTL 兜底 5 分钟。
 *
 * 可用变量：
 *   %windypurchase_total%        — 玩家总充值金额（元）
 *   %windypurchase_today%        — 玩家今日充值金额（元）
 *   %windypurchase_orders%       — 玩家已支付订单数
 *   %windypurchase_last_amount%  — 最近一笔订单金额（元）
 *   %windypurchase_last_time%    — 最近一笔订单时间
 *   %windypurchase_server_total% — 全服总充值额（元）
 */
class PurchasePapiHelper(private val cache: PlayerStatsCache) {

    /**
     * 解析变量。Bukkit 子服 PAPI 扩展或 REST API 调用此方法。
     * @param playerId 玩家 UUID 字符串
     * @param placeholder 变量名（不含 %%）
     * @return 变量值，未知变量返回 null
     */
    fun resolve(playerId: String, placeholder: String): String? {
        return when (placeholder) {
            "total" -> String.format("%.2f", cache.getPlayerStats(playerId).totalFen / 100.0)
            "today" -> String.format("%.2f", cache.getPlayerStats(playerId).todayFen / 100.0)
            "orders" -> cache.getPlayerStats(playerId).orderCount.toString()
            "last_amount" -> cache.getPlayerStats(playerId).lastAmountFen?.let { String.format("%.2f", it / 100.0) }
            "last_time" -> cache.getPlayerStats(playerId).lastTime?.let {
                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(it)
            }
            "server_total" -> String.format("%.2f", cache.getServerStats().totalAmount / 100.0)
            else -> null
        }
    }
}
