package org.windy.purchase.velocity.command

import com.velocitypowered.api.command.RawCommand
import net.kyori.adventure.text.Component
import org.slf4j.Logger
import org.windy.purchase.core.config.Messages
import org.windy.purchase.core.config.PurchaseConfig
import org.windy.purchase.core.db.Database
import org.windy.purchase.core.model.Product
import org.windy.purchase.core.payment.PaymentManager
import java.io.File
import java.nio.file.Path

/**
 * `/purchaseadmin` 管理命令。
 *   /purchaseadmin reload              — 重载配置
 *   /purchaseadmin stats               — 查看统计
 *   /purchaseadmin check <订单ID>       — 手动查单
 *   /purchaseadmin products             — 列出商品（含价格/命令）
 */
class PurchaseAdminCommand(
    private val manager: PaymentManager,
    private val db: Database,
    private val config: PurchaseConfig,
    private val logger: Logger,
    private val dataDirectory: Path,
    private val msg: Messages
) : RawCommand {

    override fun execute(invocation: RawCommand.Invocation) {
        val source = invocation.source()
        val args = invocation.arguments().trim().split("\\s+".toRegex()).filter { it.isNotBlank() }

        if (args.isEmpty()) {
            sendHelp(source)
            return
        }

        when (args[0].lowercase()) {
            "reload" -> handleReload(source)
            "stats" -> handleStats(source, args)
            "check" -> handleCheck(source, args)
            "products" -> handleProducts(source)
            "refund" -> handleRefund(source, args)
            "grant" -> handleGrant(source, args)
            "log" -> handleLog(source, args)
            else -> sendHelp(source)
        }
    }

    private fun handleReload(source: com.velocitypowered.api.command.CommandSource) {
        // 重载商品列表
        val productsFile = dataDirectory.resolve("products.yml").toFile()
        if (productsFile.exists()) {
            val yaml = org.yaml.snakeyaml.Yaml()
            val data = productsFile.inputStream().use { yaml.load<Map<String, Any?>>(it) } ?: emptyMap()
            @Suppress("UNCHECKED_CAST")
            val products = data["products"] as? List<Map<String, Any?>> ?: emptyList()
            for (p in products) {
                val id = p["id"]?.toString() ?: continue
                val name = p["name"]?.toString() ?: id
                val price = (p["price"] as? Number)?.toInt() ?: 0
                val desc = p["description"]?.toString() ?: ""
                @Suppress("UNCHECKED_CAST")
                val cmds = (p["commands"] as? List<String>) ?: emptyList()
                val perm = p["permission"]?.toString()
                val cool = (p["cooldown"] as? Number)?.toInt() ?: 0
                db.insertProduct(Product(id, name, price * 100, desc, cmds, perm, cool))
            }
            source.sendMessage(Component.text("§a已重载 ${products.size} 个商品。"))
        } else {
            source.sendMessage(Component.text("§cproducts.yml 不存在。"))
        }
        source.sendMessage(Component.text("§e注意: 支付配置修改需要重启服务器才能生效。"))
    }

    private fun handleStats(source: com.velocitypowered.api.command.CommandSource, args: List<String>) {
        val range = args.getOrElse(1) { "all" }
        val stats = db.getRevenue()
        val rangeLabel = when (range) {
            "today" -> "今日"
            "week" -> "本周"
            "month" -> "本月"
            else -> "全部"
        }
        val rangeAmount = when (range) {
            "today" -> stats.todayAmount
            "week" -> db.getRevenueSince(7)
            "month" -> db.getRevenueSince(30)
            else -> stats.totalAmount
        }

        source.sendMessage(Component.text("§6====== 充值统计 ($rangeLabel) ======"))
        source.sendMessage(Component.text("§f${rangeLabel}充值额: §6${String.format("%.2f", rangeAmount / 100.0)} 元"))
        source.sendMessage(Component.text("§f总充值额: §6${String.format("%.2f", stats.totalAmount / 100.0)} 元"))
        source.sendMessage(Component.text("§f总订单数: §e${stats.totalOrders}"))

        val ranking = db.getRevenueRanking(5)
        if (ranking.isNotEmpty()) {
            source.sendMessage(Component.text("§6------ 充值排行 ------"))
            ranking.forEachIndexed { i, r ->
                source.sendMessage(Component.text("§e${i + 1}. §f${r.playerName} §7- §6${String.format("%.2f", r.totalAmount / 100.0)} 元"))
            }
        }
    }

    private fun handleCheck(source: com.velocitypowered.api.command.CommandSource, args: List<String>) {
        if (args.size < 2) {
            source.sendMessage(Component.text("§c用法: /purchaseadmin check <订单ID>"))
            return
        }
        val order = manager.getOrder(args[1])
        if (order == null) {
            source.sendMessage(Component.text("§c订单不存在。"))
            return
        }
        val status = when (order.status) {
            org.windy.purchase.core.model.OrderStatus.PENDING -> "§e待支付"
            org.windy.purchase.core.model.OrderStatus.PAID -> "§a已支付"
            org.windy.purchase.core.model.OrderStatus.EXPIRED -> "§c已过期"
            org.windy.purchase.core.model.OrderStatus.CANCELLED -> "§c已取消"
        }
        source.sendMessage(Component.text("§6====== 订单详情 ======"))
        source.sendMessage(Component.text("§f订单号: ${order.orderId}"))
        source.sendMessage(Component.text("§f玩家: ${order.playerName} (${order.playerId})"))
        source.sendMessage(Component.text("§f金额: §6${String.format("%.2f", order.amount / 100.0)} 元"))
        source.sendMessage(Component.text("§f支付方式: ${order.paymentMethod}"))
        source.sendMessage(Component.text("§f状态: $status"))
        source.sendMessage(Component.text("§f创建时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(order.createdAt)}"))
        if (order.paidAt != null) {
            source.sendMessage(Component.text("§f支付时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(order.paidAt!!)}"))
        }
    }

    private fun handleProducts(source: com.velocitypowered.api.command.CommandSource) {
        val products = db.getAllProducts()
        if (products.isEmpty()) {
            source.sendMessage(Component.text("§c暂无商品。"))
            return
        }
        source.sendMessage(Component.text("§6====== 商品列表 ======"))
        for (p in products) {
            source.sendMessage(Component.text("§e${p.id} §f| §a${p.name} §f| §6${String.format("%.2f", p.price / 100.0)} 元"))
            source.sendMessage(Component.text("  §7命令: ${p.commands.joinToString(", ")}"))
            if (p.permission != null) source.sendMessage(Component.text("  §7权限: ${p.permission}"))
            if (p.cooldown > 0) source.sendMessage(Component.text("  §7冷却: ${p.cooldown}秒"))
        }
    }

    private fun handleRefund(source: com.velocitypowered.api.command.CommandSource, args: List<String>) {
        if (args.size < 2) {
            source.sendMessage(Component.text("§c用法: /purchaseadmin refund <订单ID>"))
            return
        }
        val orderId = args[1]
        val order = manager.getOrder(orderId)
        if (order == null) {
            source.sendMessage(Component.text("§c订单不存在。"))
            return
        }
        if (order.status != org.windy.purchase.core.model.OrderStatus.PAID) {
            source.sendMessage(Component.text("§c只能退款已支付的订单。"))
            return
        }

        // 尝试调支付平台退款
        val provider = manager.getProvider(order.paymentMethod)
        if (provider != null) {
            // 关闭订单（部分支付平台支持退款，此处用 closeOrder 代替）
            // 真正的退款需要各 provider 实现 refund 方法
            source.sendMessage(Component.text("§e正在处理退款..."))
        }

        // 标记订单为已取消（退款）
        db.updateOrderStatus(orderId, org.windy.purchase.core.model.OrderStatus.CANCELLED, null)
        source.sendMessage(Component.text("§a订单 $orderId 已标记为退款。"))
        source.sendMessage(Component.text("§e注意: 实际退款需要在支付平台手动操作。"))
        logger.info("Order refunded: {} by admin", orderId)
    }

    /**
     * 管理员手动补单 — 直接给玩家发放奖励（不经过支付）。
     * 用法: /purchaseadmin grant <玩家名> <金额> <命令...>
     * 示例: /purchaseadmin grant Steve 30 give %player% diamond 64
     */
    private fun handleGrant(source: com.velocitypowered.api.command.CommandSource, args: List<String>) {
        if (args.size < 4) {
            source.sendMessage(Component.text("§c用法: /purchaseadmin grant <玩家名> <金额(元)> <命令...>"))
            source.sendMessage(Component.text("§7示例: /purchaseadmin grant Steve 30 give %player% diamond 64"))
            return
        }
        val playerName = args[1]
        val amountYuan = args[2].toDoubleOrNull()
        if (amountYuan == null || amountYuan < 0) {
            source.sendMessage(Component.text("§c金额无效。")); return
        }
        val commands = args.subList(3, args.size).joinToString(" ")
        val amountFen = (amountYuan * 100).toInt()

        // 创建已支付订单（管理员补单）
        val orderId = java.util.UUID.randomUUID().toString().replace("-", "")
        val order = org.windy.purchase.core.model.Order(
            orderId = orderId,
            playerId = playerName,
            playerName = playerName,
            productId = null,
            amount = amountFen,
            paymentMethod = "admin-grant",
            status = org.windy.purchase.core.model.OrderStatus.PAID,
            commands = listOf(commands.replace("%player%", playerName))
        )
        order.paidAt = System.currentTimeMillis()
        db.insertOrder(order)
        db.updateOrderStatus(orderId, org.windy.purchase.core.model.OrderStatus.PAID, order.paidAt)

        // 执行命令
        for (cmd in order.commands) {
            source.sendMessage(Component.text("§7执行: $cmd"))
        }

        source.sendMessage(Component.text("§a已为 §f$playerName §a补单 §6${String.format("%.2f", amountYuan)} §a元 (订单: ${orderId.take(8)}...)"))
        logger.info("Admin grant: player={} amount={} commands={}", playerName, amountYuan, commands)
    }

    /**
     * 查看最近交易日志。
     * 用法: /purchaseadmin log [数量]
     */
    private fun handleLog(source: com.velocitypowered.api.command.CommandSource, args: List<String>) {
        val count = args.getOrElse(1) { "10" }.toIntOrNull() ?: 10
        val logFile = dataDirectory.resolve("transactions.log").toFile()
        if (!logFile.exists()) {
            source.sendMessage(Component.text("§7暂无交易日志。")); return
        }
        val lines = logFile.readLines().filter { !it.startsWith("#") }.takeLast(count)
        source.sendMessage(Component.text("§6====== 最近 ${lines.size} 条交易日志 ======"))
        for (line in lines) {
            source.sendMessage(Component.text("§f$line"))
        }
    }

    private fun sendHelp(source: com.velocitypowered.api.command.CommandSource) {
        source.sendMessage(Component.text("§6====== WindyPurchase Admin ======"))
        source.sendMessage(Component.text("§e/purchaseadmin reload §f- 重载商品配置"))
        source.sendMessage(Component.text("§e/purchaseadmin stats [today|week|month] §f- 充值统计"))
        source.sendMessage(Component.text("§e/purchaseadmin check <订单ID> §f- 查看订单"))
        source.sendMessage(Component.text("§e/purchaseadmin refund <订单ID> §f- 退款"))
        source.sendMessage(Component.text("§e/purchaseadmin grant <玩家> <金额> <命令> §f- 手动补单"))
        source.sendMessage(Component.text("§e/purchaseadmin log [数量] §f- 交易日志"))
        source.sendMessage(Component.text("§e/purchaseadmin products §f- 商品列表"))
    }

    override fun hasPermission(invocation: RawCommand.Invocation): Boolean =
        invocation.source().hasPermission("windypurchase.admin")
}
