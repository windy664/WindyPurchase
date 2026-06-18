package org.windy.purchase.core.config

import org.slf4j.LoggerFactory
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 优惠码系统（线程安全 + 延迟写盘）。
 */
class PromoCodeManager(private val file: File) {
    private val log = LoggerFactory.getLogger(PromoCodeManager::class.java)
    private val codes = ConcurrentHashMap<String, PromoCode>()
    private val yaml = Yaml()
    private val dirty = AtomicBoolean(false)

    init { load() }

    fun load() {
        codes.clear()
        if (!file.exists()) {
            file.writeText(DEFAULT_PROMO)
            return
        }
        try {
            @Suppress("UNCHECKED_CAST")
            val data = FileInputStream(file).use { yaml.load<Map<String, Any?>>(it) } ?: return
            @Suppress("UNCHECKED_CAST")
            val list = data["codes"] as? List<Map<String, Any?>> ?: return
            for (item in list) {
                val code = item["code"]?.toString()?.uppercase() ?: continue
                codes[code] = PromoCode(
                    code = code,
                    type = item["type"]?.toString() ?: "discount",
                    value = (item["value"] as? Number)?.toDouble() ?: 0.0,
                    maxUses = (item["max-uses"] as? Number)?.toInt() ?: -1,
                    usedCount = (item["used-count"] as? Number)?.toInt() ?: 0,
                    expiresAt = item["expires"]?.toString() ?: "",
                    applicableProducts = (item["products"] as? List<String>)?.toSet() ?: emptySet(),
                    minAmountFen = ((item["min-amount"] as? Number)?.toDouble()?.times(100))?.toInt() ?: 0
                )
            }
        } catch (e: Exception) {
            log.error("Failed to load promo codes", e)
        }
    }

    fun flush() {
        if (!dirty.compareAndSet(true, false)) return
        saveInternal()
    }

    private fun saveInternal() {
        val data = mapOf("codes" to codes.values.map { c ->
            mapOf(
                "code" to c.code, "type" to c.type, "value" to c.value,
                "max-uses" to c.maxUses, "used-count" to c.usedCount,
                "expires" to c.expiresAt, "products" to c.applicableProducts.toList(),
                "min-amount" to c.minAmountFen / 100.0
            )
        })
        file.writer().use { yaml.dump(data, it) }
    }

    /**
     * 验证并使用优惠码。线程安全（CAS 更新 usedCount）。
     */
    fun apply(code: String, originalAmountFen: Int, productId: String?): Int? {
        val upperCode = code.uppercase()
        val promo = codes[upperCode] ?: return null

        // 检查使用次数（CAS）
        if (promo.maxUses > 0) {
            var updated = promo
            while (updated.usedCount < updated.maxUses) {
                val newPromo = updated.copy(usedCount = updated.usedCount + 1)
                if (codes.replace(upperCode, updated, newPromo)) {
                    updated = newPromo
                    break
                }
                updated = codes[upperCode] ?: return null  // 被删除了
            }
            if (updated.usedCount >= promo.maxUses) return null
        }

        // 检查过期
        if (promo.expiresAt.isNotBlank()) {
            try {
                val expires = java.text.SimpleDateFormat("yyyy-MM-dd").parse(promo.expiresAt)
                if (System.currentTimeMillis() > expires.time) return null
            } catch (_: Exception) {}
        }

        // 检查最低金额
        if (originalAmountFen < promo.minAmountFen) return null

        // 检查适用商品
        if (promo.applicableProducts.isNotEmpty() && productId != null && productId !in promo.applicableProducts) return null

        // 计算折扣
        val newAmount = when (promo.type) {
            "discount" -> (originalAmountFen * promo.value / 100).toInt()
            "fixed" -> originalAmountFen - (promo.value * 100).toInt()
            else -> originalAmountFen
        }

        if (newAmount <= 0) return null

        // 延迟写盘
        dirty.set(true)
        return newAmount
    }

    fun getCode(code: String): PromoCode? = codes[code.uppercase()]
    fun getAllCodes(): List<PromoCode> = codes.values.toList()

    companion object {
        val DEFAULT_PROMO = """
# ============================================
# 优惠码配置
# ============================================
codes:
  - code: "WELCOME"
    type: "discount"
    value: 80
    max-uses: -1
    used-count: 0
    expires: ""
    products: []
    min-amount: 0
""".trimIndent()
    }
}

data class PromoCode(
    val code: String,
    val type: String,
    val value: Double,
    val maxUses: Int,
    val usedCount: Int,
    val expiresAt: String,
    val applicableProducts: Set<String>,
    val minAmountFen: Int
)
