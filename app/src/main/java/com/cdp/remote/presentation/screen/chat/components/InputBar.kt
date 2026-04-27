package com.cdp.remote.presentation.screen.chat.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cdp.remote.presentation.theme.*

@Composable
fun ActionToolbar(
    isConnected: Boolean,
    isGenerating: Boolean,
    tvMode: Boolean,
    currentModel: String,
    isWindsurf: Boolean = false,
    /** Codex 专用：隐藏上翻/下翻并显示项目管理按钮 */
    isCodex: Boolean = false,
    onNewSession: () -> Unit,
    onStopGeneration: () -> Unit,
    onCancelRunningTask: () -> Unit = {},
    onScrollUp: () -> Unit,
    onScrollDown: () -> Unit,
    onAcceptAll: () -> Unit,
    onRejectAll: () -> Unit,
    onSwitchModel: () -> Unit,
    /** 历史会话列表（合并自原悬浮按钮） */
    onSessionList: () -> Unit = {},
    /** Codex 项目管理（合并自原悬浮按钮） */
    onProjectManagement: () -> Unit = {},
    /** 反重力系：打开「全局规则」弹窗，经 CDP 写入 Customizations → Global */
    showGlobalRuleButton: Boolean = false,
    onGlobalRule: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 历史会话列表（原悬浮按钮，合并到工具栏首位）
            ToolbarBtn(
                icon = Icons.Default.List,
                label = "会话",
                enabled = isConnected,
                onClick = onSessionList
            )
            // Codex 专属：项目管理（原悬浮按钮，合并到工具栏）
            if (isCodex) {
                ToolbarBtn(
                    icon = Icons.Default.FolderOpen,
                    label = "项目",
                    enabled = isConnected,
                    iconColor = Primary,
                    onClick = onProjectManagement
                )
            }
            ToolbarBtn(
                icon = Icons.Default.Add,
                label = "新建",
                enabled = isConnected,
                iconColor = Color(0xFF2196F3),
                onClick = onNewSession
            )
            ToolbarBtn(
                icon = Icons.Default.Stop,
                label = "停止",
                enabled = isConnected,
                iconColor = Accent,
                onClick = onStopGeneration
            )
            // Windsurf 专属：取消运行中任务按钮
            // 不依赖 isGenerating（任务可能从 IDE 直接发起，手机端状态未同步）
            if (isWindsurf) {
                ToolbarBtn(
                    icon = Icons.Default.Close,
                    label = "取消任务",
                    enabled = isConnected,
                    iconColor = Color(0xFFE53935),
                    onClick = onCancelRunningTask
                )
            }
            // 上翻/下翻：Codex 不需要（侧边栏已含会话与项目入口，节省横向空间）
            if (!isCodex) {
                ToolbarBtn(
                    icon = Icons.Default.KeyboardArrowUp,
                    label = "上翻",
                    enabled = isConnected,
                    onClick = onScrollUp
                )
                ToolbarBtn(
                    icon = Icons.Default.KeyboardArrowDown,
                    label = "下翻",
                    enabled = isConnected,
                    onClick = onScrollDown
                )
            }
            ToolbarBtn(
                icon = Icons.Default.CheckCircle,
                label = "接受",
                enabled = isConnected,
                iconColor = AccentGreen,
                onClick = onAcceptAll
            )
            ToolbarBtn(
                icon = Icons.Default.Cancel,
                label = "拒绝",
                enabled = isConnected,
                iconColor = AccentOrange,
                onClick = onRejectAll
            )
            ToolbarBtn(
                icon = Icons.Default.SwapHoriz,
                label = "切换",
                enabled = isConnected,
                iconColor = Primary,
                onClick = onSwitchModel
            )
            if (showGlobalRuleButton) {
                ToolbarBtn(
                    icon = Icons.Default.Tune,
                    label = "全局",
                    enabled = isConnected,
                    iconColor = Color(0xFF7E57C2),
                    onClick = onGlobalRule
                )
            }
        }
    }
}

@Composable
private fun ToolbarBtn(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    iconColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: () -> Unit
) {
    val alpha = if (enabled) 1f else 0.4f

    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.widthIn(min = 42.dp),
        shape = RoundedCornerShape(6.dp),
        color = Color.Transparent
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 3.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(16.dp),
                tint = iconColor.copy(alpha = alpha)
            )
            Text(
                text = label,
                fontSize = 9.sp,
                fontWeight = FontWeight.Light,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha * 0.8f)
            )
        }
    }
}

@Composable
fun InputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttachImage: () -> Unit,
    isConnected: Boolean,
    isGenerating: Boolean,
    pendingImages: List<com.cdp.remote.presentation.screen.chat.PendingImage> = emptyList(),
    onRemoveImage: ((Long) -> Unit)? = null,
    // Legacy compat
    hasPendingImage: Boolean = false,
    onClearImage: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    val borderColor by animateColorAsState(
        targetValue = if (isFocused) Primary.copy(alpha = 0.5f) else Color.Transparent,
        label = "borderColor"
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp,
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            // Image thumbnail strip
            if (pendingImages.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    pendingImages.forEach { img ->
                        Box(modifier = Modifier.size(56.dp)) {
                            // Thumbnail
                            val bitmap = remember(img.id) {
                                try {
                                    val bytes = android.util.Base64.decode(img.base64, android.util.Base64.DEFAULT)
                                    // Decode with downsampling for thumbnail
                                    val opts = android.graphics.BitmapFactory.Options().apply {
                                        inJustDecodeBounds = true
                                    }
                                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                                    opts.inSampleSize = maxOf(1, minOf(opts.outWidth, opts.outHeight) / 56)
                                    opts.inJustDecodeBounds = false
                                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                                } catch (e: Exception) { null }
                            }
                            if (bitmap != null) {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = "待发送图片",
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(24.dp))
                                }
                            }
                            // Delete button
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 4.dp, y = (-4).dp)
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.error)
                                    .clickable { onRemoveImage?.invoke(img.id) },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "删除图片",
                                    modifier = Modifier.size(12.dp),
                                    tint = Color.White
                                )
                            }
                        }
                    }
                }
            }

            // Input row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Attach image button
                IconButton(
                    onClick = onAttachImage,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    enabled = isConnected
                ) {
                    Icon(
                        imageVector = Icons.Default.Landscape,
                        contentDescription = "添加图片",
                        modifier = Modifier.size(20.dp),
                        tint = if (pendingImages.isNotEmpty()) Secondary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Text input field with embedded send button
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 40.dp, max = 150.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                        .border(1.dp, borderColor, RoundedCornerShape(20.dp))
                        .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
                            if (text.isEmpty()) {
                                Text(
                                    text = if (pendingImages.isNotEmpty()) "📷 ${pendingImages.size}张图片已选择" else "输入消息...",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    fontSize = 14.sp
                                )
                            }
                            BasicTextField(
                                value = text,
                                onValueChange = onTextChange,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .onFocusChanged { isFocused = it.isFocused },
                                textStyle = TextStyle(
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 14.sp,
                                    lineHeight = 20.sp
                                ),
                                cursorBrush = SolidColor(Primary),
                                enabled = isConnected
                            )
                        }
                        // Send button — always send, never stop (stop is in toolbar)
                        val canSend = isConnected && (text.isNotBlank() || pendingImages.isNotEmpty())
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(
                                    if (canSend) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.surfaceVariant
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            IconButton(
                                onClick = onSend,
                                modifier = Modifier.size(32.dp),
                                enabled = canSend
                            ) {
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowUp,
                                    contentDescription = "发送",
                                    modifier = Modifier.size(18.dp),
                                    tint = if (canSend) MaterialTheme.colorScheme.surface
                                           else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

