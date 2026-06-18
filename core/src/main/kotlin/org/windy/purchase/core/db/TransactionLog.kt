package org.windy.purchase.core.db

import org.windy.purchase.core.model.Order
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date

/**
 * 交易日志 — 追加写入 + 自动轮转。
 * 文件超过 maxSizeBytes 时重命名为 .old，重新创建。
 */
class TransactionLog(
    private val logFile: File,
    private val maxSizeBytes: Long = 10 * 1024 * 1024  // 10MB
) {
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
    private val lock = Object()
    @Volatile private var writer: BufferedWriter = openWriter()

    fun logCreate(order: Order) = write("CREATE", order.orderId, order.playerName, order.amount, order.paymentMethod, order.productId ?: "direct")
    fun logPaid(order: Order) = write("PAID", order.orderId, order.playerName, order.amount, order.paymentMethod, "")
    fun logExpired(order: Order) = write("EXPIRED", order.orderId, order.playerName, order.amount, order.paymentMethod, "")
    fun logCancelled(order: Order) = write("CANCELLED", order.orderId, order.playerName, order.amount, order.paymentMethod, "")
    fun logRefund(orderId: String, playerName: String, amount: Int, method: String) = write("REFUND", orderId, playerName, amount, method, "")

    private fun write(event: String, orderId: String, player: String, amountFen: Int, method: String, detail: String) {
        val line = "[${dateFormat.format(Date())}] $event | $orderId | $player | ${String.format("%.2f", amountFen / 100.0)} | $method | $detail"
        synchronized(lock) {
            // 轮转检查
            if (logFile.length() > maxSizeBytes) {
                rotate()
            }
            writer.write(line)
            writer.newLine()
            writer.flush()
        }
    }

    private fun rotate() {
        runCatching { writer.close() }
        val oldFile = File(logFile.parent, logFile.nameWithoutExtension + ".old" + "." + logFile.extension)
        runCatching {
            if (oldFile.exists()) oldFile.delete()
            logFile.renameTo(oldFile)
        }
        writer = openWriter()
    }

    private fun openWriter(): BufferedWriter {
        logFile.parentFile?.mkdirs()
        return BufferedWriter(FileWriter(logFile, true))
    }

    fun close() {
        synchronized(lock) { runCatching { writer.close() } }
    }
}
