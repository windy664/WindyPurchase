package org.windy.purchase.core.config

import java.util.concurrent.ConcurrentHashMap

/**
 * 每日首充奖励。
 *
 * 修复：today 不再构造时固定，每次 check 动态计算。
 * 内存不持久化，重启清零（每日数据本来就当天有效）。
 */
class DailyRewardManager(private val config: DailyRewardConfig) {
    /** 玩家今日是否已领奖 (playerId → 已领取的日期字符串) */
    private val claimed = ConcurrentHashMap<String, String>()

    /**
     * 检查并发放每日首充奖励。
     * @return 奖励命令列表，null = 不满足条件或已领取
     */
    fun checkAndClaim(playerId: String, playerName: String, paidAmountFen: Int): List<String>? {
        if (!config.enabled) return null
        if (paidAmountFen < config.minAmountFen) return null

        val today = todayString()
        val lastClaimed = claimed[playerId]
        if (lastClaimed == today) return null  // 今天已领取

        claimed[playerId] = today
        return config.commands.map { it.replace("%player%", playerName) }
    }

    fun isClaimed(playerId: String): Boolean {
        return claimed[playerId] == todayString()
    }

    /** 动态计算今天日期（本地时区），跨天自动刷新 */
    private fun todayString(): String {
        return java.text.SimpleDateFormat("yyyyMMdd").format(java.util.Date())
    }

    companion object {
        fun load(map: Map<String, Any?>): DailyRewardConfig {
            @Suppress("UNCHECKED_CAST")
            return DailyRewardConfig(
                enabled = map["enabled"] as? Boolean ?: false,
                minAmountFen = ((map["min-amount"] as? Number)?.toDouble()?.times(100))?.toInt() ?: 0,
                commands = (map["commands"] as? List<String>) ?: emptyList()
            )
        }
    }
}

data class DailyRewardConfig(
    val enabled: Boolean,
    val minAmountFen: Int,
    val commands: List<String>
)
