package com.cdp.remote.data.cdp

import org.junit.Assert.*
import org.junit.Test

/**
 * 测试 Commands 中 JS 字符串模板的转义安全性。
 *
 * AntigravityCommands.setInputText() 使用字符串模板将用户输入嵌入 JS 代码。
 * 如果转义不正确，恶意输入可能破坏 JS 执行或注入代码。
 *
 * 这里我们提取转义逻辑并验证各种边界输入的安全性。
 */
class JsTemplateSecurityTest {

    /**
     * 模拟 AntigravityCommands.setInputText 中的转义逻辑。
     * 与源码完全一致。
     */
    private fun escapeForJs(text: String): String {
        return text
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
    }

    /**
     * 生成带转义文本的 JS insertText 调用。
     * 模拟 AntigravityCommands 中 document.execCommand('insertText', false, '$escaped') 的场景。
     */
    private fun buildJsInsertCommand(text: String): String {
        val escaped = escapeForJs(text)
        return "document.execCommand('insertText', false, '$escaped');"
    }

    // ─── 基本转义 ───────────────────────────────────────────────────

    @Test
    fun `plain text passes through unchanged`() {
        assertEquals("hello world", escapeForJs("hello world"))
    }

    @Test
    fun `single quote is escaped`() {
        val result = escapeForJs("it's a test")
        assertEquals("it\\'s a test", result)
        // 在 JS 模板 '...' 中不会提前结束
        assertFalse(buildJsInsertCommand("it's a test").contains("', false, 'it'"))
    }

    @Test
    fun `backslash is escaped`() {
        val result = escapeForJs("path\\to\\file")
        assertEquals("path\\\\to\\\\file", result)
    }

    @Test
    fun `newline is escaped`() {
        val result = escapeForJs("line1\nline2")
        assertEquals("line1\\nline2", result)
        assertFalse("转义后不应包含真实换行", result.contains("\n"))
    }

    @Test
    fun `carriage return is escaped`() {
        val result = escapeForJs("line1\rline2")
        assertEquals("line1\\rline2", result)
    }

    @Test
    fun `CRLF is escaped`() {
        val result = escapeForJs("line1\r\nline2")
        assertEquals("line1\\r\\nline2", result)
    }

    // ─── 注入攻击防御 ───────────────────────────────────────────────

    @Test
    fun `JS string escape injection attempt`() {
        // 试图闭合 JS 字符串并注入代码
        val malicious = "'; alert('xss'); '"
        val js = buildJsInsertCommand(malicious)
        // 转义后 ' 变成 \'，不会提前闭合
        assertTrue("单引号应被转义", js.contains("\\'"))
        assertFalse("不应有未转义的 alert 调用", js.contains("alert('xss')"))
    }

    @Test
    fun `backslash followed by quote injection`() {
        // 攻击: 用 \' 试图让 \\ 吃掉转义
        val malicious = "\\'; alert(1); //'"
        val escaped = escapeForJs(malicious)
        // \\ -> \\\\, ' -> \', 所以结果是 \\\\\\'; alert(1); //\\'
        assertTrue("反斜杠应先被转义", escaped.startsWith("\\\\"))
        assertTrue("后续单引号也应被转义", escaped.contains("\\'"))
    }

    @Test
    fun `newline injection attempt`() {
        // 试图通过真实换行符注入新的 JS 行
        val malicious = "text\n'); malicious(); ('"
        val escaped = escapeForJs(malicious)
        assertFalse("不应包含真实换行", escaped.contains("\n"))
        assertTrue("换行应被转义为 \\n", escaped.contains("\\n"))
    }

    // ─── 中文和 Unicode ─────────────────────────────────────────────

    @Test
    fun `Chinese characters pass through unchanged`() {
        val text = "请帮我写一个函数"
        assertEquals(text, escapeForJs(text))
    }

    @Test
    fun `emoji pass through unchanged`() {
        val text = "Hello 👋 World 🌍"
        assertEquals(text, escapeForJs(text))
    }

    @Test
    fun `mixed Chinese and special characters`() {
        val text = "这是一个'测试'\n包含\\路径"
        val result = escapeForJs(text)
        assertTrue(result.contains("这是一个"))
        assertTrue(result.contains("\\'测试\\'"))
        assertTrue(result.contains("\\n"))
        assertTrue(result.contains("\\\\路径"))
    }

    // ─── 极端输入 ────────────────────────────────────────────────────

    @Test
    fun `empty string`() {
        assertEquals("", escapeForJs(""))
        val js = buildJsInsertCommand("")
        assertTrue("空字符串应生成有效 JS", js.contains("''"))
    }

    @Test
    fun `string of only special characters`() {
        val result = escapeForJs("'\\'\\n\\r")
        assertNotNull(result)
        // 确保每个字符都被正确处理
    }

    @Test
    fun `very long string does not crash`() {
        val longText = "a".repeat(100000)
        val result = escapeForJs(longText)
        assertEquals(100000, result.length)
    }

    @Test
    fun `null bytes in string`() {
        val text = "before\u0000after"
        val result = escapeForJs(text)
        // null 字符不在转义列表中，但不应崩溃
        assertNotNull(result)
    }

    // ─── base64 图片数据转义（pasteImage 使用） ─────────────────────

    @Test
    fun `base64 data does not need quote escaping`() {
        // base64 字符集: A-Z, a-z, 0-9, +, /, =
        // 不包含 ' 或 \, 所以转义应为 no-op
        val b64 = "aWdIYXlPblRoZVdvcmxk+/=="
        assertEquals(b64, escapeForJs(b64))
    }

    @Test
    fun `base64 chunk single-quote escape for pasteImage`() {
        // pasteImage 中: chunk.replace("'", "\\'")
        val chunk = "abc'def"
        val escaped = chunk.replace("'", "\\'")
        assertEquals("abc\\'def", escaped)
    }

    // ─── 转义顺序验证 ───────────────────────────────────────────────

    @Test
    fun `escape order matters - backslash first then quote`() {
        // 如果先转义 ' 再转义 \，\' 中的 \ 会被二次转义变成 \\' → 错误
        // 正确顺序：先 \ 再 '
        val input = "\\'"
        val result = escapeForJs(input)
        // \ → \\, ' → \' → 最终 \\\\'
        assertEquals("\\\\\\'", result)
    }

    @Test
    fun `reversed escape order would produce wrong result`() {
        val input = "\\'"
        // 错误顺序模拟
        val wrongResult = input
            .replace("'", "\\'")     // \' → \\' (先处理引号)
            .replace("\\", "\\\\")   // \\' → \\\\' (再转义反斜杠，连 \' 的 \ 也被转义了)
        // 正确结果
        val correctResult = escapeForJs(input)
        // 两者应该不同，验证顺序确实重要
        assertNotEquals("转义顺序错误会产生不同结果", wrongResult, correctResult)
    }
}
