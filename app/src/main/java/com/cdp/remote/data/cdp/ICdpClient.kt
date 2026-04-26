package com.cdp.remote.data.cdp

import com.google.gson.JsonObject
import kotlinx.coroutines.flow.StateFlow

/**
 * CdpClient 的抽象接口 — 所有 CDP 操作的契约。
 *
 * 引入该接口的目的：
 * 1. 让 AntigravityCommands / ChatViewModel 依赖抽象而非具体实现
 * 2. 测试时可注入 FakeCdpClient，无需真实 WebSocket
 * 3. 未来可扩展不同传输层（如直连 CDP、通过 HTTP 代理等）
 */
interface ICdpClient {

    // ─── 状态 ────────────────────────────────────────────────────────

    /** 当前连接状态的可观察 Flow */
    val connectionState: StateFlow<ConnectionState>

    /** 是否已连接 */
    val isConnected: Boolean

    // ─── 连接管理 ────────────────────────────────────────────────────

    /** 发现指定主机上的可调试页面 */
    suspend fun discoverPages(host: HostInfo): CdpResult<List<CdpPage>>

    /** 连接到指定页面 */
    suspend fun connect(host: HostInfo, page: CdpPage): CdpResult<Unit>

    /** 通过 WebSocket URL 直连 */
    suspend fun connectDirect(wsUrl: String): CdpResult<Unit>

    /** 连接到主机的 workbench 页面 */
    suspend fun connectToWorkbench(host: HostInfo): CdpResult<CdpPage>

    /** 断开连接 */
    fun disconnect()

    // ─── CDP 调用 ────────────────────────────────────────────────────

    /** 发送原始 CDP 方法调用 */
    suspend fun call(method: String, params: JsonObject = JsonObject()): CdpResult<JsonObject>

    /** 执行 JS 表达式 */
    suspend fun evaluate(expression: String, awaitPromise: Boolean = false): CdpResult<String?>

    // ─── 输入事件 ────────────────────────────────────────────────────

    /** 派发键盘事件 */
    suspend fun dispatchKeyEvent(type: String, key: String): CdpResult<JsonObject>

    /** 插入文本 (最适合输入法输入) */
    suspend fun insertText(text: String): CdpResult<JsonObject>

    /** 派发鼠标事件 */
    suspend fun dispatchMouseEvent(
        type: String, x: Double, y: Double,
        button: String = "left", clickCount: Int = 1
    ): CdpResult<JsonObject>

    /** 派发滚轮事件 */
    suspend fun dispatchScrollEvent(deltaY: Double, x: Double = 0.0, y: Double = 0.0): CdpResult<JsonObject>

    // ─── 截图 ────────────────────────────────────────────────────────

    /** 截取页面截图 */
    suspend fun captureScreenshot(quality: Int = 80, clip: JsonObject? = null): CdpResult<ByteArray>

    // ─── 事件监听 ────────────────────────────────────────────────────

    /** 注册 CDP 事件监听器 */
    fun addEventListener(event: String, listener: (JsonObject) -> Unit)

    /** 移除指定事件的所有监听器 */
    fun removeEventListeners(event: String)
}
