package com.cdp.remote.presentation.screen.hosts

import com.cdp.remote.data.cdp.CdpPage
import com.cdp.remote.data.cdp.HostInfo

// DirItem and FolderBrowserState defined here per APK structure (HostListViewModel.kt)

data class DirItem(
    val name: String,
    val path: String
)

data class CwdHistoryItem(
    val path: String,
    val app: String = "",
    val time: String = ""
)

data class FolderBrowserState(
    val isOpen: Boolean = false,
    val hostUrl: String = "",
    val currentPath: String = "",
    val parentPath: String = "",
    val dirs: List<DirItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

enum class LaunchStep {
    IDLE, CONNECTING, LAUNCHING, SUCCESS, FAILED
}

data class HostUiState(
    val hosts: List<HostInfo> = listOf(
        HostInfo("100.106.253.39", 19336, "MacBook (Tailscale)")
    ),
    val isScanning: Boolean = false,
    val discoveredApps: Map<String, List<CdpPage>> = emptyMap(),
    val scanError: String? = null,
    val showAddDialog: Boolean = false,
    val showLaunchDialog: Boolean = false,
    val launchStep: LaunchStep = LaunchStep.IDLE,
    val launchStatus: String? = null,
    val folderBrowserState: FolderBrowserState = FolderBrowserState(),
    val cwdHistory: List<CwdHistoryItem> = emptyList(),
    // ─── 首页 TV 模式 ───
    val tvMode: Boolean = false,
    /** 每个 IDE 的实时帧，key = wsUrl */
    val tvFrames: Map<String, ByteArray> = emptyMap(),
    val tvQuality: Int = 50,
    val tvIntervalMs: Long = 1200L,
    // ─── App 手动排序 ───
    /** 用户自定义的 IDE 应用顺序，key = 主机地址, value = wsUrl 列表（按显示顺序） */
    val appOrder: Map<String, List<String>> = emptyMap()
)
