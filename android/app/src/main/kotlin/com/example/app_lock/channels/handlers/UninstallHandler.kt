package com.example.app_lock.channels.handlers

import android.util.Log
import com.example.app_lock.admin.UninstallBlockOperations

/**
 * Handles uninstall-block method calls coming from Flutter.
 *
 * This is a thin dispatcher: it routes each method name to the
 * [UninstallBlockOperations] and translates the outcome into the primitive
 * `Boolean` that the MethodChannel contract expects.
 *
 * No business logic lives here — capability detection, restriction
 * application, and event emission all live in [UninstallBlockController].
 */
class UninstallHandler(
    private val uninstallController: UninstallBlockOperations,
) {

    companion object {
        private const val TAG = "UninstallHandler"
    }

    /**
     * Applies or removes the uninstall-block restriction.
     *
     * @param blocked `true` to block uninstall, `false` to allow it.
     * @return `true` when the restriction was actually applied/removed.
     */
    fun setUninstallBlocked(blocked: Boolean): Boolean {
        Log.d(TAG, "setUninstallBlocked($blocked) -> delegating to UninstallBlockController")
        return uninstallController.setBlocked(blocked)
    }

    /**
     * Returns `true` when the uninstall-block restriction is currently in force.
     */
    fun isUninstallBlocked(): Boolean {
        Log.d(TAG, "isUninstallBlocked() -> delegating to UninstallBlockController")
        return uninstallController.isBlocked()
    }

    /**
     * Returns `true` when true uninstall blocking is supported on this device
     * (Device Owner or Profile Owner).
     */
    fun isUninstallBlockSupported(): Boolean {
        Log.d(TAG, "isUninstallBlockSupported() -> delegating to UninstallBlockController")
        return uninstallController.isSupported()
    }
}
