package com.quanmin.dlnacast

/**
 * DLNA 的纯逻辑工具：时间格式、DIDL 元数据、URL 校验。
 *
 * 刻意与 [DlnaCast] 分开、且**不依赖任何 Android 类**，
 * 这样 tools/dlna-verify 能在普通 JVM 上直接编译验证这些逻辑
 * （时间/元数据格式错了在电视上表现为「投过去黑屏」，很难在设备上排查）。
 */
internal object DlnaHelpers {

    /** http/https 才能投屏；file:// 、blob: 、相册路径电视端拿不到 */
    fun isCastableUrl(url: String): Boolean =
        url.isNotBlank() &&
            (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true))

    /** 秒 → HH:MM:SS（DLNA Seek 的 REL_TIME 格式） */
    fun formatHms(totalSeconds: Int): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return "%02d:%02d:%02d".format(h, m, s)
    }

    /** HH:MM:SS → 秒；电视常见返回 NOT_IMPLEMENTED 或空串，按 0 处理 */
    fun parseHms(value: String): Int {
        if (value.isBlank() || value == "NOT_IMPLEMENTED") return 0
        val parts = value.split(":").mapNotNull { it.trim().toDoubleOrNull() }
        return when (parts.size) {
            3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]).toInt()
            2 -> (parts[0] * 60 + parts[1]).toInt()
            1 -> parts[0].toInt()
            else -> 0
        }
    }

    /** 按 URL 后缀猜 mime；部分电视靠 protocolInfo 决定用哪个解码器 */
    fun guessProtocolInfo(url: String): String {
        val lower = url.lowercase()
        val mime = when {
            lower.contains(".m3u8") -> "application/vnd.apple.mpegurl"
            lower.contains(".mpd") -> "application/dash+xml"
            lower.contains(".flv") -> "video/x-flv"
            lower.contains(".mkv") -> "video/x-matroska"
            lower.contains(".avi") -> "video/x-msvideo"
            else -> "video/mp4"
        }
        return "http-get:*:$mime:*"
    }

    fun escapeXml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    /** 投屏时下发的 DIDL-Lite 元数据（AVTransport 的 CurrentURIMetaData） */
    fun buildDidl(title: String, url: String): String {
        val safeTitle = escapeXml(title.ifBlank { "视频" })
        val protocolInfo = guessProtocolInfo(url)
        return buildString {
            append("<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" ")
            append("xmlns:dc=\"http://purl.org/dc/elements/1.1/\" ")
            append("xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">")
            append("<item id=\"0\" parentID=\"-1\" restricted=\"1\">")
            append("<dc:title>").append(safeTitle).append("</dc:title>")
            append("<upnp:class>object.item.videoItem</upnp:class>")
            append("<res protocolInfo=\"").append(protocolInfo).append("\">")
            append(escapeXml(url))
            append("</res>")
            append("</item></DIDL-Lite>")
        }
    }
}
