package com.qsong.appstartup.utils

import android.app.Application
import com.qsong.appstartup.BuildConfig
import java.lang.Exception
import kotlin.reflect.KClass

object ProcessUtils {

    /**
     * 获取当前进程名
     */
    fun getProcessName() : String? {
        val activityThreadClsStr = "android.app.ActivityThread"
        val methodForProcessNameStr = "currentProcessName"
        try {
            val activityThreadCls = Class.forName(activityThreadClsStr)
            val params = arrayOf(Object::class.java)
            val methodForProcessName = activityThreadCls.getDeclaredMethod(methodForProcessNameStr)
            methodForProcessName.isAccessible = true
            val result = methodForProcessName.invoke(null)
            if (result is String) {
                return result
            }
        } catch (e : Exception) {
            e.printStackTrace()
        }
        return null
    }

    /**
     * 是否主进程
     */
    fun isMainProcess() : Boolean {
        return getProcessName() == BuildConfig.APPLICATION_ID
    }
}