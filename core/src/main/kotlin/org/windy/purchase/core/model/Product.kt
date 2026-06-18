package org.windy.purchase.core.model

/**
 * 可购买商品。
 * price 单位为「分」（整数）。
 */
data class Product(
    val id: String,
    val name: String,
    /** 价格，单位：分 */
    val price: Int,
    val description: String = "",
    /** 购买成功后执行的命令（支持 %player% 占位符） */
    val commands: List<String> = emptyList(),
    /** 购买所需权限节点；null 表示所有人都能买 */
    val permission: String? = null,
    /** 两次购买间冷却秒数；0 = 无冷却 */
    val cooldown: Int = 0
)
