package org.windy.purchase.core.api

/**
 * PurchaseApi 全局注册表。
 *
 * WindyPurchase 插件启动时注册实例，其他插件通过 [get] 获取。
 *
 * 用法（其他 Velocity 插件）：
 * ```
 * class MyPlugin {
 *     fun onInit() {
 *         val api = PurchaseApiProvider.get()
 *         if (api != null) {
 *             val order = api.createOrder(playerId, playerName, 3000, "alipay")
 *             // ...
 *         }
 *     }
 * }
 * ```
 */
object PurchaseApiProvider {

    @Volatile
    private var instance: PurchaseApi? = null

    /**
     * 注册 API 实例（由 WindyPurchase 插件调用）。
     */
    fun register(api: PurchaseApi) {
        instance = api
    }

    /**
     * 注销（插件关闭时调用）。
     */
    fun unregister() {
        instance = null
    }

    /**
     * 获取 API 实例。返回 null 表示 WindyPurchase 未加载。
     */
    fun get(): PurchaseApi? = instance
}
