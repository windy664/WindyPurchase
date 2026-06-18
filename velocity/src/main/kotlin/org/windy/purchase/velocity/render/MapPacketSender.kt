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

    /**
     * 地图二维码发包参数。不同 MC 版本包 ID / 物品网络 ID 不同，做成可配置而非写死。
     * @param supportedProtocols 客户端协议号白名单；非空时仅对名单内版本发包，避免向其他版本注入错误包 ID 导致连接错乱。空=不校验。
     */
    data class MapQrParams(
        val setSlotPacketId: Int = 0x14,
        val mapDataPacketId: Int = 0x27,
        val filledMapItemId: Int = 358,
        val restoreDelaySeconds: Long = 5,
        val supportedProtocols: Set<Int> = emptySet()
    )

    /**
     * 向玩家客户端注入地图二维码。
     * @return true=已发送；false=跳过/失败（调用方应回退到聊天链接，通常已先行发送）。
     */
    fun sendMapQrCode(player: Player, qrContent: String, mapId: Int = 9999, params: MapQrParams = MapQrParams()): Boolean {
        // 协议版本守卫：名单非空且玩家版本不在内，则跳过，避免错误包 ID 打乱客户端连接
        if (params.supportedProtocols.isNotEmpty()) {
            val proto = player.protocolVersion.protocol
            if (proto !in params.supportedProtocols) {
                log.warn("玩家 {} 协议号 {} 不在地图二维码支持列表，跳过（回退聊天链接）", player.username, proto)
                return false
            }
        }
        return try {
            val channel = getNettyChannel(player) ?: run {
                log.warn("拿不到 {} 的 Netty Channel，跳过地图二维码（回退聊天链接）", player.username)
                return false
            }

            val setSlotBuf = buildSetContainerSlot(mapId, params.setSlotPacketId, params.filledMapItemId)
            val mapDataBuf = buildMapData(mapId, qrContent, params.mapDataPacketId)
            channel.write(setSlotBuf)
            channel.write(mapDataBuf)
            channel.flush()

            // 延迟恢复原物品
            val restoreBuf = buildSetContainerSlot(-1, params.setSlotPacketId, params.filledMapItemId)
            channel.eventLoop().schedule({
                try { channel.writeAndFlush(restoreBuf) } catch (_: Exception) {}
            }, params.restoreDelaySeconds, java.util.concurrent.TimeUnit.SECONDS)

            log.info("Map QR sent to {} (mapId={})", player.username, mapId)
            true
        } catch (e: Exception) {
            log.warn("地图二维码发送失败 {}（回退聊天链接）: {}", player.username, e.message)
            false
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

    private fun buildSetContainerSlot(mapId: Int, packetId: Int, filledMapItemId: Int): ByteBuf {
        val buf = Unpooled.buffer()
        buf.writeVarInt(packetId)
        buf.writeByte(0)     // window ID = player inventory
        buf.writeVarInt(0)   // state ID
        buf.writeShort(36)   // slot = main hand in inventory window
        if (mapId < 0) {
            buf.writeBoolean(false)  // empty item
        } else {
            buf.writeBoolean(true)
            buf.writeVarInt(filledMapItemId)
            buf.writeByte(1)  // count
            // components: map_id
            buf.writeVarInt(1)  // set count
            buf.writeVarInt(7)  // map_id component type
            buf.writeVarInt(mapId)
            buf.writeVarInt(0)  // remove count
        }
        return buf
    }

    private fun buildMapData(mapId: Int, qrContent: String, packetId: Int): ByteBuf {
        val pixels = generateQrPixels(qrContent, 128)
        val buf = Unpooled.buffer()
        buf.writeVarInt(packetId)
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
