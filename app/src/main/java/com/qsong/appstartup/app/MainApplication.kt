package com.qsong.appstartup.app

import android.app.Application
import android.util.Log
import com.qsong.appstartup.LogExt.logd
import com.qsong.appstartup.utils.ProcessUtils

class MainApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        logd("MainApplication onCreate")
        ProcessUtils.getProcessName()?.let { logd(it) }
    }
}