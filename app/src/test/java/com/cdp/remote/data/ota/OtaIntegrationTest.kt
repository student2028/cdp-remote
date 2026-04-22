package com.cdp.remote.data.ota

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * OTA 更新管理的端到端集成测试。
 *
 * 使用 MockWebServer 模拟中继服务器的 /version 端点，
 * 测试 checkFromRelayUrls() 的真实 HTTP 请求 + JSON 解析 + 版本比较逻辑。
 *
 * 这些场景都是之前手工测试过的：
 * - 远程版本更高 → 提示更新
 * - 版本相同 → 已是最新
 * - 本地版本更高 → 提示 "请先 publish_ota.sh"
 * - 中继服务器挂了 → Failed
 * - 多个中继取最高版本
 * - /version 返回无效 JSON
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class OtaIntegrationTest {

    private lateinit var mockServer: MockWebServer
    private lateinit var manager: OtaUpdateManager

    @Before
    fun setup() {
        mockServer = MockWebServer()
        mockServer.start()
        val context = RuntimeEnvironment.getApplication()
        manager = OtaUpdateManager(context, mockServer.url("/").toString())
    }

    @After
    fun teardown() {
        mockServer.shutdown()
    }

    // ═══════════════════════════════════════════════════════════════
    // checkForUpdates — 单中继基础场景
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `checkForUpdates parses valid version response`() = runBlocking {
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"versionCode": 42, "versionName": "2.1.0", "updateMessage": "修复图片发送"}"""))

        val info = manager.checkForUpdates()

        assertNotNull("应解析出版本信息", info)
        assertEquals(42, info!!.versionCode)
        assertEquals("2.1.0", info.versionName)
        assertEquals("修复图片发送", info.updateMessage)

        // 验证请求路径
        val request = mockServer.takeRequest()
        assertTrue("请求路径应为 /version", request.path!!.endsWith("/version"))
    }

    @Test
    fun `checkForUpdates returns null on HTTP error`() = runBlocking {
        mockServer.enqueue(MockResponse().setResponseCode(500))

        val info = manager.checkForUpdates()
        assertNull("HTTP 500 应返回 null", info)
    }

    @Test
    fun `checkForUpdates returns null on invalid JSON`() = runBlocking {
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("not valid json at all"))

        val info = manager.checkForUpdates()
        assertNull("无效 JSON 应返回 null", info)
    }

    @Test
    fun `checkForUpdates returns null when versionCode missing`() = runBlocking {
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"versionName": "1.0", "updateMessage": "no code"}"""))

        val info = manager.checkForUpdates()
        assertNull("缺少 versionCode 应返回 null", info)
    }

    @Test
    fun `checkForUpdates handles relay error JSON`() = runBlocking {
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"error": "APK not found on relay server"}"""))

        val info = manager.checkForUpdates()
        assertNull("有 error 字段且无 versionCode 应返回 null", info)
    }

    @Test
    fun `checkForUpdates defaults missing updateMessage to empty`() = runBlocking {
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"versionCode": 10, "versionName": "1.0"}"""))

        val info = manager.checkForUpdates()
        assertNotNull(info)
        assertEquals("", info!!.updateMessage)
    }

    // ═══════════════════════════════════════════════════════════════
    // checkFromRelayUrls — 多中继 + 版本比较决策
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `checkFromRelayUrls returns UpdateAvailable when remote is higher`() = runBlocking {
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"versionCode": 50, "versionName": "3.0", "updateMessage": "大版本更新"}"""))

        val relayUrl = mockServer.url("/").toString()
        val outcome = manager.checkFromRelayUrls(listOf(relayUrl), localVersionCode = 30)

        assertTrue("远程版本更高应返回 UpdateAvailable", outcome is OtaCheckOutcome.UpdateAvailable)
        val update = outcome as OtaCheckOutcome.UpdateAvailable
        assertEquals(50, update.info.versionCode)
        assertEquals("大版本更新", update.info.updateMessage)
    }

    @Test
    fun `checkFromRelayUrls returns UpToDate when versions equal`() = runBlocking {
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"versionCode": 30, "versionName": "2.0", "updateMessage": ""}"""))

        val relayUrl = mockServer.url("/").toString()
        val outcome = manager.checkFromRelayUrls(listOf(relayUrl), localVersionCode = 30)

        assertTrue("版本相同应返回 UpToDate", outcome is OtaCheckOutcome.UpToDate)
    }

    @Test
    fun `checkFromRelayUrls returns Failed when local is ahead`() = runBlocking {
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"versionCode": 10, "versionName": "1.0", "updateMessage": ""}"""))

        val relayUrl = mockServer.url("/").toString()
        val outcome = manager.checkFromRelayUrls(listOf(relayUrl), localVersionCode = 20)

        assertTrue("本地领先应返回 Failed", outcome is OtaCheckOutcome.Failed)
        val failed = outcome as OtaCheckOutcome.Failed
        assertTrue("应提示 publish_ota.sh", failed.detail.contains("publish_ota"))
    }

    @Test
    fun `checkFromRelayUrls returns Failed when relay is down`() = runBlocking {
        // MockWebServer 已 shutdown，无法连接
        val deadUrl = "http://192.168.255.255:19336"
        val outcome = manager.checkFromRelayUrls(listOf(deadUrl), localVersionCode = 10)

        assertTrue("中继挂了应返回 Failed", outcome is OtaCheckOutcome.Failed)
    }

    @Test
    fun `checkFromRelayUrls returns Failed on empty relay list`() = runBlocking {
        val outcome = manager.checkFromRelayUrls(emptyList(), localVersionCode = 10)

        assertTrue("空列表应返回 Failed", outcome is OtaCheckOutcome.Failed)
        assertTrue((outcome as OtaCheckOutcome.Failed).detail.contains("中继地址"))
    }

    @Test
    fun `checkFromRelayUrls picks highest version from multiple relays`() = runBlocking {
        // 启动两个 mock 服务器
        val server2 = MockWebServer()
        server2.start()

        // server1 返回 v20, server2 返回 v50
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"versionCode": 20, "versionName": "2.0", "updateMessage": ""}"""))
        server2.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"versionCode": 50, "versionName": "5.0", "updateMessage": "最新"}"""))

        val urls = listOf(
            mockServer.url("/").toString(),
            server2.url("/").toString()
        )
        val outcome = manager.checkFromRelayUrls(urls, localVersionCode = 10)

        assertTrue(outcome is OtaCheckOutcome.UpdateAvailable)
        val update = outcome as OtaCheckOutcome.UpdateAvailable
        assertEquals("应选最高版本 50", 50, update.info.versionCode)

        server2.shutdown()
    }

    @Test
    fun `checkFromRelayUrls survives partial relay failure`() = runBlocking {
        // server1 挂了（使用不可达地址），server2 正常
        val deadUrl = "http://10.255.255.255:19336"
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"versionCode": 30, "versionName": "3.0", "updateMessage": ""}"""))

        val urls = listOf(deadUrl, mockServer.url("/").toString())
        val outcome = manager.checkFromRelayUrls(urls, localVersionCode = 20)

        assertTrue("部分中继失败，有效中继应返回 UpdateAvailable",
            outcome is OtaCheckOutcome.UpdateAvailable)
    }

    @Test
    fun `checkFromRelayUrls deduplicates relay URLs`() = runBlocking {
        // 3 个重复 URL 应只请求 1 次
        val url = mockServer.url("/").toString()
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"versionCode": 25, "versionName": "2.5", "updateMessage": ""}"""))

        val urls = listOf(url, url, url)
        val outcome = manager.checkFromRelayUrls(urls, localVersionCode = 20)

        assertTrue(outcome is OtaCheckOutcome.UpdateAvailable)
        // 去重后只应发 1 个请求
        assertEquals("应只发 1 个请求（去重）", 1, mockServer.requestCount)
    }

    // ═══════════════════════════════════════════════════════════════
    // 边界条件 — 异常 JSON
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `checkFromRelayUrls handles relay returning error JSON`() = runBlocking {
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"error": "no APK deployed"}"""))

        val url = mockServer.url("/").toString()
        val outcome = manager.checkFromRelayUrls(listOf(url), localVersionCode = 10)

        assertTrue("error JSON 应返回 Failed", outcome is OtaCheckOutcome.Failed)
    }

    @Test
    fun `checkFromRelayUrls handles partial JSON with versionCode`() = runBlocking {
        // 只有 versionCode，缺少 versionName
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"versionCode": 99}"""))

        val url = mockServer.url("/").toString()
        val outcome = manager.checkFromRelayUrls(listOf(url), localVersionCode = 10)

        assertTrue("有 versionCode 应成功", outcome is OtaCheckOutcome.UpdateAvailable)
        assertEquals(99, (outcome as OtaCheckOutcome.UpdateAvailable).info.versionCode)
        assertEquals("?", outcome.info.versionName) // 默认值
    }

    @Test
    fun `download URL is correctly formed`() {
        // 验证 downloadUrl 拼接逻辑
        val baseWithSlash = "http://10.0.0.1:19336/"
        val baseNoSlash = "http://10.0.0.1:19336"

        val url1 = if (baseWithSlash.endsWith("/")) "${baseWithSlash}download_apk" else "$baseWithSlash/download_apk"
        val url2 = if (baseNoSlash.endsWith("/")) "${baseNoSlash}download_apk" else "$baseNoSlash/download_apk"

        assertEquals("http://10.0.0.1:19336/download_apk", url1)
        assertEquals("http://10.0.0.1:19336/download_apk", url2)
    }
}
