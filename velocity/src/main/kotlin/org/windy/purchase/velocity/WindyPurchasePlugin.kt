package org.windy.purchase.velocity

import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.ProxyServer
import org.slf4j.Logger
import org.windy.purchase.core.config.DailyRewardManager
import org.windy.purchase.core.config.Messages
import org.windy.purchase.core.config.PromoCodeManager
import org.windy.purchase.core.config.PurchaseConfig
import org.windy.purchase.core.db.Database
import org.windy.purchase.core.db.OfflineCommandCache
import org.windy.purchase.core.db.PlayerStatsCache
import org.windy.purchase.core.db.TransactionLog
import org.windy.purchase.core.payment.*
import org.windy.purchase.velocity.channel.PurchaseChannel
import org.windy.purchase.velocity.command.PurchaseAdminCommand
import org.windy.purchase.velocity.command.PurchaseCommand
import org.windy.purchase.velocity.http.HttpServer
import org.windy.purchase.velocity.notify.PaymentNotifier
import java.io.File
import java.nio.file.Path

@Plugin(
    id = "windypurchase",
    name = "WindyPurchase",
    version = "1.0-SNAPSHOT",
    description = "In-game payment plugin for Minecraft (Alipay / WeChat Pay)"
)
class WindyPurchasePlugin @Inject constructor(
    private val server: ProxyServer,
    private val logger: Logger,
    @DataDirectory private val dataDirectory: Path
) {
    private var db: Database? = null
    private var paymentManager: PaymentManager? = null
    private var httpServer: HttpServer? = null
    private var channel: PurchaseChannel? = null
    private var notifier: PaymentNotifier? = null
    private var afdianPoller: AfdianPoller? = null
    private var statsCache: PlayerStatsCache? = null
    private var cacheCleanup: java.util.Timer? = null
    private var txLog: TransactionLog? = null
    private var offlineCommands: OfflineCommandCache? = null

    @Subscribe
    fun onProxyInitialize(event: ProxyInitializeEvent) {
        logger.info("WindyPurchase initializing...")

        val config = runCatching { PurchaseConfig.load(dataDirectory.resolve("config.yml").toFile()) }.getOrElse {
            logger.error("Failed to load config.yml: {}", it.message); return
        }
        val messages = Messages.load(dataDirectory.toFile(), config.locale())

        val database = runCatching { Database(dataDirectory.resolve("windypurchase.db").toFile().absolutePath) }.getOrElse {
            logger.error("Failed to init database: {}", it.message); return
        }
        db = database

        val cache = PlayerStatsCache(database)
        statsCache = cache

        val manager = PaymentManager(db = database, orderTimeoutMs = config.orderTimeoutSeconds() * 1000L, pollIntervalMs = config.orderPollIntervalMs(), statsCache = cache)

        // 注册支付方式
        if (config.alipayEnabled()) manager.registerProvider(AlipayProvider(config.alipayAppId(), config.alipayPrivateKey(), config.alipayPublicKey(), config.alipayNotifyUrl(), sandbox = config.alipaySandbox()))
        if (config.wechatEnabled()) manager.registerProvider(WeChatPayProvider(config.wechatAppId(), config.wechatMchId(), config.wechatMchSerialNumber(), config.wechatApiV3Key(), config.wechatPrivateKeyPath(), config.wechatNotifyUrl()))
        if (config.paypalEnabled()) manager.registerProvider(PayPalProvider(config.paypalClientId(), config.paypalClientSecret(), config.paypalCurrency(), config.paypalSandbox(), config.paypalReturnUrl(), config.paypalCancelUrl()))

        if (config.afdianEnabled()) {
            val afdianProvider = AfdianProvider(config.afdianUserId(), config.afdianToken(), config.afdianBaseUrl())
            manager.registerProvider(afdianProvider)
            if (config.afdianPollEnabled()) {
                val poller = AfdianPoller(afdianProvider, database, config.afdianPollIntervalMs(), config.afdianPlans())
                poller.onPaymentSuccess = { order -> notifier?.onPaid(order) }
                poller.createVirtualOrder = { playerName, commands -> manager.createOrder(playerName, playerName, 0, "afdian", commands = commands) }
                poller.start()
                afdianPoller = poller
            }
        }

        if (config.mockEnabled()) {
            val mock = MockProvider(config.mockAutoConfirmMs())
            mock.onConfirm = { orderId -> manager.handleNotify(orderId, null) }
            manager.registerProvider(mock)
        }

        // 基础设施
        val transactionLog = TransactionLog(dataDirectory.resolve("transactions.log").toFile())
        txLog = transactionLog
        val offlineCache = OfflineCommandCache(dataDirectory.resolve("offline_commands.yml").toFile())
        offlineCommands = offlineCache
        val promoManager = PromoCodeManager(dataDirectory.resolve("promo_codes.yml").toFile())
        val dailyRewardConfig = runCatching {
            val defaultYml = javaClass.getResourceAsStream("/daily_reward_default.yml")?.bufferedReader()?.readText() ?: "{}"
            val yaml = org.yaml.snakeyaml.Yaml()
            @Suppress("UNCHECKED_CAST")
            DailyRewardManager.load(yaml.load(defaultYml) as? Map<String, Any?> ?: emptyMap())
        }.getOrElse { DailyRewardManager.load(emptyMap()) }
        val dailyReward = DailyRewardManager(dailyRewardConfig)

        // 定期清理
        val cleanupTimer = java.util.Timer("cleanup", true)
        cleanupTimer.schedule(object : java.util.TimerTask() {
            override fun run() { cache.cleanup(); offlineCache.flush(); promoManager.flush() }
        }, 30_000L, 30_000L)
        cacheCleanup = cleanupTimer

        val ch = PurchaseChannel(server, logger)
        ch.register()
        channel = ch

        val payNotifier = PaymentNotifier(server, config, ch, messages)
        notifier = payNotifier

        // 支付成功回调
        manager.onPaymentSuccess = { order ->
            transactionLog.logPaid(order)
            val player = server.getPlayer(order.playerId).orElse(null)
            if (player != null) {
                payNotifier.onPaid(order)
                val rewardCmds = dailyReward.checkAndClaim(order.playerId, order.playerName, order.amount)
                if (rewardCmds != null) {
                    for (cmd in rewardCmds) server.commandManager.executeAsync(server.consoleCommandSource, cmd)
                    player.sendMessage(net.kyori.adventure.text.Component.text("§6[WindyPurchase] §e每日首充奖励已发放！"))
                }
            } else {
                offlineCache.add(order.playerId, order)
                logger.info("Player {} offline, cached {} commands", order.playerName, order.commands.size)
            }
        }

        paymentManager = manager
        loadProducts(database, dataDirectory.resolve("products.yml").toFile())

        val http = HttpServer(config.httpHost(), config.httpPort(), config.httpToken(), manager, database, logger, payNotifier, cache, dataDirectory)
        http.commandExecutor = { cmd -> server.commandManager.executeAsync(server.consoleCommandSource, cmd) }
        http.start()
        httpServer = http

        // 玩家上线：执行离线命令
        server.eventManager.register(this, object {
            @com.velocitypowered.api.event.Subscribe
            fun onPlayerLogin(event: com.velocitypowered.api.event.connection.PostLoginEvent) {
                val player = event.player
                val pending = offlineCommands?.takeAll(player.uniqueId.toString()) ?: return
                if (pending.isEmpty()) return
                for (cmd in pending) for (c in cmd.commands) server.commandManager.executeAsync(server.consoleCommandSource, c.replace("%player%", player.username))
                player.sendMessage(net.kyori.adventure.text.Component.text("§a[WindyPurchase] §f你有 ${pending.size} 笔离线期间的充值奖励已发放！"))
            }
        })

        server.commandManager.register(server.commandManager.metaBuilder("purchase").plugin(this).build(), PurchaseCommand(manager, database, server, logger, payNotifier, messages, promoManager, dailyReward))
        server.commandManager.register(server.commandManager.metaBuilder("purchaseadmin").plugin(this).build(), PurchaseAdminCommand(manager, database, config, logger, dataDirectory, messages))

        org.windy.purchase.core.api.PurchaseApiProvider.register(org.windy.purchase.velocity.api.PurchaseApiImpl(manager, database, payNotifier, dataDirectory))

        logger.info("WindyPurchase started — HTTP on {}:{} — providers: {}", config.httpHost(), config.httpPort(), manager.availableProviders())
    }

    @Subscribe
    fun onPluginMessage(event: com.velocitypowered.api.event.connection.PluginMessageEvent) {
        if (event.identifier != PurchaseChannel.CHANNEL_ID) return
        val player = event.source as? com.velocitypowered.api.proxy.Player ?: return
        // 读取 payload — event.data 可能是 ByteBuf 或 LazyBuffer，统一处理
        try {
            val raw = event.data
            val payload: ByteArray = when (raw) {
                is io.netty.buffer.ByteBuf -> {
                    val bytes = ByteArray(raw.readableBytes())
                    raw.readBytes(bytes)
                    raw.release()
                    bytes
                }
                else -> {
                    // LazyBuffer 或其他类型，尝试反射获取 ByteBuf
                    val method = raw.javaClass.methods.find {
                        it.name == "getData" && io.netty.buffer.ByteBuf::class.java.isAssignableFrom(it.returnType)
                    } ?: raw.javaClass.methods.find { it.name == "toByteArray" && it.returnType == ByteArray::class.java }
                    when (val result = method?.invoke(raw)) {
                        is io.netty.buffer.ByteBuf -> {
                            val bytes = ByteArray(result.readableBytes())
                            result.readBytes(bytes)
                            result.release()
                            bytes
                        }
                        is ByteArray -> result
                        else -> return
                    }
                }
            }
            val parsed = channel?.parsePayload(payload) ?: return
            val (action, data) = parsed
            when (action) {
                PurchaseChannel.ACTION_REQUEST_QR -> {
                    val order = paymentManager?.getOrder(data)
                    if (order != null && order.status == org.windy.purchase.core.model.OrderStatus.PENDING) notifier?.onOrderCreated(order)
                }
                PurchaseChannel.ACTION_CANCEL_GESTURE -> {
                    val order = paymentManager?.getOrder(data)
                        ?: db?.getPendingOrderByPlayerName(player.username, "afdian")
                        ?: db?.getOrdersByPlayer(player.uniqueId.toString(), 10)?.find { it.status == org.windy.purchase.core.model.OrderStatus.PENDING }
                    if (order != null && order.status == org.windy.purchase.core.model.OrderStatus.PENDING) {
                        paymentManager?.getProvider(order.paymentMethod)?.closeOrder(order.orderId)
                        db?.updateOrderStatusCAS(order.orderId, org.windy.purchase.core.model.OrderStatus.PENDING, org.windy.purchase.core.model.OrderStatus.CANCELLED, null)
                        player.sendMessage(net.kyori.adventure.text.Component.text("§a订单已取消（Shift+F）。"))
                        logger.info("Order {} cancelled by gesture from player {}", order.orderId, player.username)
                    }
                }
            }
        } catch (e: Exception) {
            logger.debug("Plugin message handling error: {}", e.message)
        }
    }

    private fun loadProducts(db: Database, file: File) {
        if (!file.exists()) { file.writeText(DEFAULT_PRODUCTS); logger.info("Released default products.yml") }
        val count = org.windy.purchase.velocity.api.PurchaseApiImpl.loadProductsFromFile(db, file)
        logger.info("Loaded {} products", count)
    }

    @Subscribe
    fun onProxyShutdown(event: ProxyShutdownEvent) {
        org.windy.purchase.core.api.PurchaseApiProvider.unregister()
        httpServer?.stop()
        afdianPoller?.stop()
        cacheCleanup?.cancel()
        channel?.unregister()
        offlineCommands?.flush()
        paymentManager?.shutdown()
        statsCache?.invalidateAll()
        db?.close()
        txLog?.close()
        logger.info("WindyPurchase stopped.")
    }

    companion object {
        val DEFAULT_PRODUCTS = """
products:
  - id: "vip_monthly"
    name: "VIP月卡"
    price: 30
    description: "30天VIP权限"
    commands: ["lp user %player% parent add vip 30d"]
    permission: ""
    cooldown: 0
  - id: "diamond_pack"
    name: "钻石礼包"
    price: 10
    description: "获得64颗钻石"
    commands: ["give %player% diamond 64"]
    permission: ""
    cooldown: 60
""".trimIndent()
    }
}
