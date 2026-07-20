package com.example.app_lock.admin

/**
 * Declares the standard event publishing methods that any event emitter must implement.
 * Replaces direct, mutable global references to EventChannels (P-07).
 */
interface EventPublisher {
    fun emitPermissionChanged(granted: Boolean)
    fun emitLockResult(result: String)
    fun emitServiceStateChanged(status: String)
    fun emitLockoutStateChanged(active: Boolean)
    fun emitUninstallBlockChanged(blocked: Boolean)
    fun emitLocationComplianceStateChanged(state: String)
}

/**
 * Handles security administration operations including lock actions (P-15).
 */
interface DeviceAdminOperations {
    fun lockNow(): Boolean
    fun lockForDuration(durationMs: Long): Boolean
}

/**
 * Handles location tracking compliance enforcement policies (P-15).
 */
interface LocationEnforcementOperations {
    fun isSupported(): Boolean
    fun isLocationEnabled(): Boolean
    fun apply(): Boolean
    fun release()
    fun lockNow(): Boolean
    fun scheduleReapply()
    fun cancelReapply()
    fun startRealtimeWatch()
    fun stopRealtimeWatch()
}

/**
 * Enforces uninstall protection and config rules (P-15).
 */
interface UninstallBlockOperations {
    fun isSupported(): Boolean
    fun isBlocked(): Boolean
    fun setBlocked(blocked: Boolean): Boolean
    fun setOnUninstallBlockChangedListener(listener: OnUninstallBlockChangedListener?)
    fun setActivityLauncher(launcher: com.example.app_lock.permissions.ActivityLauncher?)
}

fun interface OnUninstallBlockChangedListener {
    fun onChanged(isBlocked: Boolean)
}
