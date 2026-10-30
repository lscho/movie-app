package com.quanmin.dlnacast

/**
 * 极简 JSON 序列化（只覆盖投屏用到的结构）。
 *
 * 为什么手写而不引 fastjson/gson：
 * 跨 UTS 边界的数据量大不了几百字节，手写 30 行足够；而多引一个序列化库就多一份
 * 在离线SDK工程里版本冲突的风险。这里只做「对象 → 字符串」单向序列化。
 */
internal object Json {

    fun escape(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '/' -> sb.append("/")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (c < ' ') {
                        sb.append("\\u").append(String.format("%04x", c.code))
                    } else {
                        sb.append(c)
                    }
                }
            }
        }
        return sb.toString()
    }

    fun str(value: String): String = "\"" + escape(value) + "\""

    /** 结果对象：{"errCode":0,"errMsg":"ok"} */
    fun result(r: CastResult): String =
        "{\"errCode\":${r.errCode},\"errMsg\":" + str(r.errMsg) + "}"

    /** 设备对象 */
    fun device(d: DlnaDevice): String =
        "{\"name\":" + str(d.name) +
            ",\"udn\":" + str(d.udn) +
            ",\"location\":" + str(d.location) + "}"

    /**
     * 设备列表，包在对象里：{"devices":[...]}
     * 不用裸数组是刻意的——UTS 侧 JSON.parseObject + getArray 的组合最稳，
     * 裸数组要额外判断 JSON.parse 的返回类型，容易踩 UTS 类型推导的坑。
     */
    fun devices(list: List<DlnaDevice>): String =
        "{\"devices\":[" + list.joinToString(separator = ",") { device(it) } + "]}"

    /** 播放状态对象 */
    fun status(s: CastStatus): String =
        "{\"state\":" + str(s.state) +
            ",\"position\":${s.position}" +
            ",\"duration\":${s.duration}" +
            ",\"volume\":${s.volume}" +
            ",\"muted\":${s.muted}}"
}
