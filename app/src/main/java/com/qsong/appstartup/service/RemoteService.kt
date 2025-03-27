package com.qsong.appstartup.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.qsong.appstartup.LogExt.logd

class RemoteService : Service() {

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        logd("RemoteService onCreate")
    }

}