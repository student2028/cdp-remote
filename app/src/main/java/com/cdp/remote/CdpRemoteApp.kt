package com.cdp.remote

import android.app.Application
import android.content.Context
import java.io.IOException

class CdpRemoteApp : Application() {
    companion object {
        private lateinit var instance: CdpRemoteApp

        fun readAssetText(path: String): String? {
            return try {
                instance.assets.open(path).bufferedReader().use { it.readText() }
            } catch (e: IOException) {
                null
            }
        }

        fun getAppContext(): Context = instance.applicationContext
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }
}
