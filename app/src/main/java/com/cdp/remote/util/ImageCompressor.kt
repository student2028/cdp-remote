package com.cdp.remote.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/**
 * 图片压缩工具 — 专为 CDP 远程传输优化。
 *
 * 高清手机照片（3~8 MB）经 Base64 编码后膨胀 33%，通过 CDP evaluate 分块传输
 * 需要 100+ 次串行 WebSocket 往返，极易超时。本工具将图片缩放 + 压缩到合理大小，
 * 大幅减少传输次数（降至 10 次以内），从根本上解决高清图发送失败的问题。
 */
object ImageCompressor {

    /** 默认最大宽度（像素），对 IDE 输入框来说 1920px 已经足够清晰 */
    private const val DEFAULT_MAX_WIDTH = 1920

    /** 默认最大高度（像素） */
    private const val DEFAULT_MAX_HEIGHT = 1920

    /** 默认目标大小上限（字节），500 KB 足够清晰且传输快速 */
    private const val DEFAULT_MAX_SIZE_BYTES = 500 * 1024

    /** 压缩质量下限，低于此值画质损失过大 */
    private const val MIN_QUALITY = 20

    /** 初始压缩质量 */
    private const val INITIAL_QUALITY = 85

    /** 质量递减步长 */
    private const val QUALITY_STEP = 10

    /** 小于此大小的图片直接跳过压缩，避免反复解码开销 */
    private const val SKIP_THRESHOLD_BYTES = 200 * 1024

    /**
     * 压缩图片到适合远程传输的大小。
     *
     * 流程：
     * 1. 如果原始字节已经足够小（< 200 KB），直接返回
     * 2. 用 inSampleSize 快速降采样（避免 OOM）
     * 3. 精确缩放到 maxWidth/maxHeight 以内
     * 4. 二分法调整 JPEG quality 直到满足目标大小
     *
     * @param rawBytes 原始图片字节（JPEG/PNG/WebP 等 BitmapFactory 支持的格式）
     * @param maxWidth 最大宽度（默认 1920）
     * @param maxHeight 最大高度（默认 1920）
     * @param maxSizeBytes 目标文件大小上限（默认 500 KB）
     * @return 压缩后的 JPEG 字节，或原始字节（如果解码失败）
     */
    fun compressForUpload(
        rawBytes: ByteArray,
        maxWidth: Int = DEFAULT_MAX_WIDTH,
        maxHeight: Int = DEFAULT_MAX_HEIGHT,
        maxSizeBytes: Int = DEFAULT_MAX_SIZE_BYTES
    ): ByteArray {
        // 小图直接跳过
        if (rawBytes.size <= SKIP_THRESHOLD_BYTES) return rawBytes

        // 1. 只读尺寸，不分配像素
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, boundsOpts)
        val origWidth = boundsOpts.outWidth
        val origHeight = boundsOpts.outHeight
        if (origWidth <= 0 || origHeight <= 0) return rawBytes

        // 2. 计算 inSampleSize（2 的幂次降采样）
        val sampleSize = calculateSampleSize(origWidth, origHeight, maxWidth, maxHeight)
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val sampled = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOpts)
            ?: return rawBytes

        // 3. 精确缩放到目标尺寸以内
        val scaled = scaleDownIfNeeded(sampled, maxWidth, maxHeight)

        // 4. 二分法压缩质量
        var quality = INITIAL_QUALITY
        var result: ByteArray
        do {
            val baos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, baos)
            result = baos.toByteArray()
            quality -= QUALITY_STEP
        } while (result.size > maxSizeBytes && quality >= MIN_QUALITY)

        // 回收中间 Bitmap（scaled 可能是新对象）
        if (scaled !== sampled) scaled.recycle()
        sampled.recycle()

        return result
    }

    /**
     * 计算 inSampleSize：取最接近的 2 的幂次，使采样后尺寸刚好 ≤ 目标。
     */
    private fun calculateSampleSize(
        width: Int, height: Int,
        reqWidth: Int, reqHeight: Int
    ): Int {
        var inSampleSize = 1
        if (width > reqWidth || height > reqHeight) {
            val halfW = width / 2
            val halfH = height / 2
            while ((halfW / inSampleSize) >= reqWidth && (halfH / inSampleSize) >= reqHeight) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    /**
     * 如果 Bitmap 尺寸超过目标，等比缩放到目标以内。
     * 返回新 Bitmap 或原 Bitmap（如果无需缩放）。
     */
    private fun scaleDownIfNeeded(bitmap: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        if (bitmap.width <= maxWidth && bitmap.height <= maxHeight) return bitmap

        val ratioW = maxWidth.toFloat() / bitmap.width
        val ratioH = maxHeight.toFloat() / bitmap.height
        val ratio = minOf(ratioW, ratioH)

        val newWidth = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val newHeight = (bitmap.height * ratio).toInt().coerceAtLeast(1)

        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }
}
