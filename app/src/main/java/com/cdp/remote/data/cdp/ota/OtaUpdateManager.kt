package com.cdp.remote.data.ota

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

class OtaUpdateManager(
    private val context: Context,
    /** 仅用于 download 时的 base；检查版本可用 [checkFromRelayUrls] */
    private val baseUrl: String
) {
    companion object {
        private const val TAG = "OtaUpdateManager"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    data class VersionInfo(
        val versionCode: Int,
        val versionName: String,
        val updateMessage: String
    )

    suspend fun checkForUpdates(): VersionInfo? = getVersionInfo(baseUrl)

    private suspend fun getVersionInfo(base: String): VersionInfo? = withContext(Dispatchers.IO) {
        try {
            val url = if (base.endsWith("/")) "${base}version" else "$base/version"
            Log.d(TAG, "GET $url")
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                val body = if (response.isSuccessful) response.body?.string() else null
                if (body == null) {
                    Log.w(TAG, "/version HTTP ${response.code} $url")
                    return@withContext null
                }
                val json = JsonParser.parseString(body).asJsonObject
                if (json.has("error") && !json.has("versionCode")) {
                    Log.w(TAG, "relay error json: $body")
                    return@withContext null
                }
                val versionCode = json.get("versionCode")?.asInt ?: return@withContext null
                val versionName = json.get("versionName")?.asString ?: "?"
                val updateMessage = json.get("updateMessage")?.asString ?: ""
                VersionInfo(versionCode, versionName, updateMessage)
            }
        } catch (e: Exception) {
            Log.e(TAG, "getVersionInfo fail $base", e)
            null
        }
    }

    /**
     * 遍历所有中继地址，取 **最高** 的 versionCode 与 [localVersionCode] 比较。
     * [relayHttpUrls] 须为 **OTA 专用端口**（本工程固定 [com.cdp.remote.data.cdp.RELAY_OTA_HTTP_PORT]）的 http://ip:port。
     */
    suspend fun checkFromRelayUrls(relayHttpUrls: List<String>, localVersionCode: Long): OtaCheckOutcome =
        withContext(Dispatchers.IO) {
            val distinct = relayHttpUrls.map { it.trimEnd('/') }.distinct().filter { it.isNotEmpty() }
            if (distinct.isEmpty()) {
                return@withContext OtaCheckOutcome.Failed("列表里还没有中继地址，请先添加 Mac 的 IP:端口")
            }
            var best: VersionInfo? = null
            var bestUrl = ""
            val errors = mutableListOf<String>()
            for (base in distinct) {
                val info = getVersionInfo(base)
                if (info == null) {
                    errors.add("$base 无响应")
                    continue
                }
                if (best == null || info.versionCode > best.versionCode) {
                    best = info
                    bestUrl = base
                }
            }
            val remote = best
                ?: return@withContext OtaCheckOutcome.Failed(
                    errors.joinToString("；").ifEmpty { "全部中继都无法返回 /version" }
                )
            Log.i(TAG, "OTA 本机=$localVersionCode 中继=${remote.versionCode} ($bestUrl)")
            return@withContext when {
                remote.versionCode.toLong() > localVersionCode ->
                    OtaCheckOutcome.UpdateAvailable(remote, bestUrl)

                remote.versionCode.toLong() == localVersionCode ->
                    OtaCheckOutcome.UpToDate(remote, bestUrl)

                else ->
                    OtaCheckOutcome.Failed(
                        "本机 versionCode=$localVersionCode 已高于中继 ${remote.versionCode}。" +
                            "请在电脑上的仓库根目录执行 ./publish_ota.sh 后保持 node 中继运行。"
                    )
            }
        }

    fun downloadAndInstallUpdate(fromRelayBaseUrl: String, fileName: String = "CdpRemote-update.apk") {
        try {
            val downloadUrl =
                if (fromRelayBaseUrl.endsWith("/")) "${fromRelayBaseUrl}download_apk" else "$fromRelayBaseUrl/download_apk"
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val destination = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
            if (destination.exists()) destination.delete()

            val request = DownloadManager.Request(Uri.parse(downloadUrl))
                .setTitle("CdpRemote 更新")
                .setDescription("正在下载新版本...")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationUri(Uri.fromFile(destination))
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)

            val downloadId = downloadManager.enqueue(request)

            val onComplete = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                    if (id != downloadId) return
                    try {
                        // 查询 DownloadManager 确认下载成功，并使用其 content URI（与通知栏点击一致）
                        val query = DownloadManager.Query().setFilterById(downloadId)
                        val cursor = downloadManager.query(query)
                        if (cursor != null && cursor.moveToFirst()) {
                            val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                            val status = if (statusIdx >= 0) cursor.getInt(statusIdx) else -1
                            cursor.close()
                            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                                // 使用 DownloadManager 提供的 content URI，保证文件已完全写入
                                val dmUri = downloadManager.getUriForDownloadedFile(downloadId)
                                if (dmUri != null) {
                                    installApkFromUri(dmUri)
                                } else {
                                    // fallback：DownloadManager URI 不可用时用文件路径
                                    installApk(destination)
                                }
                            } else {
                                Log.w(TAG, "下载未成功，状态码: $status")
                            }
                        } else {
                            cursor?.close()
                            Log.w(TAG, "无法查询下载状态")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "处理下载完成事件失败", e)
                    }
                    ctx.unregisterReceiver(this)
                }
            }

            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(
                    onComplete,
                    IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                    Context.RECEIVER_EXPORTED
                )
            } else {
                context.registerReceiver(onComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start download", e)
        }
    }

    /** 兼容旧调用：用构造时的 baseUrl */
    fun downloadAndInstallUpdate(fileName: String = "CdpRemote-update.apk") {
        downloadAndInstallUpdate(baseUrl, fileName)
    }

    private fun installApk(file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.applicationContext.packageName}.fileprovider",
                file
            )
            installApkFromUri(uri)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install APK", e)
        }
    }

    private fun installApkFromUri(uri: Uri) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install APK from URI", e)
        }
    }
}

/** Activity / Service 里取本应用 versionCode（API 兼容） */
fun Context.appVersionCodeLong(): Long {
    val pInfo = packageManager.getPackageInfo(packageName, 0)
    return PackageInfoCompat.getLongVersionCode(pInfo)
}

sealed class OtaCheckOutcome {
    data class UpdateAvailable(val info: OtaUpdateManager.VersionInfo, val relayBaseUrl: String) : OtaCheckOutcome()
    data class UpToDate(val remote: OtaUpdateManager.VersionInfo, val relayBaseUrl: String) : OtaCheckOutcome()
    data class Failed(val detail: String) : OtaCheckOutcome()
}
