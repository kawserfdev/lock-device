package com.example.app_lock.permissions

import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Decouples the system-instantiated [com.example.app_lock.admin.AppLockDeviceAdminReceiver]
 * from the Flutter [com.example.app_lock.channels.LockEventChannel].
 *
 * Why this exists: `DeviceAdminReceiver` callbacks are invoked by the Android
 * system, which constructs the receiver itself — we cannot inject the channel
 * into it. Instead, the receiver posts permission-state changes to this
 * process-wide bus, and [com.example.app_lock.MainActivity] subscribes once and
 * forwards each event onto the EventChannel.
 *
 * Thread-safety: listeners are held in a [CopyOnWriteArrayList] so
 * [post] (called from the system's binder thread) and [addListener] /
 * [removeListener] (called from the platform thread) never race.
 */
object PermissionEventBus {

    private const val TAG = "PermissionEventBus"

    /**
     * Functional listener for permission-state changes.
     */
    fun interface Listener {
        /** Called with `true` when device-admin becomes active, `false` otherwise. */
        fun onPermissionChanged(granted: Boolean)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()

    /**
     * Registers a listener. Idempotent — adding the same listener twice has no
     * extra effect.
     */
    fun addListener(listener: Listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
            Log.d(TAG, "Listener added (${listeners.size} total)")
        }
    }

    /**
     * Unregisters a listener.
     */
    fun removeListener(listener: Listener) {
        listeners.remove(listener)
        Log.d(TAG, "Listener removed (${listeners.size} total)")
    }

    /**
     * Posts a permission-state change to all registered listeners.
     *
     * Called from the system's binder thread inside the DeviceAdminReceiver
     * callbacks; safe because [CopyOnWriteArrayList] iterators are snapshot
     * views.
     */
    fun post(granted: Boolean) {
        Log.d(TAG, "post(granted=$granted) to ${listeners.size} listeners")
        listeners.forEach { it.onPermissionChanged(granted) }
    }
}
