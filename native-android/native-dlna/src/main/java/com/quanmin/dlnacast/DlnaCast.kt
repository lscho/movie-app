package com.quanmin.dlnacast

import android.content.Context
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * DLNA 投屏对外门面。
 *
 * 关键设计（对应「手机侧可以随时关闭」的诉求）：
 * - 投屏是**电视自己拉流**，手机只下发 URL 和控制指令，视频数据完全不经过手机。
 * - 因此本类**不持有 WakeLock、不起前台服务、不注册常驻通知**；
 *   App 息屏 / 切后台 / 被系统回收都不影响电视播放，只是会失去控制能力。
 * - 设备控制地址（UDN + controlURL）会持久化，下次进 App 可自动重连，无需重新扫描。
 *
 * 所有公开方法都是「发起异步任务 + 回调」，调用方在主线程回调。
 */
object DlnaCast {

    const val ERR_OK = 0
    const val ERR_NOT_CONNECTED = 1001
    const val ERR_NETWORK = 1002
    const val ERR_DEVICE_UNREACHABLE = 1003
    const val ERR_PARAM = 1004
    const val ERR_STATE = 1005

    private const val SERVICE_AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
    private const val SERVICE_RENDERING = "urn:schemas-upnp-org:service:RenderingControl:1"

    private val io = Executors.newFixedThreadPool(3) { r ->
        Thread(r, "uni-dlna-cast").apply { isDaemon = true }
    }

    @Volatile
    private var appContext: Context? = null

    private val currentProfile = AtomicReference<DeviceProfile?>(null)
    private val currentDevice = AtomicReference<DlnaDevice?>(null)

    @Volatile
    private var discovery: SsdpDiscovery? = null

    /** 最近一次扫描的诊断信息（搜不到设备时用于定位是网卡、组播还是设备问题） */
    @Volatile
    private var lastScanDiag: String = ""

    @Volatile
    private var lastVolume: Int = 30

    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
    }

    // -------------------------------------------------------------- 扫描

    /** 扫描局域网设备。阻塞调用，请在 io 线程执行（UTS 层已包好）。 */
    fun scanBlocking(timeoutMs: Long): List<DlnaDevice> {
        val ctx = appContext ?: run {
            lastScanDiag = "appContext not initialized"
            return emptyList()
        }
        val d = SsdpDiscovery(ctx)
        discovery = d
        return try {
            val result = d.scan(timeoutMs)
            lastScanDiag = d.diagnostics().let { diag ->
                if (result.isEmpty()) diag else "found=${result.size}; $diag"
            }
            result
        } catch (e: Throwable) {
            lastScanDiag = "scan exception: " + (e.message ?: e.javaClass.simpleName)
            emptyList()
        } finally {
            discovery = null
        }
    }

    /** 最近一次扫描的诊断信息，供 UI/日志排查 */
    fun scanDiagnostics(): String = lastScanDiag

    fun stopScan() {
        discovery?.cancel()
        discovery = null
    }

    // -------------------------------------------------------------- 连接

    /** 连接指定设备：拉描述 XML、解析 AVTransport/RenderingControl 控制地址。 */
    fun connectBlocking(device: DlnaDevice): CastResult {
        val xml = SoapClient.fetchText(device.location, timeoutMs = 4000)
            ?: return CastResult.fail(ERR_DEVICE_UNREACHABLE, "无法读取设备描述，请确认设备已开机并在同一局域网")
        val profile = DeviceDescriptionParser.parse(xml, device.location)
            ?: return CastResult.fail(ERR_DEVICE_UNREACHABLE, "设备描述解析失败")
        if (profile.avTransportControlUrl.isEmpty()) {
            return CastResult.fail(ERR_DEVICE_UNREACHABLE, "该设备不支持 AVTransport，无法投屏")
        }
        currentProfile.set(profile)
        currentDevice.set(device)
        // 连上后先读一次音量，遥控面板初始值才不是瞎猜的
        readVolume()?.let { lastVolume = it }
        return CastResult.OK
    }

    fun disconnect() {
        currentProfile.set(null)
        currentDevice.set(null)
    }

    fun isConnected(): Boolean = currentProfile.get() != null

    fun connectedName(): String = currentDevice.get()?.name ?: ""

    // -------------------------------------------------------------- 播放

    /** 投屏播放：下发 SetAVTransportURI + Play。 */
    fun playBlocking(url: String, title: String): CastResult {
        if (url.isBlank()) return CastResult.fail(ERR_PARAM, "播放地址为空")
        if (!DlnaHelpers.isCastableUrl(url)) {
            return CastResult.fail(ERR_PARAM, "投屏地址必须是 http/https，本地文件无法投屏")
        }
        val profile = currentProfile.get() ?: return CastResult.fail(ERR_NOT_CONNECTED, "未连接设备")

        val metaData = buildDidl(title, url)
        val inner = buildString {
            append("<InstanceID>0</InstanceID>")
            append("<CurrentURI>").append(DlnaHelpers.escapeXml(url)).append("</CurrentURI>")
            append("<CurrentURIMetaData>").append(DlnaHelpers.escapeXml(metaData)).append("</CurrentURIMetaData>")
        }
        val setResult = SoapClient.post(profile.avTransportControlUrl, SERVICE_AV_TRANSPORT, "SetAVTransportURI", inner)
            ?: return CastResult.fail(ERR_DEVICE_UNREACHABLE, "SetAVTransportURI 无响应，投屏失败")

        // 部分设备 SetAVTransportURI 会返回错误码（如 716 资源找不到），但 HTTP 仍是 200，
        // 因此要同时看 SOAP 里的 errorCode
        SoapClient.tagValue(setResult, "errorCode")?.toIntOrNull()?.let { code ->
            if (code != 0) return CastResult.fail(ERR_STATE, "设备拒绝了该地址（错误码 $code），可能电视不支持此视频格式")
        }

        val playInner = "<InstanceID>0</InstanceID><Speed>1</Speed>"
        SoapClient.post(profile.avTransportControlUrl, SERVICE_AV_TRANSPORT, "Play", playInner)
            ?: return CastResult.fail(ERR_DEVICE_UNREACHABLE, "Play 无响应")

        return CastResult.OK
    }

    fun pauseBlocking(): CastResult = avAction("Pause", "<InstanceID>0</InstanceID>")

    fun resumeBlocking(): CastResult = avAction("Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")

    fun stopBlocking(): CastResult = avAction("Stop", "<InstanceID>0</InstanceID>")

    fun seekBlocking(positionSeconds: Int): CastResult {
        if (positionSeconds < 0) return CastResult.fail(ERR_PARAM, "进度不能为负")
        val hhmmss = formatHms(positionSeconds)
        return avAction(
            "Seek",
            "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>$hhmmss</Target>"
        )
    }

    /** 设置音量 0-100（RenderingControl） */
    fun setVolumeBlocking(volume: Int): CastResult {
        val profile = currentProfile.get() ?: return CastResult.fail(ERR_NOT_CONNECTED, "未连接设备")
        if (profile.renderingControlUrl.isEmpty()) return CastResult.fail(ERR_STATE, "该设备不支持音量控制")
        val v = volume.coerceIn(0, 100)
        val inner = "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>$v</DesiredVolume>"
        val resp = SoapClient.post(profile.renderingControlUrl, SERVICE_RENDERING, "SetVolume", inner)
            ?: return CastResult.fail(ERR_DEVICE_UNREACHABLE, "SetVolume 无响应")
        SoapClient.tagValue(resp, "errorCode")?.toIntOrNull()?.takeIf { it != 0 }?.let {
            return CastResult.fail(ERR_STATE, "设备拒绝设置音量（错误码 $it）")
        }
        lastVolume = v
        return CastResult.OK
    }

    // -------------------------------------------------------------- 状态

    /** 读播放状态 + 进度 + 音量。任何一项失败都不抛异常，返回 UNKNOWN 兜底。 */
    fun statusBlocking(): CastStatus {
        val profile = currentProfile.get() ?: return CastStatus.UNKNOWN

        var state = "UNKNOWN"
        var position = 0
        var duration = 0

        val infoResp = SoapClient.post(
            profile.avTransportControlUrl, SERVICE_AV_TRANSPORT,
            "GetTransportInfo", "<InstanceID>0</InstanceID>", timeoutMs = 2500
        )
        if (infoResp != null) {
            state = SoapClient.tagValue(infoResp, "CurrentTransportState") ?: "UNKNOWN"
        }

        val posResp = SoapClient.post(
            profile.avTransportControlUrl, SERVICE_AV_TRANSPORT,
            "GetPositionInfo", "<InstanceID>0</InstanceID>", timeoutMs = 2500
        )
        if (posResp != null) {
            position = parseHms(SoapClient.tagValue(posResp, "RelTime") ?: "")
            duration = parseHms(SoapClient.tagValue(posResp, "TrackDuration") ?: "")
        }

        val volume = readVolume() ?: lastVolume
        return CastStatus(state = state, position = position, duration = duration, volume = volume, muted = false)
    }

    // -------------------------------------------------------------- 内部

    private fun readVolume(): Int? {
        val profile = currentProfile.get() ?: return null
        if (profile.renderingControlUrl.isEmpty()) return null
        val resp = SoapClient.post(
            profile.renderingControlUrl, SERVICE_RENDERING,
            "GetVolume", "<InstanceID>0</InstanceID><Channel>Master</Channel>", timeoutMs = 2500
        ) ?: return null
        return SoapClient.tagValue(resp, "CurrentVolume")?.toIntOrNull()
    }

    private fun avAction(action: String, inner: String): CastResult {
        val profile = currentProfile.get() ?: return CastResult.fail(ERR_NOT_CONNECTED, "未连接设备")
        val resp = SoapClient.post(profile.avTransportControlUrl, SERVICE_AV_TRANSPORT, action, inner)
            ?: return CastResult.fail(ERR_DEVICE_UNREACHABLE, "$action 无响应")
        SoapClient.tagValue(resp, "errorCode")?.toIntOrNull()?.takeIf { it != 0 }?.let {
            return CastResult.fail(ERR_STATE, "$action 失败（错误码 $it）")
        }
        return CastResult.OK
    }

    private fun buildDidl(title: String, url: String): String = DlnaHelpers.buildDidl(title, url)

    private fun formatHms(totalSeconds: Int): String = DlnaHelpers.formatHms(totalSeconds)

    private fun parseHms(value: String): Int = DlnaHelpers.parseHms(value)

    /** 供 UTS 层切换线程用 */
    internal fun runOnIo(task: () -> Unit) {
        io.execute(task)
    }

    internal fun shutdown() {
        io.shutdownNow()
    }
}
