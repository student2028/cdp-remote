package com.cdp.remote.presentation.screen.chat.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
    onClose: () -> Unit,
    onSettingsChange: (quality: Int, intervalMs: Long) -> Unit,
    onFocusChatChange: (Boolean) -> Unit
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

    val bitmap = remember(frameData) {
        BitmapFactory.decodeByteArray(frameData, 0, frameData.size)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Image display
        if (bitmap != null) {
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
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offsetX,
                        translationY = offsetY
                    )
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(0.5f, 5f)
                            offsetX += pan.x
                            offsetY += pan.y
                        }
                    }
            )
        }

        // Top bar with LIVE badge, focus mode buttons, and settings
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .align(Alignment.TopEnd),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // LIVE badge + stats (left side)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(com.cdp.remote.presentation.theme.AccentGreen)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "LIVE",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = com.cdp.remote.presentation.theme.AccentGreen
                )
                Spacer(modifier = Modifier.width(6.dp))
                val fps = if (intervalMs > 0) String.format("%.1f", 1000.0 / intervalMs) else "0"
                Text(
                    text = "Q:$quality | ${fps}fps | ${formatBytes(bytesTotal)} (${frameCount}帧)",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            // Focus mode buttons + settings (right side)
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
                // Settings gear
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
