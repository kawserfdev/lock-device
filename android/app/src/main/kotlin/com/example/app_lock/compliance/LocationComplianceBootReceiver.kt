package com.example.app_lock.compliance

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Manifest-registered [BroadcastReceiver] that restores the Location Compliance
 * Enforcement System after a device reboot.
 *
 * ## Why a boot receiver
 *
 * The enforcement state is persisted by [LocationComplianceStateStore] so it
 * survives reboot, but the in-memory [LocationComplianceCoordinator] state, the
 * runtime [com.example.app_lock.admin.LocationProviderChangeReceiver], and the
 * [LocationComplianceForegroundService] are all lost when the device powers
 * off. On `BOOT_COMPLETED` this receiver:
 *
 * 1. Reads the persisted store to check whether enforcement was armed before
 *    the reboot.
 * 2. If armed, starts the [LocationComplianceForegroundService] (which in turn
 *    calls [LocationComplianceCoordinator.restore] to rebuild the state machine
 *    and re-register the runtime receiver).
 * 3. If not armed (e.g. the "Uninstall Block" was turned OFF before the
 *    reboot), does nothing — the store is already clear.
 *
 * ## Manifest registration
 *
 * Registered in `AndroidManifest.xml` with the
 * `android.permission.RECEIVE_BOOT_COMPLETED` permission and a
 * `BOOT_COMPLETED` intent filter. `BOOT_COMPLETED` *is* on the implicit-
 * broadcast exception list, so manifest registration works (unlike
 * `PROVIDERS_CHANGED`).
 *
 * ## Android 12–15 notes
 *
 * `BOOT_COMPLETED` is a protected system broadcast delivered while the app is
 * in the background. On Android 12+ (API 31+) the app can receive it without
 * special restrictions because it is on the implicit-broadcast exception list.
 * Starting a foreground service from a boot receiver is permitted because the
 * app holds the `FOREGROUND_SERVICE_SPECIAL_USE` permission and the service is
 * declared with the `specialUse` type.
 */
class LocationComplianceBootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "LocComplianceBootRx"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "onReceive() — ignoring action=${intent?.action}")
            return
        }
        val appContext = context?.applicationContext ?: return
        Log.i(TAG, "BOOT_COMPLETED — checking persisted enforcement state")
        val store = LocationComplianceStateStore(appContext)
        if (!store.isEnforcementArmed()) {
            Log.d(TAG, "onReceive() — enforcement not armed before reboot; staying idle")
            return
        }
        Log.i(TAG, "onReceive() — enforcement was armed; starting compliance foreground service")
        runCatching {
            val serviceIntent = Intent(appContext, com.example.app_lock.service.EnforcementForegroundService::class.java).apply {
                action = com.example.app_lock.service.EnforcementForegroundService.ACTION_START_COMPLIANCE_MONITOR
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                appContext.startForegroundService(serviceIntent)
            } else {
                appContext.startService(serviceIntent)
            }
        }.onFailure { Log.e(TAG, "onReceive() — failed to start foreground service", it) }
    }
}
