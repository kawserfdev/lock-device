package com.example.app_lock.compliance

import android.content.Context
import android.content.Intent
import android.util.Log

import android.os.Build
import com.example.app_lock.service.EnforcementForegroundService

class ComplianceServiceManager(private val context: Context) {
    companion object {
        private const val TAG = "ComplianceServiceMgr"
    }

    fun startService() {
        runCatching {
            val intent = Intent(context, EnforcementForegroundService::class.java).apply {
                action = EnforcementForegroundService.ACTION_START_COMPLIANCE_MONITOR
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }.onFailure { Log.w(TAG, "startService() — failed", it) }
    }

    fun stopService() {
        runCatching {
            val intent = Intent(context, EnforcementForegroundService::class.java).apply {
                action = EnforcementForegroundService.ACTION_STOP
                putExtra(EnforcementForegroundService.EXTRA_STOP_COMPLIANCE, true)
            }
            context.startService(intent)
        }.onFailure { Log.w(TAG, "stopService() — failed", it) }
    }
}
