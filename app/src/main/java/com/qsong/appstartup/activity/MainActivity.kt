package com.qsong.appstartup.activity

import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.IBinder
import android.view.View
import com.qsong.appstartup.LogExt.logd
import com.qsong.appstartup.R
import com.qsong.appstartup.service.RemoteService

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<View>(R.id.button).setOnClickListener {
            val intent = Intent(this, RemoteService::class.java)
            bindService(intent, object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    logd("-->onServiceConnected")
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    logd("-->onServiceDisconnected")
                }

            }, Service.BIND_AUTO_CREATE)
        }
    }
}