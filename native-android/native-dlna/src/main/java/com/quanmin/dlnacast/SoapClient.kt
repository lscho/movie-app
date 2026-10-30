package com.quanmin.dlnacast

import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * 极简 HTTP / SOAP 客户端。
 *
 * 只用 JDK 的 HttpURLConnection，不引 OkHttp —— 投屏控制报文都是几十字节的 SOAP，
 * 不需要连接池，少一个依赖就少一份在离线SDK工程里编译失败的风险。
 */
internal object SoapClient {

    /** GET 一个文本资源（设备描述 XML）；失败返回 null */
    fun fetchText(url: String, timeoutMs: Int = 3000): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                setRequestProperty("Content-Type", "text/xml; charset=utf-8")
            }
            val code = conn.responseCode
            if (code !in 200..299) return null
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: Exception) {
            null
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 发一个 SOAP 动作。
     *
     * @param controlUrl 设备描述里解析出的绝对控制地址
     * @param serviceType 形如 urn:schemas-upnp-org:service:AVTransport:1
     * @param action 形如 SetAVTransportURI
     * @param innerBodyXml 动作参数（不含 <u:Action> 外壳）
     * @return 响应体文本；HTTP 非 2xx 返回 null
     */
    fun post(
        controlUrl: String,
        serviceType: String,
        action: String,
        innerBodyXml: String,
        timeoutMs: Int = 4000
    ): String? {
        val body = buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
            append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
            append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">")
            append("<s:Body>")
            append("<u:").append(action).append(" xmlns:u=\"").append(serviceType).append("\">")
            append(innerBodyXml)
            append("</u:").append(action).append(">")
            append("</s:Body></s:Envelope>")
        }
        val bytes = body.toByteArray(Charsets.UTF_8)

        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(controlUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                doOutput = true
                setRequestProperty("Content-Type", "text/xml; charset=utf-8")
                // SOAPAction 的引号必须保留，部分电视端解析器严格要求
                setRequestProperty("SOAPAction", "\"$serviceType#$action\"")
                setRequestProperty("Connection", "close")
            }
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            if (code !in 200..299) return null
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: Exception) {
            null
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    /** 从 SOAP 响应体里取指定标签的文本，取不到返回 null */
    fun tagValue(xml: String, tag: String): String? {
        // 响应可能带命名空间前缀，如 <u:CurrentTransportState>
        val regex = Regex("<(?:[A-Za-z0-9_]+:)?${Regex.escape(tag)}[^>]*>(.*?)</(?:[A-Za-z0-9_]+:)?${Regex.escape(tag)}>", RegexOption.DOT_MATCHES_ALL)
        return regex.find(xml)?.groupValues?.getOrNull(1)?.trim()
    }
}

/** 设备描述 XML 解析：取 friendlyName / UDN / AVTransport、RenderingControl 的 controlURL */
internal object DeviceDescriptionParser {

    private const val SERVICE_AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport"
    private const val SERVICE_RENDERING = "urn:schemas-upnp-org:service:RenderingControl"

    fun parse(xml: String, location: String): DeviceProfile? {
        return try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                // 关掉外部实体，避免设备返回恶意 XML 时被 XXE
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
            val doc = factory.newDocumentBuilder()
                .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
            val root = doc.documentElement ?: return null

            val name = firstText(root, "friendlyName") ?: ""
            val udn = firstText(root, "UDN") ?: ""

            var avTransport = ""
            var rendering = ""

            val services = root.getElementsByTagName("service")
            for (i in 0 until services.length) {
                val node = services.item(i)
                if (node !is Element) continue
                val type = childText(node, "serviceType") ?: continue
                val controlUrl = childText(node, "controlURL") ?: continue
                val absolute = resolveUrl(location, controlUrl)
                if (type.startsWith(SERVICE_AV_TRANSPORT)) {
                    avTransport = absolute
                } else if (type.startsWith(SERVICE_RENDERING)) {
                    rendering = absolute
                }
            }

            DeviceProfile(name = name, udn = udn, avTransportControlUrl = avTransport, renderingControlUrl = rendering)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 把设备描述里的相对 controlURL 拼成绝对地址。
     *
     * 这是最容易踩的坑：绝大多数电视返回的是 `/upnp/control/AVTransport1` 这种以 / 开头的路径，
     * 必须基于 LOCATION 的 scheme + host + port 拼接；少数返回 `control/xxx` 相对当前目录。
     */
    private fun resolveUrl(location: String, controlUrl: String): String {
        val target = controlUrl.trim()
        if (target.startsWith("http://", ignoreCase = true) || target.startsWith("https://", ignoreCase = true)) {
            return target
        }
        return try {
            val base = URL(location)
            val port = if (base.port > 0) ":${base.port}" else ""
            val prefix = "${base.protocol}://${base.host}$port"
            if (target.startsWith("/")) prefix + target else "$prefix/$target"
        } catch (_: Exception) {
            target
        }
    }

    private fun firstText(parent: Element, tag: String): String? {
        val nodes = parent.getElementsByTagName(tag)
        return if (nodes.length > 0) nodes.item(0)?.textContent?.trim() else null
    }

    private fun childText(parent: Element, tag: String): String? {
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val n = children.item(i)
            if (n is Element && n.nodeName == tag) {
                return n.textContent?.trim()
            }
        }
        return null
    }
}
