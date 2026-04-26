package com.cdp.remote.presentation.screen.chat.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * TV 实时画面组件 — 支持「观影模式」和「操控模式」双模切换。
 *
 * 观影模式（默认）：双指缩放 + 单指平移，与原有行为完全一致。
 * 操控模式：触摸事件映射到远端 IDE 的 CDP Input 坐标：
 *   - 单击 → mousePressed + mouseReleased（点击）
 *   - 拖拽 → mouseMoved 序列（选择文本/拖动元素）
 *   - 双指滑动 → 滚轮事件（上下滚动页面）
 */
@Composable
fun TvLiveView(
    modifier: Modifier = Modifier,
    isFullScreen: Boolean = false,
    frameData: ByteArray,
    quality: Int,
    intervalMs: Long,
    bytesTotal: Long = 0L,
    frameCount: Int = 0,
    focusChat: Boolean = true,
    appName: String = "Antigravity",
    controlMode: Boolean = false,
    onClose: () -> Unit,
    onSettingsChange: (quality: Int, intervalMs: Long) -> Unit,
    onFocusChatChange: (Boolean) -> Unit,
    onToggleControlMode: () -> Unit = {},
    onRemoteInput: (type: String, ratioX: Float, ratioY: Float, button: String) -> Unit = { _, _, _, _ -> },
    onRemoteScroll: (ratioX: Float, ratioY: Float, deltaY: Float) -> Unit = { _, _, _ -> }
) {
    var showSettings by remember { mutableStateOf(false) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    // 0=RIGHT(default), 1=CENTER(full), 2=LEFT
    var focusMode by remember(appName) {
        val initial = when {
            appName.contains("Cursor", ignoreCase = true) -> 2
            appName.contains("Codex", ignoreCase = true) -> 1
            else -> 0
        }
        mutableIntStateOf(initial)
    }

    // 记录 Image 组件在屏幕上的实际渲染尺寸（用于坐标映射）
    var imageLayoutSize by remember { mutableStateOf(IntSize.Zero) }

    val bitmap = remember(frameData) {
        BitmapFactory.decodeByteArray(frameData, 0, frameData.size)
    }

    // 操控模式下保持当前缩放和偏移不变 —— 用户先在观影模式下放大到目标区域，
    // 再切换到操控模式进行精准点击。toImageRatio() 已正确处理 scale/offset 变换。

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Image display
        if (bitmap != null) {
            val bitmapW = bitmap.width.toFloat()
            val bitmapH = bitmap.height.toFloat()

            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "IDE 实时画面",
                contentScale = if (focusMode != 1) ContentScale.FillHeight else ContentScale.Fit,
                alignment = when (focusMode) {
                    0 -> Alignment.TopEnd     // right panel
                    2 -> Alignment.TopStart   // left panel
                    else -> Alignment.Center  // full screen
                },
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { coordinates ->
                        imageLayoutSize = coordinates.size
                    }
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offsetX,
                        translationY = offsetY
                    )
                    .pointerInput(controlMode, focusMode) {
                        if (!controlMode) {
                            // ───── 观影模式：原有的双指缩放 + 平移 ─────
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(0.5f, 5f)
                                offsetX += pan.x
                                offsetY += pan.y
                            }
                        } else {
                            // ───── 操控模式：单指点击/长按/拖拽，双指滚动 ─────
                            awaitPointerEventScope {
                                while (true) {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    var isScrolling = false
                                    var isDragging = false
                                    var lastPos = down.position
                                    val downTime = System.currentTimeMillis()
                                    
                                    do {
                                        val event = awaitPointerEvent()
                                        val pointers = event.changes
                                        
                                        if (pointers.size >= 2) {
                                            isScrolling = true
                                            val currentCentroidY = pointers.map { it.position.y }.average().toFloat()
                                            val previousCentroidY = pointers.map { it.previousPosition.y }.average().toFloat()
                                            val panY = currentCentroidY - previousCentroidY
                                            if (panY != 0f) {
                                                val currentCentroidX = pointers.map { it.position.x }.average().toFloat()
                                                toImageRatio(
                                                    Offset(currentCentroidX, currentCentroidY), 
                                                    imageLayoutSize, bitmapW, bitmapH,
                                                    focusMode, scale, offsetX, offsetY
                                                )?.let { (rx, ry) ->
                                                    // panY 是手指位移，手指向下滑（panY > 0）代表网页要向上滚，即发送正数 deltaY
                                                    onRemoteScroll(rx, ry, -panY * 2.5f)
                                                }
                                            }
                                            pointers.forEach {
                                                if (it.position != it.previousPosition) {
                                                    it.consume()
                                                }
                                            }
                                        } else if (!isScrolling && pointers.size == 1) {
                                            val pointer = pointers.first()
                                            val dist = (pointer.position - down.position).getDistance()
                                            if (!isDragging && dist > 18f) { // ~touch slop
                                                isDragging = true
                                                toImageRatio(down.position, imageLayoutSize, bitmapW, bitmapH, focusMode, scale, offsetX, offsetY)?.let { (rx, ry) ->
                                                    onRemoteInput("mousePressed", rx, ry, "left")
                                                }
                                            }
                                            if (isDragging && pointer.position != lastPos) {
                                                lastPos = pointer.position
                                                toImageRatio(pointer.position, imageLayoutSize, bitmapW, bitmapH, focusMode, scale, offsetX, offsetY)?.let { (rx, ry) ->
                                                    onRemoteInput("mouseMoved", rx, ry, "left")
                                                }
                                                if (pointer.position != pointer.previousPosition) {
                                                    pointer.consume()
                                                }
                                            }
                                        }
                                    } while (event.changes.any { it.pressed })
                                    
                                    // Handle release
                                    if (isDragging) {
                                        toImageRatio(lastPos, imageLayoutSize, bitmapW, bitmapH, focusMode, scale, offsetX, offsetY)?.let { (rx, ry) ->
                                            onRemoteInput("mouseReleased", rx, ry, "left")
                                        }
                                    } else if (!isScrolling) {
                                        val duration = System.currentTimeMillis() - downTime
                                        if (duration > 400) { // Long press threshold
                                            toImageRatio(down.position, imageLayoutSize, bitmapW, bitmapH, focusMode, scale, offsetX, offsetY)?.let { (rx, ry) ->
                                                onRemoteInput("mousePressed", rx, ry, "right")
                                                onRemoteInput("mouseReleased", rx, ry, "right")
                                            }
                                        } else {
                                            toImageRatio(down.position, imageLayoutSize, bitmapW, bitmapH, focusMode, scale, offsetX, offsetY)?.let { (rx, ry) ->
                                                onRemoteInput("mousePressed", rx, ry, "left")
                                                onRemoteInput("mouseReleased", rx, ry, "left")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
            )
        }

        // ─── 顶部工具栏（单行，点击 LIVE 徽章切换操控模式） ─────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .align(Alignment.TopEnd),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // LIVE / CTRL 可点击徽章（点击切换操控模式）+ 统计信息
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (controlMode) Color(0xFFFF9800).copy(alpha = 0.15f)
                        else MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                    )
                    .then(
                        if (controlMode) Modifier.border(1.dp, Color(0xFFFF9800), RoundedCornerShape(8.dp))
                        else Modifier
                    )
                    .pointerInput(Unit) {
                        detectTapGestures { onToggleControlMode() }
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(
                            if (controlMode) Color(0xFFFF9800)
                            else com.cdp.remote.presentation.theme.AccentGreen
                        )
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (controlMode) "CTRL" else "LIVE",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (controlMode) Color(0xFFFF9800)
                            else com.cdp.remote.presentation.theme.AccentGreen
                )
                Spacer(modifier = Modifier.width(6.dp))
                val fps = if (intervalMs > 0) String.format("%.1f", 1000.0 / intervalMs) else "0"
                Text(
                    text = "Q:$quality | ${fps}fps | ${formatBytes(bytesTotal)} (${frameCount}帧)",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }

            // 聚焦模式按钮 + 设置齿轮（右侧）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
            ) {
                TextButton(
                    onClick = { focusMode = 2 },
                    modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    colors = if (focusMode == 2) ButtonDefaults.filledTonalButtonColors() else ButtonDefaults.textButtonColors()
                ) { Text("◀左", fontSize = 11.sp) }
                TextButton(
                    onClick = { focusMode = 1 },
                    modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    colors = if (focusMode == 1) ButtonDefaults.filledTonalButtonColors() else ButtonDefaults.textButtonColors()
                ) { Text("全屏", fontSize = 11.sp) }
                TextButton(
                    onClick = { focusMode = 0 },
                    modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    colors = if (focusMode == 0) ButtonDefaults.filledTonalButtonColors() else ButtonDefaults.textButtonColors()
                ) { Text("右▶", fontSize = 11.sp) }
                // 设置齿轮
                IconButton(
                    onClick = { showSettings = !showSettings },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = "TV 设置",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // 操控模式下显示底部提示条
        if (controlMode) {
            Text(
                text = "🖱️ 操控模式：点击=左键 | 长按=右键 | 双指=滚动",
                fontSize = 10.sp,
                color = Color(0xFFFF9800),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 4.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }

        // Settings panel
        if (showSettings) {
            TvSettingsPanel(
                quality = quality,
                intervalMs = intervalMs,
                onSettingsChange = onSettingsChange
            )
        }
    }
}

/**
 * 将 Image 组件上的触摸坐标转换为原始图片的归一化比例 [0..1]。
 *
 * 考虑了 ContentScale（FillHeight / Fit）和 Alignment（TopEnd / TopStart / Center）
 * 对图片实际绘制区域的影响。
 *
 * @return Pair(ratioX, ratioY) 或 null（触摸点在图片外）
 */
private fun toImageRatio(
    touchOffset: Offset,
    layoutSize: IntSize,
    bitmapW: Float,
    bitmapH: Float,
    focusMode: Int,
    scale: Float,
    transX: Float,
    transY: Float
): Pair<Float, Float>? {
    if (layoutSize.width <= 0 || layoutSize.height <= 0 || bitmapW <= 0f || bitmapH <= 0f) return null

    val viewW = layoutSize.width.toFloat()
    val viewH = layoutSize.height.toFloat()

    // Compose 的 pointerInput 定义在 graphicsLayer 之后，所以它接收到的 touchOffset 
    // 已经被 Compose 自动应用了反向变换（已经处于原始 1:1 的 local 坐标系中）。
    // 所以这里不需要再手动除以 scale 和减去 transX！
    val localX = touchOffset.x
    val localY = touchOffset.y

    // 根据 ContentScale 计算图片实际绘制区域
    val drawScale: Float
    val drawW: Float
    val drawH: Float

    if (focusMode != 1) {
        // FillHeight：图片高度撑满，宽度可能超出
        drawScale = viewH / bitmapH
        drawW = bitmapW * drawScale
        drawH = viewH
    } else {
        // Fit：整张图片可见
        val scaleW = viewW / bitmapW
        val scaleH = viewH / bitmapH
        drawScale = minOf(scaleW, scaleH)
        drawW = bitmapW * drawScale
        drawH = bitmapH * drawScale
    }

    // 根据 Alignment 计算图片绘制区域的偏移
    val imgLeft: Float
    val imgTop: Float
    when (focusMode) {
        0 -> { // TopEnd
            imgLeft = viewW - drawW
            imgTop = 0f
        }
        2 -> { // TopStart
            imgLeft = 0f
            imgTop = 0f
        }
        else -> { // Center
            imgLeft = (viewW - drawW) / 2f
            imgTop = (viewH - drawH) / 2f
        }
    }

    // 检查触摸点是否在图片绘制区域内
    val relX = localX - imgLeft
    val relY = localY - imgTop
    if (relX < 0f || relX > drawW || relY < 0f || relY > drawH) return null

    // 归一化到 [0..1]
    val ratioX = relX / drawW
    val ratioY = relY / drawH
    return Pair(ratioX.coerceIn(0f, 1f), ratioY.coerceIn(0f, 1f))
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "${bytes}B"
    if (bytes < 1048576) return "${bytes / 1024}KB"
    return String.format("%.1fMB", bytes / 1048576.0)
}
@Composable
private fun TvSettingsPanel(
    quality: Int,
    intervalMs: Long,
    onSettingsChange: (quality: Int, intervalMs: Long) -> Unit
) {
    var localQuality by remember(quality) { mutableFloatStateOf(quality.toFloat()) }
    var localInterval by remember(intervalMs) { mutableFloatStateOf(intervalMs.toFloat()) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 56.dp, start = 16.dp, end = 16.dp, bottom = 16.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
            .padding(16.dp)
    ) {
        Text("JPEG 质量", style = MaterialTheme.typography.labelMedium)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("低画质\n省流量", style = MaterialTheme.typography.labelSmall)
            Text("高画质\n费流量", style = MaterialTheme.typography.labelSmall)
        }
        Slider(
            value = localQuality,
            onValueChange = { localQuality = it },
            valueRange = 10f..100f,
            onValueChangeFinished = { onSettingsChange(localQuality.toInt(), localInterval.toLong()) }
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text("刷新间隔", style = MaterialTheme.typography.labelMedium)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("慢 (0.3fps)\n省流量", style = MaterialTheme.typography.labelSmall)
            Text("快 (5fps)\n费流量", style = MaterialTheme.typography.labelSmall)
        }
        Slider(
            value = localInterval,
            onValueChange = { localInterval = it },
            valueRange = 100f..3000f,
            onValueChangeFinished = { onSettingsChange(localQuality.toInt(), localInterval.toLong()) }
        )
    }
}
