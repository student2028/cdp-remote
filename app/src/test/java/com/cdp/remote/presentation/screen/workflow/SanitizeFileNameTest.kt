package com.cdp.remote.presentation.screen.workflow

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * sanitizeFileName 的行为必须与 Relay 端 JS 的
 * `filename.replace(/[^a-zA-Z0-9._\-]/g, '_')` 完全一致。
 */
class SanitizeFileNameTest {

    @Test
    fun asciiNameUnchanged() {
        assertEquals("screenshot.png", sanitizeFileName("screenshot.png"))
    }

    @Test
    fun chineseReplacedWithUnderscore() {
        assertEquals("__.png", sanitizeFileName("截图.png"))
    }

    @Test
    fun spacesAndParenthesesReplaced() {
        assertEquals("my_file__1_.txt", sanitizeFileName("my file (1).txt"))
    }

    @Test
    fun plusSignReplaced() {
        assertEquals("file_name.doc", sanitizeFileName("file+name.doc"))
    }

    @Test
    fun hyphenUnderscoreDotPreserved() {
        assertEquals("a-b_c.d", sanitizeFileName("a-b_c.d"))
    }

    @Test
    fun digitsPreserved() {
        assertEquals("image_123.jpg", sanitizeFileName("image_123.jpg"))
    }

    @Test
    fun emptyStringReturnsEmpty() {
        assertEquals("", sanitizeFileName(""))
    }

    @Test
    fun specialCharsMixed() {
        // 连字符 `-` 是合法字符，只有空格和括号被替换
        assertEquals("photo_2024-01-01__1_.jpeg", sanitizeFileName("photo 2024-01-01 (1).jpeg"))
    }
}
