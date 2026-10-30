package com.quanmin.dlnacast

/**
 * 局域网内发现的一台 DLNA 接收端（电视 / 盒子 / 智能投影）。
 *
 * @param name     展示名，如「小米电视4A」
 * @param udn      设备唯一标识，用于去重与记忆上次投屏设备
 * @param location 设备描述 XML 的地址
 */
data class DlnaDevice(
    val name: String,
    val udn: String,
    val location: String
)

/** 设备描述 XML 中解析出的服务控制信息 */
internal data class DeviceProfile(
    val name: String,
    val udn: String,
    /** AVTransport 的绝对 controlURL */
    val avTransportControlUrl: String,
    /** RenderingControl 的绝对 controlURL，可能为空 */
    val renderingControlUrl: String
)

/** 当前播放器状态 */
data class CastStatus(
    /** PLAYING / PAUSED_PLAYBACK / STOPPED / TRANSITIONING / NO_MEDIA_PRESENT / UNKNOWN */
    val state: String,
    /** 当前播放位置（秒） */
    val position: Int,
    /** 总时长（秒），未知为 0 */
    val duration: Int,
    /** 音量 0-100 */
    val volume: Int,
    val muted: Boolean
) {
    companion object {
        val UNKNOWN = CastStatus("UNKNOWN", 0, 0, 0, false)
    }
}

/** 统一回调结果，errCode == 0 表示成功 */
data class CastResult(val errCode: Int, val errMsg: String) {
    val isOk: Boolean get() = errCode == 0

    companion object {
        val OK = CastResult(0, "ok")

        fun fail(code: Int, msg: String) = CastResult(code, msg)
    }
}
