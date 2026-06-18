package org.windy.purchase.velocity.render

import com.velocitypowered.api.proxy.Player
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import org.slf4j.LoggerFactory

/**
 * Velocity 端地图二维码渲染器 — 通过反射获取 Netty Channel 发送原始协议包。
 *
 * 流程：
 * 1. 发 Set Container Slot 包 → 临时把地图物品放入玩家主手
 * 2. 发 Map Data 包 → 128×128 二维码像素
 * 3. 延迟 5 秒 → 恢复原物品
 *
 * 协议版本：1.20.2+
 */
object MapPacketSender {

    private val log = LoggerFactory.getLogger(MapPacketSender::class.java)
    private const val COLOR_BLACK: Byte = 19
    private const val COLOR_WHITE: Byte = 34
    private const val PKT_SET_CONTAINER_SLOT = 0x14
    private const val PKT_MAP_DATA = 0x27

    fun sendMapQrCode(player: Player, qrContent: String, mapId: Int = 9999) {
        try {
            val channel = getNettyChannel(player) ?: run {
                log.warn("Cannot get Netty channel for {}", player.username)
                return
            }

            val setSlotBuf = buildSetContainerSlot(mapId)
            val mapDataBuf = buildMapData(mapId, qrContent)
            channel.write(setSlotBuf)
            channel.write(mapDataBuf)
            channel.flush()

            // 5 秒后恢复
            val restoreBuf = buildSetContainerSlot(-1)
            channel.eventLoop().schedule({
                try { channel.writeAndFlush(restoreBuf) } catch (_: Exception) {}
            }, 5, java.util.concurrent.TimeUnit.SECONDS)

            log.info("Map QR sent to {} (mapId={})", player.username, mapId)
        } catch (e: Exception) {
            log.warn("Map QR failed for {}: {}", player.username, e.message)
        }
    }

    /**
     * 通过反射获取 Player 的 Netty Channel。
     * Velocity 内部链路：Player → MinecraftConnection → Channel
     */
    private fun getNettyChannel(player: Player): Channel? {
        try {
            // 遍历类层次找 connection 字段
            var clazz: Class<*>? = player.javaClass
            while (clazz != null) {
                for (field in clazz.declaredFields) {
                    val name = field.type.name.lowercase()
                    if (name.contains("connection") || name.contains("minecraftconnection")) {
                        field.isAccessible = true
                        val conn = field.get(player) ?: continue
                        // 找 connection 内的 Channel
                        var connClass: Class<*>? = conn.javaClass
                        while (connClass != null) {
                            for (cf in connClass.declaredFields) {
                                if (Channel::class.java.isAssignableFrom(cf.type)) {
                                    cf.isAccessible = true
                                    return cf.get(conn) as? Channel
                                }
                            }
                            connClass = connClass.superclass
                        }
                    }
                }
                clazz = clazz.superclass
            }
        } catch (e: Exception) {
            log.debug("Reflection failed: {}", e.message)
        }
        return null
    }

    private fun buildSetContainerSlot(mapId: Int): ByteBuf {
        val buf = Unpooled.buffer()
        buf.writeVarInt(PKT_SET_CONTAINER_SLOT)
        buf.writeByte(0)     // window ID = player inventory
        buf.writeVarInt(0)   // state ID
        buf.writeShort(36)   // slot = main hand in inventory window
        if (mapId < 0) {
            buf.writeBoolean(false)  // empty item
        } else {
            buf.writeBoolean(true)
            buf.writeVarInt(getFilledMapItemId())
            buf.writeByte(1)  // count
            // components: map_id
            buf.writeVarInt(1)  // set count
            buf.writeVarInt(7)  // map_id component type
            buf.writeVarInt(mapId)
            buf.writeVarInt(0)  // remove count
        }
        return buf
    }

    private fun buildMapData(mapId: Int, qrContent: String): ByteBuf {
        val pixels = generateQrPixels(qrContent, 128)
        val buf = Unpooled.buffer()
        buf.writeVarInt(PKT_MAP_DATA)
        buf.writeVarInt(mapId)
        buf.writeByte(0)       // scale
        buf.writeBoolean(false) // tracking
        buf.writeVarInt(0)     // icon count
        buf.writeByte(128)     // columns
        buf.writeShort(0)      // x offset
        buf.writeShort(0)      // z offset
        buf.writeByte(128)     // rows
        buf.writeVarInt(pixels.size)
        buf.writeBytes(pixels)
        return buf
    }

    private fun getFilledMapItemId(): Int = 358

    fun generateQrPixels(content: String, size: Int): ByteArray {
        val hints = mapOf(
            com.google.zxing.EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M,
            com.google.zxing.EncodeHintType.MARGIN to 1
        )
        val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(content, com.google.zxing.BarcodeFormat.QR_CODE, 0, 0, hints)
        val qrSize = matrix.width
        val scale = (size - 4) / qrSize
        val offsetX = (size - qrSize * scale) / 2
        val offsetZ = (size - qrSize * scale) / 2
        val pixels = ByteArray(size * size)
        pixels.fill(COLOR_WHITE)
        for (y in 0 until qrSize) {
            for (x in 0 until qrSize) {
                val color = if (matrix[x, y]) COLOR_BLACK else COLOR_WHITE
                for (dy in 0 until scale) {
                    for (dx in 0 until scale) {
                        val px = offsetX + x * scale + dx
                        val pz = offsetZ + y * scale + dy
                        if (px in 0 until size && pz in 0 until size) pixels[pz * size + px] = color
                    }
                }
            }
        }
        return pixels
    }

    private fun ByteBuf.writeVarInt(value: Int) {
        var v = value
        while (v and 0x7F.inv() != 0) { writeByte(v and 0x7F or 0x80); v = v ushr 7 }
        writeByte(v)
    }
}
