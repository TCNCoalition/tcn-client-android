package org.tcncoalition.tcnclient

import android.annotation.SuppressLint
import android.os.Build
import android.os.PowerManager

class WakeLockManager(private val powerManager: PowerManager?) {

    private var wakeLock: PowerManager.WakeLock? = null

    @SuppressLint("InvalidWakeLockTag", "WakelockTimeout")
    fun acquire() {
        var tag = "${BuildConfig.LIBRARY_PACKAGE_NAME}:LOCK"

        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.M && Build.MANUFACTURER == "Huawei") {
            tag = "LocationManagerService"
        }

        wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag)
        wakeLock?.let {
            if (!it.isHeld) {
                it.acquire()
            }
        }
    }

    fun release() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
    }
}