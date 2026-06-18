package org.windy.purchase.core.util

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * ASCII 二维码生成器 — 直接在聊天中显示，零依赖。
 *
 * 使用 Unicode 半块字符（▄ ▀ █）在两行内编码一行像素，
 * 压缩垂直空间，让二维码在聊天窗口中更紧凑。
 *
 * 效果示例（11x11 二维码 → 约 15 行聊天）：
 *   ████████████████
 *   ██ ▄▄▄▄▄ ██ ▄██
 *   ██ ██ ██ ██ ▄██
 *   ██ ▀▀▀▀▀ ██ ▄██
 *   ████████████████
 */
object AsciiQrCode {

    /**
     * 生成聊天友好的 ASCII 二维码。
     * @param content 二维码内容（URL 等）
     * @param compact 是否使用半块字符压缩高度（默认 true）
     * @return 每行一行文本的列表
     */
    fun generate(content: String, compact: Boolean = true): List<String> {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
            EncodeHintType.MARGIN to 1
        )
        val matrix = QRCodeWriter().encode(content, com.google.zxing.BarcodeFormat.QR_CODE, 0, 0, hints)
        val size = matrix.width

        return if (compact) {
            generateCompact(matrix, size)
        } else {
            generateNormal(matrix, size)
        }
    }

    /**
     * 紧凑模式：用 ▄ ▀ █ 压缩高度（推荐聊天用）。
     * 每两行像素合并为一行字符。
     */
    private fun generateCompact(matrix: com.google.zxing.common.BitMatrix, size: Int): List<String> {
        val lines = mutableListOf<String>()
        var y = 0
        while (y < size) {
            val sb = StringBuilder()
            for (x in 0 until size) {
                val top = matrix[x, y]
                val bottom = if (y + 1 < size) matrix[x, y + 1] else false
                sb.append(
                    when {
                        top && bottom -> "█"
                        top -> "▀"
                        bottom -> "▄"
                        else -> " "
                    }
                )
            }
            lines.add(sb.toString())
            y += 2
        }
        return lines
    }

    /**
     * 普通模式：每个像素一行（更清晰但更长）。
     */
    private fun generateNormal(matrix: com.google.zxing.common.BitMatrix, size: Int): List<String> {
        val lines = mutableListOf<String>()
        for (y in 0 until size) {
            val sb = StringBuilder()
            for (x in 0 until size) {
                sb.append(if (matrix[x, y]) "██" else "  ")
            }
            lines.add(sb.toString())
        }
        return lines
    }
}
