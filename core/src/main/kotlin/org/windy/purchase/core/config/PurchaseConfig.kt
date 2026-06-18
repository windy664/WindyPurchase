package org.windy.purchase.core.config

import org.slf4j.LoggerFactory
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.io.FileInputStream

/**
 * 插件配置 — 从 config.yml 加载。
 */
class PurchaseConfig private constructor(private val map: Map<String, Any?>) {

    // ==================== 通用 ====================
    fun locale(): String = get("locale", "zh")

    // ==================== HTTP ====================
    fun httpHost(): String = get("http.host", "0.0.0.0")
    fun httpPort(): Int = get("http.port", 9080)
    fun httpToken(): String = get("http.token", "")

    // ==================== 订单 ====================
    fun orderTimeoutSeconds(): Int = get("order.timeout-seconds", 300)
    fun orderPollIntervalMs(): Long = get("order.poll-interval-ms", 3000L)

    // ==================== 金额校验容差 ====================
    /** 少付容差（百分比，0=精确到分）。实付低于应付超过此比例则拒单。 */
    fun underpayTolerancePercent(): Int = get("payment.amount-check.underpay-tolerance-percent", 0)
    /** 多付容差上限（百分比，-1=不限制，玩家多付不拒单）。 */
    fun overpayTolerancePercent(): Int = get("payment.amount-check.overpay-tolerance-percent", -1)
    /** 按支付方式覆盖少付容差（如爱发电含平台手续费）。 */
    fun underpayToleranceOverrides(): Map<String, Int> {
        @Suppress("UNCHECKED_CAST")
        val raw = get("payment.amount-check.underpay-overrides", emptyMap<String, Any?>()) as? Map<String, Any?> ?: return emptyMap()
        return raw.mapNotNull { (k, v) -> (v as? Number)?.toInt()?.let { k to it } }.toMap()
    }

    // ==================== 可选模块（纯网关定位：默认全关，按需开启） ====================
    /** 每日首充奖励。 */
    fun moduleDailyReward(): Boolean = get("modules.daily-reward", false)
    /** 优惠码。 */
    fun modulePromoCode(): Boolean = get("modules.promo-code", false)
    /** 充值排行榜（/purchase top 命令 + /api/revenue/ranking）。 */
    fun moduleRanking(): Boolean = get("modules.ranking", false)
    /** 内嵌 Web 管理面板静态页（支付回调等 API 始终可用，不受此开关影响）。 */
    fun moduleDashboard(): Boolean = get("modules.dashboard", false)

    // ==================== 游戏内地图二维码（进阶/默认关） ====================
    /** 是否启用地图二维码注入。默认关：可靠扫码靠聊天链接+ASCII（始终生效）。 */
    fun mapQrEnabled(): Boolean = get("map-qr.enabled", false)
    fun mapQrSetSlotPacketId(): Int = get("map-qr.packet-set-slot", 0x14)
    fun mapQrMapDataPacketId(): Int = get("map-qr.packet-map-data", 0x27)
    fun mapQrFilledMapItemId(): Int = get("map-qr.filled-map-item-id", 358)
    fun mapQrRestoreDelaySeconds(): Long = get("map-qr.restore-delay-seconds", 5).toLong()
    /** 客户端协议号白名单（留空=不校验直接尝试）。 */
    fun mapQrSupportedProtocols(): Set<Int> {
        @Suppress("UNCHECKED_CAST")
        val raw = get("map-qr.supported-protocols", emptyList<Any?>()) as? List<Any?> ?: return emptySet()
        return raw.mapNotNull { (it as? Number)?.toInt() }.toSet()
    }

    // ==================== Mock（测试用） ====================
    fun mockEnabled(): Boolean = get("mock.enabled", true)
    /** 自动确认延迟（毫秒），0 = 手动确认 */
    fun mockAutoConfirmMs(): Long = get("mock.auto-confirm-ms", 5000L)

    // ==================== 支付宝 ====================
    fun alipayEnabled(): Boolean = get("alipay.enabled", false)
    fun alipayAppId(): String = get("alipay.app-id", "")
    fun alipayPrivateKey(): String = get("alipay.private-key", "")
    fun alipayPublicKey(): String = get("alipay.alipay-public-key", "")
    fun alipayNotifyUrl(): String = get("alipay.notify-url", "")
    fun alipaySandbox(): Boolean = get("alipay.sandbox", false)

    // ==================== 微信 ====================
    fun wechatEnabled(): Boolean = get("wechat.enabled", false)
    fun wechatAppId(): String = get("wechat.app-id", "")
    fun wechatMchId(): String = get("wechat.mch-id", "")
    fun wechatMchSerialNumber(): String = get("wechat.mch-serial-number", "")
    fun wechatApiV3Key(): String = get("wechat.api-v3-key", "")
    fun wechatPrivateKeyPath(): String = get("wechat.private-key-path", "")
    fun wechatNotifyUrl(): String = get("wechat.notify-url", "")

    // ==================== PayPal ====================
    fun paypalEnabled(): Boolean = get("paypal.enabled", false)
    fun paypalClientId(): String = get("paypal.client-id", "")
    fun paypalClientSecret(): String = get("paypal.client-secret", "")
    fun paypalCurrency(): String = get("paypal.currency", "USD")
    fun paypalSandbox(): Boolean = get("paypal.sandbox", true)
    fun paypalReturnUrl(): String = get("paypal.return-url", "")
    fun paypalCancelUrl(): String = get("paypal.cancel-url", "")

    // ==================== 爱发电 ====================
    fun afdianEnabled(): Boolean = get("afdian.enabled", false)
    fun afdianUserId(): String = get("afdian.user-id", "")
    fun afdianToken(): String = get("afdian.token", "")
    fun afdianBaseUrl(): String = get("afdian.base-url", "https://afdian.com/api")
    /** 轮询模式间隔（毫秒），默认 15 秒 */
    fun afdianPollIntervalMs(): Long = get("afdian.poll-interval-ms", 15_000L)
    /** 是否启用轮询模式（默认 true，不需要公网） */
    fun afdianPollEnabled(): Boolean = get("afdian.poll-enabled", true)
    /** 是否启用 webhook 模式（需公网端点或 CF Worker 中转） */
    fun afdianWebhookEnabled(): Boolean = get("afdian.webhook-enabled", false)
    /**
     * 爱发电商品映射：方案标题 → 执行的命令。
     * 格式：[{title: "VIP月卡", commands: ["lp user %player% parent add vip 30d"]}]
     */
    fun afdianPlans(): List<AfdianPlanConfig> {
        @Suppress("UNCHECKED_CAST")
        val plans = get("afdian.plans", emptyList<List<Map<String, Any?>>>()) as? List<Map<String, Any?>> ?: return emptyList()
        return plans.mapNotNull { p ->
            val title = p["title"]?.toString() ?: return@mapNotNull null
            @Suppress("UNCHECKED_CAST")
            val commands = (p["commands"] as? List<String>) ?: emptyList()
            AfdianPlanConfig(title, commands)
        }
    }

    // ==================== 辅助方法 ====================
    @Suppress("UNCHECKED_CAST")
    private fun <T> get(path: String, default: T): T {
        val parts = path.split(".")
        var current: Any? = map
        for (part in parts) {
            current = (current as? Map<String, Any?>)?.get(part) ?: return default
        }
        return (current as? T) ?: default
    }

    companion object {
        private val YAML = Yaml()
        private val LOG = LoggerFactory.getLogger(PurchaseConfig::class.java)
        private val TOP_KEY = Regex("^([A-Za-z][A-Za-z0-9_-]*):")

        /**
         * 从文件加载配置。
         * - 文件不存在 → 释放完整默认配置。
         * - 文件存在但缺少升级后新增的顶层配置段 → 自动补齐缺失段（保留用户原内容与注释）。
         */
        fun load(file: File): PurchaseConfig {
            if (!file.exists()) {
                file.parentFile?.mkdirs()
                file.writeText(DEFAULT_CONFIG)
                return PurchaseConfig(parse(file))
            }
            var map = parse(file)
            val missing = missingSections(map.keys.map { it.toString() }.toSet())
            if (missing.isNotEmpty()) {
                file.appendText("\n" + missing.joinToString("\n\n") + "\n")
                map = parse(file)
                LOG.info("config.yml 已自动补齐缺失配置段: {}", missing.mapNotNull { topKeyOf(it) })
            }
            return PurchaseConfig(map)
        }

        private fun parse(file: File): Map<String, Any?> =
            FileInputStream(file).use { YAML.load<Map<String, Any?>>(it) } ?: emptyMap()

        /** 取一段配置文本里的顶层 key（跳过注释/空行；缩进行不算顶层）。 */
        private fun topKeyOf(chunk: String): String? {
            for (line in chunk.lines()) {
                if (line.isBlank() || line.trimStart().startsWith("#")) continue
                if (line.first().isWhitespace()) return null
                return TOP_KEY.find(line)?.groupValues?.get(1)
            }
            return null
        }

        /** 默认模板里、用户配置中缺失的顶层段（按空行切块，连带注释）。 */
        private fun missingSections(presentKeys: Set<String>): List<String> =
            DEFAULT_CONFIG.split(Regex("\\n[ \\t]*\\n")).filter { chunk ->
                val k = topKeyOf(chunk)
                k != null && k !in presentKeys
            }

        /**
         * 默认配置模板。单一来源 = 资源文件 core/src/main/resources/config.yml
         * （随 shadowJar 打进插件 jar），首启释放 + 升级自愈都基于它。
         */
        val DEFAULT_CONFIG: String by lazy {
            PurchaseConfig::class.java.getResourceAsStream("/config.yml")
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: run {
                    LOG.error("内置 config.yml 资源缺失！将生成空配置（各项使用代码默认值）")
                    ""
                }
        }
    }
}

data class AfdianPlanConfig(
    /** 爱发电方案标题（模糊匹配） */
    val title: String,
    /** 匹配后执行的命令 */
    val commands: List<String>
)
