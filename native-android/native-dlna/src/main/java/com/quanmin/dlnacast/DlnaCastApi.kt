package com.quanmin.dlnacast

import android.content.Context

/**
 * 面向 UTS 的静态门面。
 *
 * 设计要点（踩坑后的结论）：
 * 1) **所有跨界返回值一律用 JSON 字符串或基本类型**，绝不返回自定义 Kotlin 类或 Array。
 *    UTS 编译器对 Kotlin data class 的属性访问、Kotlin Array 的 .length 映射不可靠，
 *    直接跨边界会得到一堆 Unresolved reference。JSON 字符串是唯一零风险的通道。
 * 2) 所有 `*Blocking` 方法同步阻塞，UTS 层负责切到 io 线程再调用。
 */
class DlnaCastApi private constructor() {

    companion object {

        @JvmStatic
        fun init(context: Context) {
            DlnaCast.init(context)
        }

        // ------------------------------------------------------------ 扫描

        /** 同步扫描，返回 JSON 数组：[{"name":..,"udn":..,"location":..}, ...] */
        @JvmStatic
        fun scanBlocking(timeoutMs: Long): String {
            return Json.devices(DlnaCast.scanBlocking(timeoutMs))
        }

        @JvmStatic
        fun stopScan() {
            DlnaCast.stopScan()
        }

        /** 最近一次扫描的诊断信息（网卡/收包数/候选数），搜不到设备时用于定位 */
        @JvmStatic
        fun scanDiagnostics(): String = DlnaCast.scanDiagnostics()

        // ------------------------------------------------------------ 连接

        /** @return JSON {"errCode":0,"errMsg":"ok"} */
        @JvmStatic
        fun connectBlocking(name: String, udn: String, location: String): String {
            return try {
                Json.result(DlnaCast.connectBlocking(DlnaDevice(name, udn, location)))
            } catch (e: Throwable) {
                Json.result(CastResult.fail(DlnaCast.ERR_DEVICE_UNREACHABLE, e.message ?: "连接异常"))
            }
        }

        @JvmStatic
        fun disconnect() {
            DlnaCast.disconnect()
        }

        @JvmStatic
        fun isConnected(): Boolean = DlnaCast.isConnected()

        /** 当前设备名，未连接返回空串 */
        @JvmStatic
        fun getConnectedName(): String = DlnaCast.connectedName()

        // ------------------------------------------------------------ 播放

        @JvmStatic
        fun playBlocking(url: String, title: String): String {
            return try {
                Json.result(DlnaCast.playBlocking(url, title))
            } catch (e: Throwable) {
                Json.result(CastResult.fail(DlnaCast.ERR_DEVICE_UNREACHABLE, e.message ?: "投屏异常"))
            }
        }

        @JvmStatic
        fun pauseBlocking(): String = safe { DlnaCast.pauseBlocking() }

        @JvmStatic
        fun resumeBlocking(): String = safe { DlnaCast.resumeBlocking() }

        @JvmStatic
        fun stopBlocking(): String = safe { DlnaCast.stopBlocking() }

        @JvmStatic
        fun seekBlocking(positionSeconds: Int): String = safe { DlnaCast.seekBlocking(positionSeconds) }

        @JvmStatic
        fun setVolumeBlocking(volume: Int): String = safe { DlnaCast.setVolumeBlocking(volume) }

        /** 播放状态 JSON：{"state":..,"position":..,"duration":..,"volume":..,"muted":..} */
        @JvmStatic
        fun statusBlocking(): String {
            return try {
                Json.status(DlnaCast.statusBlocking())
            } catch (e: Throwable) {
                Json.status(CastStatus.UNKNOWN)
            }
        }

        /** 统一把「可能抛异常的 CastResult 动作」包成 JSON */
        private inline fun safe(block: () -> CastResult): String {
            return try {
                Json.result(block())
            } catch (e: Throwable) {
                Json.result(CastResult.fail(DlnaCast.ERR_DEVICE_UNREACHABLE, e.message ?: "控制指令异常"))
            }
        }
    }
}
