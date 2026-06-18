package org.windy.purchase.core.db

import org.slf4j.LoggerFactory
import org.windy.purchase.core.model.Order
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 离线玩家命令缓存。
 *
 * 优化：
 * - computeIfAbsent 原子操作
 * - 延迟写盘（dirty flag + 定期 flush），避免每次 add 都 IO
 */
class OfflineCommandCache(private val file: File) {
    private val log = LoggerFactory.getLogger(OfflineCommandCache::class.java)
    private val cache = ConcurrentHashMap<String, MutableList<PendingCommand>>()
    private val yaml = Yaml()
    private val lock = Any()
    private val dirty = AtomicBoolean(false)

    init { load() }

    fun add(playerId: String, order: Order) {
        val pending = PendingCommand(order.orderId, order.commands, System.currentTimeMillis())
        cache.computeIfAbsent(playerId) { mutableListOf() }.add(pending)
        dirty.set(true)
        log.info("Cached offline commands for player {}: {} commands", playerId, order.commands.size)
    }

    fun takeAll(playerId: String): List<PendingCommand> {
        val result = cache.remove(playerId) ?: return emptyList()
        if (result.isNotEmpty()) dirty.set(true)
        return result
    }

    fun hasPending(playerId: String): Boolean = cache[playerId]?.isNotEmpty() == true

    /**
     * 定期调用：只在有变更时写盘。
     */
    fun flush() {
        if (!dirty.compareAndSet(true, false)) return
        save()
    }

    private fun load() {
        if (!file.exists()) return
        try {
            @Suppress("UNCHECKED_CAST")
            val data = FileInputStream(file).use { yaml.load<Map<String, Any?>>(it) } ?: return
            for ((key, value) in data) {
                @Suppress("UNCHECKED_CAST")
                val list = value as? List<Map<String, Any?>> ?: continue
                cache[key] = list.map { item ->
                    PendingCommand(
                        orderId = item["order"]?.toString() ?: "",
                        commands = (item["commands"] as? List<String>) ?: emptyList(),
                        createdAt = (item["created"] as? Number)?.toLong() ?: 0L
                    )
                }.toMutableList()
            }
            log.info("Loaded offline commands for {} players", cache.size)
        } catch (e: Exception) {
            log.error("Failed to load offline commands", e)
        }
    }

    private fun save() {
        synchronized(lock) {
            try {
                val data = cache.mapValues { (_, commands) ->
                    commands.map { mapOf("order" to it.orderId, "commands" to it.commands, "created" to it.createdAt) }
                }
                file.writer().use { yaml.dump(data, it) }
            } catch (e: Exception) {
                log.error("Failed to save offline commands", e)
            }
        }
    }
}

data class PendingCommand(
    val orderId: String,
    val commands: List<String>,
    val createdAt: Long
)
