package com.cdp.remote.data.cdp

import com.google.gson.JsonObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 可编程的 Fake CdpClient — 用于不需要真实 WebSocket 的测试。
 *
 * 与 MockCdpServer 的区别：
 * - MockCdpServer 通过真实 WebSocket 测试完整管道（集成测试）
 * - FakeCdpClient 直接控制返回值，跳过网络层（ViewModel 单元测试）
 *
 * 用法示例：
 * ```
 * val fake = FakeCdpClient()
 * fake.evaluateResult = CdpResult.Success("ok")
 * val vm = ChatViewModel(fake)
 * ```
 */
class FakeCdpClient : ICdpClient {

    // ─── 状态控制 ────────────────────────────────────────────────────

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState
    override val isConnected: Boolean get() = _connectionState.value == ConnectionState.CONNECTED

    /** 手动设置连接状态 */
    fun setConnectionState(state: ConnectionState) {
        _connectionState.value = state
    }

    // ─── 调用记录 ────────────────────────────────────────────────────

    /** 所有 evaluate 调用的参数记录 */
    val evaluateCalls = mutableListOf<String>()

    /** 所有 call 的方法名记录 */
    val callHistory = mutableListOf<String>()

    /** 所有 dispatchKeyEvent 的记录 */
    val keyEvents = mutableListOf<Pair<String, String>>()

    /** 所有 dispatchMouseEvent 的记录 */
    val mouseEvents = mutableListOf<Triple<String, Double, Double>>()

    // ─── 可编程返回值 ────────────────────────────────────────────────

    /** connectDirect 的返回值 */
    var connectResult: CdpResult<Unit> = CdpResult.Success(Unit)

    /** evaluate 的返回值 */
    var evaluateResult: CdpResult<String?> = CdpResult.Success("ok")

    /** call 的返回值 */
    var callResult: CdpResult<JsonObject> = CdpResult.Success(JsonObject())

    /** captureScreenshot 的返回值 */
    var screenshotResult: CdpResult<ByteArray> = CdpResult.Success(byteArrayOf(1, 2, 3))

    /** discoverPages 的返回值 */
    var discoverPagesResult: CdpResult<List<CdpPage>> = CdpResult.Success(emptyList())

    /** 自定义 evaluate 响应（根据表达式内容动态返回） */
    var evaluateHandler: ((String) -> CdpResult<String?>)? = null

    // ─── 连接管理 ────────────────────────────────────────────────────

    override suspend fun discoverPages(host: HostInfo): CdpResult<List<CdpPage>> {
        callHistory.add("discoverPages")
        return discoverPagesResult
    }

    override suspend fun connect(host: HostInfo, page: CdpPage): CdpResult<Unit> {
        callHistory.add("connect")
        if (connectResult.isSuccess) _connectionState.value = ConnectionState.CONNECTED
        return connectResult
    }

    override suspend fun connectDirect(wsUrl: String): CdpResult<Unit> {
        callHistory.add("connectDirect")
        if (connectResult.isSuccess) _connectionState.value = ConnectionState.CONNECTED
        return connectResult
    }

    override suspend fun connectToWorkbench(host: HostInfo): CdpResult<CdpPage> {
        callHistory.add("connectToWorkbench")
        return if (connectResult.isSuccess) {
            _connectionState.value = ConnectionState.CONNECTED
            val page = discoverPagesResult.getOrNull()?.firstOrNull()
                ?: CdpPage("1", "page", "Workbench", "file:///workbench.html", "ws://test/1")
            CdpResult.Success(page)
        } else {
            CdpResult.Error("连接失败")
        }
    }

    override fun disconnect() {
        callHistory.add("disconnect")
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    // ─── CDP 调用 ────────────────────────────────────────────────────

    override suspend fun call(method: String, params: JsonObject): CdpResult<JsonObject> {
        callHistory.add(method)
        return callResult
    }

    override suspend fun evaluate(expression: String, awaitPromise: Boolean): CdpResult<String?> {
        evaluateCalls.add(expression)
        callHistory.add("Runtime.evaluate")
        return evaluateHandler?.invoke(expression) ?: evaluateResult
    }

    override suspend fun dispatchKeyEvent(type: String, key: String): CdpResult<JsonObject> {
        keyEvents.add(type to key)
        callHistory.add("Input.dispatchKeyEvent")
        return callResult
    }

    override suspend fun insertText(text: String): CdpResult<JsonObject> {
        callHistory.add("Input.insertText")
        return callResult
    }

    override suspend fun dispatchMouseEvent(
        type: String, x: Double, y: Double,
        button: String, clickCount: Int
    ): CdpResult<JsonObject> {
        mouseEvents.add(Triple(type, x, y))
        callHistory.add("Input.dispatchMouseEvent")
        return callResult
    }

    override suspend fun dispatchScrollEvent(deltaY: Double, x: Double, y: Double): CdpResult<JsonObject> {
        callHistory.add("Input.dispatchMouseEvent.scroll")
        return callResult
    }

    override suspend fun captureScreenshot(quality: Int, clip: JsonObject?): CdpResult<ByteArray> {
        callHistory.add("Page.captureScreenshot")
        return screenshotResult
    }

    // ─── 事件监听 ────────────────────────────────────────────────────

    private val listeners = mutableMapOf<String, MutableList<(JsonObject) -> Unit>>()

    override fun addEventListener(event: String, listener: (JsonObject) -> Unit) {
        listeners.getOrPut(event) { mutableListOf() }.add(listener)
    }

    override fun removeEventListeners(event: String) {
        listeners.remove(event)
    }

    /** 模拟触发 CDP 事件 */
    fun fireEvent(event: String, params: JsonObject = JsonObject()) {
        listeners[event]?.forEach { it(params) }
    }

    // ─── 工具方法 ────────────────────────────────────────────────────

    /** 重置所有记录和状态 */
    fun reset() {
        evaluateCalls.clear()
        callHistory.clear()
        keyEvents.clear()
        mouseEvents.clear()
        _connectionState.value = ConnectionState.DISCONNECTED
        connectResult = CdpResult.Success(Unit)
        evaluateResult = CdpResult.Success("ok")
        callResult = CdpResult.Success(JsonObject())
        evaluateHandler = null
    }
}
