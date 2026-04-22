package com.cdp.remote.presentation.navigation

import java.net.URLEncoder

object Routes {
    const val HOST_LIST = "host_list"
    const val CHAT = "chat/{hostIp}/{hostPort}/{wsUrl}/{appName}"

    fun chatRoute(hostIp: String, hostPort: Int, wsUrl: String, appName: String): String {
        val encodedWsUrl = URLEncoder.encode(wsUrl, "UTF-8")
        val encodedAppName = URLEncoder.encode(appName, "UTF-8")
        return "chat/$hostIp/$hostPort/$encodedWsUrl/$encodedAppName"
    }
}
