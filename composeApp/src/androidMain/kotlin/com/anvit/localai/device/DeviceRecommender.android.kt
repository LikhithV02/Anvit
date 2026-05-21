package com.anvit.localai.device

import android.app.ActivityManager
import android.content.Context
import com.anvit.localai.AnvitContextHolder

actual fun getDeviceRecommendation(): DeviceRecommendation {
    val am = AnvitContextHolder.appContext
        .getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
    val gbTotal = info.totalMem / (1024.0 * 1024 * 1024)
    return when {
        gbTotal >= 10.0 -> DeviceRecommendation("gemma4-e4b", "gpu")
        else            -> DeviceRecommendation("gemma4-e2b", "cpu")
    }
}
