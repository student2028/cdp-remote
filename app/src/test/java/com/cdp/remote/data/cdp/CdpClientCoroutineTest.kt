package com.cdp.remote.data.cdp

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * CdpClient 的协程行为与状态管理测试。
 *
 * 不依赖真实 WebSocket 连接，测试可在 JVM 上验证的逻辑：
 * - 初始状态与断开
 * - 未连接时调用返回错误
 * - 事件监听器管理
 * - 连接状态 StateFlow 行为
 */
class CdpClientCoroutineTest {

    // ─── 未连接时的行为 ─────────────────────────────────────────────

    @Test
    fun `call returns error when not connected`() = runTest {
        val client = CdpClient(this)
        val result = client.call("Runtime.evaluate")
        assertTrue("未连接时 call 应返回 Error", result is CdpResult.Error)
        assertEquals("未连接", (result as CdpResult.Error).message)
    }

    @Test
    fun `evaluate returns error when not connected`() = runTest {
        val client = CdpClient(this)
        val result = client.evaluate("1+1")
        assertTrue("未连接时 evaluate 应返回 Error", result is CdpResult.Error)
    }

    @Test
    fun `dispatchKeyEvent returns error when not connected`() = runTest {
        val client = CdpClient(this)
        val result = client.dispatchKeyEvent("keyDown", "Enter")
        assertTrue(result is CdpResult.Error)
    }

    @Test
    fun `dispatchMouseEvent returns error when not connected`() = runTest {
        val client = CdpClient(this)
        val result = client.dispatchMouseEvent("mousePressed", 100.0, 200.0)
        assertTrue(result is CdpResult.Error)
    }

    @Test
    fun `dispatchScrollEvent returns error when not connected`() = runTest {
        val client = CdpClient(this)
        val result = client.dispatchScrollEvent(-300.0)
        assertTrue(result is CdpResult.Error)
    }

    @Test
    fun `captureScreenshot returns error when not connected`() = runTest {
        val client = CdpClient(this)
        val result = client.captureScreenshot()
        assertTrue(result is CdpResult.Error)
    }

    // ─── 断开连接 ───────────────────────────────────────────────────

    @Test
    fun `disconnect sets state to DISCONNECTED`() {
        val client = CdpClient()
        client.disconnect()
        assertEquals(ConnectionState.DISCONNECTED, client.connectionState.value)
    }

    @Test
    fun `multiple disconnect calls are safe`() {
        val client = CdpClient()
        client.disconnect()
        client.disconnect()
        client.disconnect()
        assertEquals(ConnectionState.DISCONNECTED, client.connectionState.value)
    }

    // ─── 事件监听器 ─────────────────────────────────────────────────

    @Test
    fun `addEventListener and removeEventListeners`() {
        val client = CdpClient()
        var called = false
        client.addEventListener("Page.loadEventFired") { called = true }

        // 移除监听器
        client.removeEventListeners("Page.loadEventFired")

        // 移除后不应有残留（通过不崩溃验证）
        client.removeEventListeners("Page.loadEventFired")  // 二次移除应安全
        assertFalse(called)
    }

    @Test
    fun `removeEventListeners for non-existent event is safe`() {
        val client = CdpClient()
        // 不应抛异常
        client.removeEventListeners("NonExistent.event")
    }

    // ─── connectionState 是 StateFlow ───────────────────────────────

    @Test
    fun `connectionState is a StateFlow with initial DISCONNECTED`() {
        val client = CdpClient()
        val flow = client.connectionState
        assertNotNull(flow)
        assertEquals(ConnectionState.DISCONNECTED, flow.value)
    }

    // ─── isConnected 属性 ────────────────────────────────────────────

    @Test
    fun `isConnected is false initially`() {
        val client = CdpClient()
        assertFalse(client.isConnected)
    }

    @Test
    fun `isConnected is false after disconnect`() {
        val client = CdpClient()
        client.disconnect()
        assertFalse(client.isConnected)
    }

}
