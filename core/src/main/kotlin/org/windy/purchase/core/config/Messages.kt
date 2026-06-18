package org.windy.purchase.core.config

import org.yaml.snakeyaml.Yaml
import java.io.File
import java.io.FileInputStream

/**
 * i18n 消息管理 — 从 messages.yml 加载，支持 %placeholder% 替换。
 * 默认中文（messages_zh.yml），config.yml 中 locale: "en" 切英文。
 */
class Messages private constructor(private val map: Map<String, Any?>) {

    // ==================== 订单 ====================
    fun orderCreated(): String = get("order.created", "§a订单已创建！请完成支付：")
    fun orderQrUrl(): String = get("order.qr-url", "§e点击打开支付: %url%")
    fun orderPaid(): String = get("order.paid", "§a支付成功！感谢充值 %amount%。")
    fun orderExpired(): String = get("order.expired", "§c订单已过期。")
    fun orderCancelled(): String = get("order.cancelled", "§c订单已取消。")
    fun orderBroadcast(): String = get("order.broadcast", "§6[充值] §e%player% §a购买了 §e%amount%§a！")

    // ==================== 支付方式文案 ====================
    fun payLabel(method: String): String = get("payment.$method.label", "§e§l[点击打开支付页面]")
    fun payHover(method: String): String = get("payment.$method.hover", "§7点击在浏览器中打开支付页面")
    fun afdianRemark(playerName: String): String = get("payment.afdian-remark", "§c§l重要：§e请在留言中填写你的游戏名 §f%player%").replace("%player%", playerName)
    fun afdianRemarkHint(): String = get("payment.afdian-remark-hint", "§7否则无法自动到账！")

    // ==================== 通用 ====================
    fun link(url: String): String = get("link", "§7链接: %url%").replace("%url%", url)
    fun orderId(id: String): String = get("order-id", "§7订单号: %id%").replace("%id%", id)

    // ==================== 命令 ====================
    fun helpTitle(): String = get("command.help.title", "§6====== WindyPurchase ======")
    fun helpList(): String = get("command.help.list", "§e/purchase list §f- 查看商品列表")
    fun helpBuy(): String = get("command.help.buy", "§e/purchase buy <商品ID> §f- 购买商品")
    fun helpPay(): String = get("command.help.pay", "§e/purchase pay <金额> §f- 直接充值")
    fun helpHistory(): String = get("command.help.history", "§e/purchase history §f- 查看订单记录")
    fun helpCheck(): String = get("command.help.check", "§e/purchase check <订单ID> §f- 查询订单状态")

    fun listTitle(): String = get("command.list.title", "§6====== 商品列表 ======")
    fun listItem(id: String, name: String, price: String, currency: String): String =
        get("command.list.item", "§e%id% §f- §a%name% §f| §6%price% %currency%")
            .replace("%id%", id).replace("%name%", name).replace("%price%", price).replace("%currency%", currency)
    fun listEmpty(): String = get("command.list.empty", "§c暂无可购买的商品。")
    fun listHint(): String = get("command.list.hint", "§7使用 /purchase buy <商品ID> 购买")

    fun buyUsage(): String = get("command.buy.usage", "§c用法: /purchase buy <商品ID> [alipay|wechat|paypal|afdian]")
    fun buyNoProduct(id: String): String = get("command.buy.no-product", "§c商品不存在: %id%").replace("%id%", id)
    fun buyNoPermission(): String = get("command.buy.no-permission", "§c你没有权限购买此商品。")
    fun creating(): String = get("command.buy.creating", "§a正在创建订单...")
    fun createFailed(): String = get("command.buy.failed", "§c创建订单失败，请稍后重试。")

    fun payUsage(): String = get("command.pay.usage", "§c用法: /purchase pay <金额> [alipay|wechat|paypal|afdian]")
    fun payInvalidAmount(min: String, currency: String): String =
        get("command.pay.invalid-amount", "§c金额无效，最小 %min% %currency%。")
            .replace("%min%", min).replace("%currency%", currency)

    fun historyTitle(): String = get("command.history.title", "§6====== 最近订单 ======")
    fun historyItem(id: String, amount: String, status: String): String =
        get("command.history.item", "§f%id% §7| §6%amount% §7| %status%")
            .replace("%id%", id).replace("%amount%", amount).replace("%status%", status)
    fun historyEmpty(): String = get("command.history.empty", "§7暂无订单记录。")

    fun checkUsage(): String = get("command.check.usage", "§c用法: /purchase check <订单ID>")
    fun checkNotFound(): String = get("command.check.not-found", "§c订单不存在。")
    fun checkTitle(): String = get("command.check.title", "§6====== 订单详情 ======")
    fun checkOrderId(id: String): String = get("command.check.order-id", "§f订单号: %id%").replace("%id%", id)
    fun checkAmount(amount: String, currency: String): String =
        get("command.check.amount", "§f金额: §6%amount% %currency%").replace("%amount%", amount).replace("%currency%", currency)
    fun checkStatus(status: String): String = get("command.check.status", "§f状态: %status%").replace("%status%", status)
    fun checkMethod(method: String): String = get("command.check.method", "§f支付方式: %method%").replace("%method%", method)
    fun checkCreated(time: String): String = get("command.check.created", "§f创建时间: %time%").replace("%time%", time)
    fun checkPaid(time: String): String = get("command.check.paid", "§f支付时间: %time%").replace("%time%", time)

    fun selectMethod(methods: String): String = get("command.select-method", "§e请选择支付方式: %methods%").replace("%methods%", methods)
    fun selectMethodHint(): String = get("command.select-method-hint", "§7用法: /purchase pay <金额> <alipay|wechat|paypal|afdian>")
    fun noMethod(): String = get("command.no-method", "§c没有可用的支付方式，请联系管理员。")
    fun playerOnly(): String = get("command.player-only", "§c此命令只能由玩家执行。")

    // ==================== 管理命令 ====================
    fun adminHelpTitle(): String = get("admin.help.title", "§6====== WindyPurchase 管理 ======")
    fun adminHelpReload(): String = get("admin.help.reload", "§e/purchaseadmin reload §f- 重载商品配置")
    fun adminHelpStats(): String = get("admin.help.stats", "§e/purchaseadmin stats §f- 充值统计")
    fun adminHelpCheck(): String = get("admin.help.check", "§e/purchaseadmin check <订单ID> §f- 查看订单详情")
    fun adminHelpProducts(): String = get("admin.help.products", "§e/purchaseadmin products §f- 商品列表")

    fun adminReloadSuccess(count: Int): String = get("admin.reload.success", "§a已重载 %count% 个商品。").replace("%count%", count.toString())
    fun adminReloadNoFile(): String = get("admin.reload.no-file", "§cproducts.yml 不存在。")
    fun adminReloadNote(): String = get("admin.reload.note", "§e注意: 支付配置修改需要重启服务器才能生效。")

    fun adminStatsTitle(): String = get("admin.stats.title", "§6====== 充值统计 ======")
    fun adminStatsTotal(amount: String, currency: String): String =
        get("admin.stats.total", "§f总充值额: §6%amount% %currency%").replace("%amount%", amount).replace("%currency%", currency)
    fun adminStatsToday(amount: String, currency: String): String =
        get("admin.stats.today", "§f今日充值: §6%amount% %currency%").replace("%amount%", amount).replace("%currency%", currency)
    fun adminStatsOrders(count: Int): String = get("admin.stats.orders", "§f总订单数: §e%count%").replace("%count%", count.toString())
    fun adminStatsRankingTitle(): String = get("admin.stats.ranking-title", "§6------ 充值排行 ------")
    fun adminStatsRankingItem(rank: Int, player: String, amount: String, currency: String): String =
        get("admin.stats.ranking-item", "§e%rank%. §f%player% §7- §6%amount% %currency%")
            .replace("%rank%", rank.toString()).replace("%player%", player).replace("%amount%", amount).replace("%currency%", currency)

    fun adminProductsTitle(): String = get("admin.products.title", "§6====== 商品列表 ======")
    fun adminProductsItem(id: String, name: String, price: String, currency: String): String =
        get("admin.products.item", "§e%id% §f| §a%name% §f| §6%price% %currency%")
            .replace("%id%", id).replace("%name%", name).replace("%price%", price).replace("%currency%", currency)
    fun adminProductsCmds(cmds: String): String = get("admin.products.commands", "  §7命令: %cmds%").replace("%cmds%", cmds)
    fun adminProductsPerm(perm: String): String = get("admin.products.permission", "  §7权限: %perm%").replace("%perm%", perm)
    fun adminProductsCooldown(sec: Int): String = get("admin.products.cooldown", "  §7冷却: %sec%秒").replace("%sec%", sec.toString())
    fun adminProductsEmpty(): String = get("admin.products.empty", "§c暂无商品。")

    // ==================== 状态 ====================
    fun statusPending(): String = get("status.pending", "§e待支付")
    fun statusPaid(): String = get("status.paid", "§a已支付")
    fun statusExpired(): String = get("status.expired", "§c已过期")
    fun statusCancelled(): String = get("status.cancelled", "§c已取消")

    fun statusText(status: String): String = when (status) {
        "PENDING" -> statusPending()
        "PAID" -> statusPaid()
        "EXPIRED" -> statusExpired()
        "CANCELLED" -> statusCancelled()
        else -> status
    }

    // ==================== 辅助 ====================
    @Suppress("UNCHECKED_CAST")
    private fun get(path: String, default: String): String {
        val parts = path.split(".")
        var current: Any? = map
        for (part in parts) {
            current = (current as? Map<String, Any?>)?.get(part) ?: return default
        }
        return (current as? String) ?: default
    }

    companion object {
        private val YAML = Yaml()

        /**
         * 加载消息文件。
         * @param dataDir 数据目录
         * @param locale 语言代码（"zh" / "en"），默认 "zh"
         */
        fun load(dataDir: File, locale: String = "zh"): Messages {
            val customFile = File(dataDir, "messages.yml")
            val localeFile = if (locale == "en") {
                Messages::class.java.getResourceAsStream("/messages_en.yml")?.use { YAML.load<Map<String, Any?>>(it) }
            } else {
                Messages::class.java.getResourceAsStream("/messages_zh.yml")?.use { YAML.load<Map<String, Any?>>(it) }
            } ?: emptyMap()

            // 用户自定义 messages.yml 覆盖默认
            val map = if (customFile.exists()) {
                val custom = FileInputStream(customFile).use { YAML.load<Map<String, Any?>>(it) } ?: emptyMap()
                deepMerge(localeFile, custom)
            } else {
                localeFile
            }

            return Messages(map)
        }

        @Suppress("UNCHECKED_CAST")
        private fun deepMerge(base: Map<String, Any?>, overlay: Map<String, Any?>): Map<String, Any?> {
            val result = base.toMutableMap()
            for ((key, value) in overlay) {
                val existing = result[key]
                if (existing is Map<*, *> && value is Map<*, *>) {
                    result[key] = deepMerge(existing as Map<String, Any?>, value as Map<String, Any?>)
                } else {
                    result[key] = value
                }
            }
            return result
        }
    }
}
