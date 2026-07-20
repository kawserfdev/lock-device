package com.example.app_lock.compliance

import android.os.Handler
import android.os.Looper

class ComplianceTimerManager(
    private val onGraceTimeout: () -> Unit,
    private val onLockTimeout: () -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val graceRunnable = Runnable { onGraceTimeout() }
    private val lockRunnable = Runnable { onLockTimeout() }

    fun startGraceTimer(durationMs: Long) {
        cancelGraceTimer()
        mainHandler.postDelayed(graceRunnable, durationMs)
    }

    fun cancelGraceTimer() {
        mainHandler.removeCallbacks(graceRunnable)
    }

    fun startLockTimer(durationMs: Long) {
        cancelLockTimer()
        mainHandler.postDelayed(lockRunnable, durationMs)
    }

    fun cancelLockTimer() {
        mainHandler.removeCallbacks(lockRunnable)
    }

    fun cancelAll() {
        cancelGraceTimer()
        cancelLockTimer()
    }
}
