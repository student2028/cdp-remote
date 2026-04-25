package com.cdp.remote.data

import com.cdp.remote.data.cdp.CdpPage

/**
 * IDE 应用排序偏好的全局存储（进程内单例）。
 *
 * 主页拖拽排序后通过 [setOrder] 写入，聊天页切换时通过 [sortApps] 读取。
 * 排序依据是 webSocketDebuggerUrl 列表的顺序。
 *
 * 当前为内存存储，App 重启后重置。如需持久化可在此扩展 SharedPreferences。
 */
object AppOrderStore {

    /** key = 主机地址 (如 "100.106.253.39:19336"), value = 有序 wsUrl 列表 */
    private val orderMap = mutableMapOf<String, List<String>>()

    fun setOrder(hostAddress: String, orderedWsUrls: List<String>) {
        orderMap[hostAddress] = orderedWsUrls
    }

    fun getOrder(hostAddress: String): List<String> =
        orderMap[hostAddress] ?: emptyList()

    /**
     * 按用户自定义顺序对 app 列表排序。
     * 未出现在 order 中的排在末尾，保持原始顺序。
     */
    fun sortApps(hostAddress: String, apps: List<CdpPage>): List<CdpPage> {
        val order = orderMap[hostAddress] ?: return apps
        if (order.isEmpty()) return apps
        val indexMap = order.withIndex().associate { (i, ws) -> ws to i }
        return apps.sortedBy { indexMap[it.webSocketDebuggerUrl] ?: Int.MAX_VALUE }
    }
}
