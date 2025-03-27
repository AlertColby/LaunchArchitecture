package com.qsong.appstartup

import android.util.Log

object LogExt {
    const val isDebug = true
    const val TAG = "CCC"

    inline fun <reified T> T.logd(message: String) {
        if (isDebug) {
            Log.d(TAG, message)
        }
    }

}
