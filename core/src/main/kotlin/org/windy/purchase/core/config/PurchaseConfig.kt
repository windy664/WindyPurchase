package org.windy.purchase.core.config

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

        /**
         * 从文件加载配置。文件不存在则释放默认配置并加载。
         */
        fun load(file: File): PurchaseConfig {
            if (!file.exists()) {
                file.parentFile?.mkdirs()
                file.writeText(DEFAULT_CONFIG)
            }
            val map = FileInputStream(file).use { YAML.load<Map<String, Any?>>(it) } ?: emptyMap()
            return PurchaseConfig(map)
        }

        val DEFAULT_CONFIG = """
# ============================================
# WindyPurchase 配置文件
# ============================================

# 语言 (zh=中文, en=英文, 或自定义 messages.yml)
locale: "zh"

# HTTP 服务端（REST API + 支付回调）
http:
  host: "0.0.0.0"
  port: 9080
  # API 鉴权 Token（为空则不鉴权，生产环境务必设置）
  token: ""

# 订单设置
order:
  # 订单超时（秒）
  timeout-seconds: 300
  # 轮询间隔（毫秒）
  poll-interval-ms: 3000

# 金额校验（防欺诈核心）
# 原则：少付从严、多付放行。
payment:
  amount-check:
    # 少付容差（百分比，0=精确到分）。实付低于应付超过此比例则拒单。
    underpay-tolerance-percent: 0
    # 多付容差上限（百分比，-1=不限制，玩家多付不拒单）。
    overpay-tolerance-percent: -1
    # 按支付方式覆盖少付容差。爱发电含平台手续费，到账可能略低于应付，可适当放宽。
    underpay-overrides:
      afdian: 5

# 可选模块（纯网关定位：默认全关，按需开启）
# 注意：REST API 与内部 Java API 始终可用，不受这些开关影响。
modules:
  # 每日首充奖励（支付成功后额外发放）
  daily-reward: false
  # 优惠码（下单时按码减免）
  promo-code: false
  # 充值排行榜的玩家命令 /purchase top（/api/revenue/ranking 接口始终可用）
  ranking: false
  # 内嵌 Web 管理面板静态页（前端后续单独开发；API 不受影响）
  dashboard: false

# 游戏内地图二维码（进阶功能，默认关闭）
# 原理：向客户端注入原始协议包，把二维码渲染到一张临时地图上。较脆弱：
# 不同 MC 版本的包 ID / 物品网络 ID 不同，开启前需对照你的服务端版本调整下列值。
# 默认关闭即可：玩家始终能收到聊天框可点链接 + ASCII 二维码（可靠且跨版本）。
map-qr:
  enabled: false
  # 客户端协议号白名单（留空=不校验直接尝试）。例：1.20.5=766, 1.21=767
  supported-protocols: []
  # Clientbound Set Container Slot 包 ID（默认 0x14，按 1.20.5+）
  packet-set-slot: 0x14
  # Clientbound Map Data 包 ID（默认 0x27）
  packet-map-data: 0x27
  # filled_map 物品网络 ID（默认 358）
  filled-map-item-id: 358
  # 展示后恢复原物品的延迟（秒）
  restore-delay-seconds: 5

# Mock 支付（测试用，生产环境请关闭）
# /purchase pay 0.01 mock → 自动确认到账
mock:
  enabled: true
  # 自动确认延迟（毫秒），0 = 需手动调 /api/mock/confirm
  auto-confirm-ms: 5000

# 支付宝当面付
alipay:
  enabled: false
  app-id: ""
  private-key: ""
  alipay-public-key: ""
  # 异步回调地址（需公网可访问）
  notify-url: "http://your-server:9080/api/notify/alipay"
  sandbox: false

# 微信支付 Native
wechat:
  enabled: false
  app-id: ""
  mch-id: ""
  mch-serial-number: ""
  api-v3-key: ""
  private-key-path: ""
  notify-url: "http://your-server:9080/api/notify/wechat"

# PayPal
paypal:
  enabled: false
  client-id: ""
  client-secret: ""
  currency: "USD"
  sandbox: true
  # 支付完成后的跳转地址
  return-url: ""
  cancel-url: ""

# 爱发电 (Afdian)
# 双模式：轮询（默认，不需要公网）+ Webhook（实时，需公网或 CF Worker 中转）
# 玩家在爱发电下单时，在留言(remark)中填写游戏名
afdian:
  enabled: false
  # 爱发电用户 ID（在开发者设置中查看）
  user-id: ""
  # 爱发电 API Token（在开发者设置中生成）
  token: ""
  base-url: "https://afdian.com/api"
  # 轮询模式（默认开启，不需要公网端点）
  poll-enabled: true
  poll-interval-ms: 15000
  # Webhook 模式（需公网端点或 Cloudflare Worker 中转）
  # 部署方法见 scripts/afdian-webhook-relay.js 和 scripts/afdian-webhook-scf.js
  webhook-enabled: false
  # 商品映射：爱发电方案标题 → 执行的命令
  # 玩家在爱发电赞助时，系统根据方案标题匹配并执行对应命令
  # 标题支持模糊匹配（包含即命中）
  plans:
    - title: "VIP月卡"
      commands:
        - "lp user %player% parent add vip 30d"
    - title: "钻石礼包"
      commands:
        - "give %player% diamond 64"

# 消息模板在 messages_zh.yml / messages_en.yml 中配置
# 自定义请在数据目录创建 messages.yml 覆盖
""".trimIndent()
    }
}

data class AfdianPlanConfig(
    /** 爱发电方案标题（模糊匹配） */
    val title: String,
    /** 匹配后执行的命令 */
    val commands: List<String>
)
