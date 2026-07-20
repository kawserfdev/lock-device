package com.example.app_lock.compliance

import android.content.Context
import android.content.Intent
import android.util.Log

class ComplianceUIManager(private val context: Context) {
    companion object {
        private const val TAG = "ComplianceUIMgr"
    }

    val lockTaskController = LockTaskController(context.applicationContext)

    fun launchComplianceActivity(lockDeadlineMs: Long) {
        runCatching {
            val intent = Intent(context, LocationComplianceActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TASK or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
                )
                putExtra(LocationComplianceCoordinator.EXTRA_LOCK_DEADLINE_MS, lockDeadlineMs)
            }
            context.startActivity(intent)
            Log.i(TAG, "launchComplianceActivity() — compliance Activity launched")
        }.onFailure { Log.e(TAG, "launchComplianceActivity() — failed to start activity", it) }
    }

    fun dismissComplianceScreen() {
        runCatching {
            context.sendBroadcast(
                Intent(LocationComplianceCoordinator.ACTION_DISMISS_COMPLIANCE)
                    .setPackage(context.packageName)
            )
        }.onFailure { Log.w(TAG, "dismissComplianceScreen() — broadcast failed", it) }
    }

    fun releaseLockTask() {
        runCatching { lockTaskController.disarm() }
            .onFailure { Log.w(TAG, "releaseLockTask() — disarm failed", it) }
    }
}
