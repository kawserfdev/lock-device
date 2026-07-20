package com.example.app_lock.service

import android.content.Context
import com.example.app_lock.admin.DeviceAdminController
import com.example.app_lock.admin.DeviceAdminOperations
import com.example.app_lock.admin.LocationEnforcementController
import com.example.app_lock.admin.LocationEnforcementOperations
import com.example.app_lock.admin.UninstallBlockController
import com.example.app_lock.admin.UninstallBlockOperations
import com.example.app_lock.channels.EventBridge

/**
 * Thread-safe process singleton responsible for building and orchestrating
 * native compliance dependencies without requiring Flutter context (P-06, P-15).
 */
class EngineBootstrap private constructor(context: Context) {

    val eventBridge = EventBridge()

    val deviceAdminOperations: DeviceAdminOperations = DeviceAdminController(context, eventBridge)

    val locationEnforcementOperations: LocationEnforcementOperations = LocationEnforcementController.getInstance(context)

    val uninstallBlockOperations: UninstallBlockOperations = UninstallBlockController(context, eventBridge)

    val lockoutManager = LockoutManager(context, eventBridge)

    init {
        uninstallBlockOperations.setOnUninstallBlockChangedListener { isBlocked ->
            if (isBlocked) {
                locationEnforcementOperations.apply()
                locationEnforcementOperations.scheduleReapply()
                locationEnforcementOperations.startRealtimeWatch()
            } else {
                locationEnforcementOperations.cancelReapply()
                locationEnforcementOperations.stopRealtimeWatch()
                locationEnforcementOperations.release()
            }
        }
    }

    companion object {
        @Volatile
        private var instance: EngineBootstrap? = null

        @JvmStatic
        fun getInstance(context: Context): EngineBootstrap {
            return instance ?: synchronized(this) {
                instance ?: EngineBootstrap(context.applicationContext).also { instance = it }
            }
        }
    }
}
