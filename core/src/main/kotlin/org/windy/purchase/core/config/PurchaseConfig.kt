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
