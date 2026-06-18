package org.windy.purchase.velocity.command

import com.velocitypowered.api.proxy.Player
import org.windy.purchase.core.db.Database

/**
 * 命令 Tab 补全。
 * Velocity API 3.1.1 的 RawCommand 不直接支持 suggest，
 * 通过在 execute 中检测空参数返回帮助文本实现。
 * Tab 补全功能需要迁移到 BrigadierCommand 才能完整支持。
 *
 * 当前版本：返回静态帮助文本。
 * 后续可迁移到 BrigadierCommand 获得完整 Tab 补全。
 */
object TabCompleterHelper {
    fun getSubCommands(command: String): List<String> {
        return when (command) {
            "purchase" -> listOf("list", "buy", "pay", "pending", "cancel", "history", "check", "top")
            "purchaseadmin" -> listOf("reload", "stats", "check", "refund", "grant", "log", "products")
            else -> emptyList()
        }
    }
}
