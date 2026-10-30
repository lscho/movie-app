import com.quanmin.dlnacast.*
import java.io.File

/**
 * DLNA 核心逻辑离线验证（普通 JVM，不需要 Android 设备）。
 *
 * 被测对象是**生产代码**（DlnaHelpers / SoapClient / DeviceDescriptionParser / Json），
 * 不是副本 —— 这里验证通过就等于 APK 里跑的是同一份逻辑。
 */
fun main() {
    val fixturePath = System.getenv("DLNA_FIXTURE") ?: "/tmp/parsecheck/Sample.xml"
    val xml = File(fixturePath).readText()
    var pass = 0
    var fail = 0
    fun ok(name: String, cond: Boolean, actual: String = "") {
        if (cond) {
            println("  PASS  $name")
            pass++
        } else {
            println("  FAIL  $name  [$actual]")
            fail++
        }
    }

    println("\n== 1. 设备描述解析（带命名空间的真实电视 XML）==")
    val p = DeviceDescriptionParser.parse(xml, "http://192.168.31.50:49152/description.xml")
    ok("能解析出 profile", p != null)
    if (p != null) {
        ok("friendlyName 中文正确", p.name == "客厅的小米电视4A", p.name)
        ok("UDN 解析正确", p.udn.startsWith("uuid:"), p.udn)
        ok(
            "AVTransport controlURL 绝对化",
            p.avTransportControlUrl == "http://192.168.31.50:49152/upnp/control/AVTransport1",
            p.avTransportControlUrl
        )
        ok(
            "RenderingControl controlURL 绝对化",
            p.renderingControlUrl == "http://192.168.31.50:49152/upnp/control/RenderingControl1",
            p.renderingControlUrl
        )
    }

    println("\n== 2. controlURL 相对路径拼接（最容易踩的坑）==")
    val p2 = DeviceDescriptionParser.parse(xml, "http://192.168.1.10:8080/desc.xml")
    ok(
        "按 host:port 拼接",
        p2?.avTransportControlUrl == "http://192.168.1.10:8080/upnp/control/AVTransport1",
        p2?.avTransportControlUrl ?: "null"
    )

    println("\n== 3. SOAP 响应标签提取（带命名空间前缀）==")
    val soap = """<?xml version="1.0"?>
        <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
        <s:Body><u:GetPositionInfoResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
        <TrackDuration>00:45:12</TrackDuration><RelTime>00:12:34</RelTime>
        </u:GetPositionInfoResponse></s:Body></s:Envelope>""".trimIndent()
    ok("取 RelTime", SoapClient.tagValue(soap, "RelTime") == "00:12:34", SoapClient.tagValue(soap, "RelTime") ?: "null")
    ok(
        "取 TrackDuration",
        SoapClient.tagValue(soap, "TrackDuration") == "00:45:12",
        SoapClient.tagValue(soap, "TrackDuration") ?: "null"
    )

    println("\n== 4. SOAP 错误码提取 ==")
    val err = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>
        <s:Fault><detail><UPnPError><errorCode>716</errorCode><errorDescription>Resource not found</errorDescription></UPnPError>
        </detail></s:Fault></s:Body></s:Envelope>"""
    ok("能识别 716（资源找不到）", SoapClient.tagValue(err, "errorCode") == "716", SoapClient.tagValue(err, "errorCode") ?: "null")

    println("\n== 5. 无 AVTransport 的设备应被识别出来 ==")
    val noAv = """<root><device><friendlyName>只有渲染的老盒子</friendlyName><UDN>uuid:abc</UDN>
        <serviceList><service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
        <controlURL>/rc</controlURL></service></serviceList></device></root>"""
    val p3 = DeviceDescriptionParser.parse(noAv, "http://1.2.3.4/d.xml")
    ok("解析成功但 AVTransport 为空", p3 != null && p3.avTransportControlUrl.isEmpty(), p3?.avTransportControlUrl ?: "null")

    println("\n== 6. 畸形 XML 不应崩溃 ==")
    ok("垃圾输入返回 null", DeviceDescriptionParser.parse("<not xml", "http://1.2.3.4/d.xml") == null)

    println("\n== 7. JSON 序列化转义（中文 + 引号 + 换行）==")
    val dev = DlnaDevice(name = "客厅\"电视\"\n换行", udn = "uuid:1", location = "http://h/d.xml")
    val j = Json.devices(listOf(dev))
    println("     JSON: $j")
    ok("包含 devices 键", j.startsWith("{\"devices\":["))
    ok("引号已转义", j.contains("\\\"电视\\\""))
    ok("换行已转义", j.contains("\\n"))

    println("\n== 8. CastResult / CastStatus 序列化 ==")
    ok("CastResult ok", Json.result(CastResult.OK) == "{\"errCode\":0,\"errMsg\":\"ok\"}", Json.result(CastResult.OK))
    val st = Json.status(CastStatus("PLAYING", 125, 3600, 42, false))
    println("     status: $st")
    ok("状态字段齐全", st.contains("\"state\":\"PLAYING\"") && st.contains("\"position\":125") && st.contains("\"duration\":3600"))

    println("\n== 9. 时间格式往返 ==")
    ok("3661s -> 01:01:01", DlnaHelpers.formatHms(3661) == "01:01:01", DlnaHelpers.formatHms(3661))
    ok("00:12:34 -> 754s", DlnaHelpers.parseHms("00:12:34") == 754, DlnaHelpers.parseHms("00:12:34").toString())
    ok("NOT_IMPLEMENTED -> 0", DlnaHelpers.parseHms("NOT_IMPLEMENTED") == 0)
    ok("空串 -> 0", DlnaHelpers.parseHms("") == 0)
    ok("带小数秒 00:00:02.44", DlnaHelpers.parseHms("00:00:02.44") == 2, DlnaHelpers.parseHms("00:00:02.44").toString())

    println("\n== 10. DIDL-Lite 元数据生成 ==")
    val d1 = DlnaHelpers.buildDidl("三体 第一集", "https://vod2.maowushi.com/a/index.m3u8")
    ok("DC title 正确", d1.contains("<dc:title>三体 第一集</dc:title>"))
    ok("videoItem 类型", d1.contains("object.item.videoItem"))
    ok("m3u8 用 HLS mime", d1.contains("application/vnd.apple.mpegurl"), d1.take(200))
    val d2 = DlnaHelpers.buildDidl("t", "https://x/y.mp4")
    ok("mp4 用 video/mp4", d2.contains("video/mp4"))
    val d3 = DlnaHelpers.buildDidl("a&b", "https://x/y.m3u8")
    ok("标题特殊字符转义", d3.contains("a&amp;b"), d3.take(160))

    println("\n== 11. 投屏 URL 校验 ==")
    ok("拒绝 file://", !DlnaHelpers.isCastableUrl("file:///sdcard/a.mp4"))
    ok("拒绝空地址", !DlnaHelpers.isCastableUrl(""))
    ok("拒绝 blob:", !DlnaHelpers.isCastableUrl("blob:https://x/y"))
    ok("接受 https", DlnaHelpers.isCastableUrl("https://x/y.m3u8"))
    ok("接受 http", DlnaHelpers.isCastableUrl("http://1.2.3.4/a.m3u8"))

    println("\n=====================================")
    println("结果: $pass 通过, $fail 失败")
    if (fail > 0) kotlin.system.exitProcess(1)
}
