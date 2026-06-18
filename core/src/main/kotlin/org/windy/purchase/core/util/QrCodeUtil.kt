package org.windy.purchase.core.util

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * 二维码工具 — 基于 ZXing。
 */
object QrCodeUtil {

    /**
     * 生成二维码 PNG 图片字节。
     * @param content 二维码内容（URL 等）
     * @param size 图片尺寸（像素）
     */
    fun generatePng(content: String, size: Int = 256): ByteArray {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1
        )
        val matrix: BitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val image = java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_RGB)
        for (x in 0 until size) {
            for (y in 0 until size) {
                image.setRGB(x, y, if (matrix[x, y]) 0x000000 else 0xFFFFFF)
            }
        }
        val baos = ByteArrayOutputStream()
        ImageIO.write(image, "PNG", baos)
        return baos.toByteArray()
    }

    /**
     * 生成终端友好的 ASCII 二维码（可选，用于控制台调试）。
     */
    fun toAscii(content: String): String {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
            EncodeHintType.MARGIN to 1
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints)
        val width = matrix.width
        val height = matrix.height
        val sb = StringBuilder()
        for (y in 0 until height step 2) {
            for (x in 0 until width) {
                val top = matrix[x, y]
                val bottom = y + 1 < height && matrix[x, y + 1]
                sb.append(
                    when {
                        top && bottom -> "█"
                        top -> "▀"
                        bottom -> "▄"
                        else -> " "
                    }
                )
            }
            sb.appendLine()
        }
        return sb.toString()
    }
}
