package org.windy.purchase.core.api

import org.windy.purchase.core.model.Order
import org.windy.purchase.core.model.Product

/**
 * WindyPurchase 对外 Java API。
 *
 * 两种使用方式：
 * 1. 嵌入式前端：通过 HttpServer 的 REST API 端点（fetch 调用）
 * 2. 其他 Velocity 插件：直接注入 PurchaseApi 实例调用
 *
 * 获取实例：
 *   // 在你的 Velocity 插件中
 *   val api = PurchaseApiProvider.get()  // 返回 null 如果 WindyPurchase 未加载
 *   val order = api?.createOrder(player.uniqueId.toString(), player.username, 3000, "alipay")
 */
interface PurchaseApi {

    // ==================== 订单 ====================

    /**
     * 创建订单。
     * @param playerId 玩家 UUID
     * @param playerName 玩家名
     * @param amountFen 金额（分）
     * @param paymentMethod 支付方式（alipay/wechat/paypal/afdian/mock）
     * @param productId 商品 ID（null = 自由金额充值）
     * @param commands 奖励命令列表
     * @return 订单（含支付链接），null = 创建失败
     */
    fun createOrder(playerId: String, playerName: String, amountFen: Int, paymentMethod: String, productId: String? = null, commands: List<String> = emptyList()): Order?

    /** 查询订单 */
    fun getOrder(orderId: String): Order?

    /** 玩家订单历史 */
    fun getOrdersByPlayer(playerId: String, limit: Int = 20): List<Order>

    /** 分页查询订单 */
    fun getOrders(page: Int, size: Int, status: String? = null, method: String? = null, player: String? = null): List<Order>

    /** 统计订单数 */
    fun countOrders(status: String? = null, method: String? = null, player: String? = null): Int

    /** 取消订单 */
    fun cancelOrder(orderId: String): Boolean

    // ==================== 商品 ====================

    /** 获取所有商品 */
    fun getProducts(): List<Product>

    /** 获取单个商品 */
    fun getProduct(id: String): Product?

    /** 创建/更新商品 */
    fun saveProduct(product: Product)

    /** 删除商品 */
    fun deleteProduct(id: String): Boolean

    // ==================== 统计 ====================

    /** 充值统计 */
    fun getRevenue(): RevenueData

    /** 充值排行 */
    fun getRevenueRanking(limit: Int = 10): List<PlayerRevenueData>

    /** 最近 N 天充值额（分） */
    fun getRevenueSince(days: Int): Int

    // ==================== 玩家画像 ====================

    /** 玩家充值总额（分） */
    fun getPlayerTotalFen(playerId: String): Int

    /** 玩家今日充值（分） */
    fun getPlayerTodayFen(playerId: String): Int

    /** 玩家订单数 */
    fun getPlayerOrderCount(playerId: String): Int

    // ==================== 管理 ====================

    /** 管理员补单（直接标记已支付 + 执行命令） */
    fun grantOrder(playerName: String, amountFen: Int, commands: List<String>): Order?

    /** 重载商品配置 */
    fun reloadProducts()
}

/** 充值统计 */
data class RevenueData(
    val totalAmountFen: Int,
    val todayAmountFen: Int,
    val totalOrders: Int
)

/** 充值排行 */
data class PlayerRevenueData(
    val playerId: String,
    val playerName: String,
    val totalAmountFen: Int
)
