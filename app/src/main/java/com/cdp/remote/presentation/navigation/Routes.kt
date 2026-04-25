package com.cdp.remote.presentation.navigation

import java.net.URLEncoder

object Routes {
    const val HOST_LIST = "host_list"
    const val CHAT = "chat/{hostIp}/{hostPort}/{wsUrl}/{appName}"
    const val SCHEDULER = "scheduler/{hostIp}/{hostPort}"
    const val WORKFLOW = "workflow/{hostIp}/{hostPort}"

    fun chatRoute(hostIp: String, hostPort: Int, wsUrl: String, appName: String): String {
        val encodedWsUrl = URLEncoder.encode(wsUrl, "UTF-8")
        val encodedAppName = URLEncoder.encode(appName, "UTF-8")
        return "chat/$hostIp/$hostPort/$encodedWsUrl/$encodedAppName"
    }

    fun schedulerRoute(hostIp: String, hostPort: Int): String =
        "scheduler/$hostIp/$hostPort"

    fun workflowRoute(hostIp: String, hostPort: Int): String =
        "workflow/$hostIp/$hostPort"
}
