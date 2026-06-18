package org.windy.purchase.core.model

enum class OrderStatus {
    /** 待支付 */
    PENDING,
    /** 已支付 */
    PAID,
    /** 已过期 */
    EXPIRED,
    /** 已取消 */
    CANCELLED
}
