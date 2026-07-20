package com.example.app_lock.admin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.app_lock.compliance.LocationComplianceCoordinator

/**
 * Standalone [BroadcastReceiver] that listens for
 * `android.location.PROVIDERS_CHANGED` and notifies the
 * [LocationComplianceCoordinator] when the user toggles location off (or back
 * on), engaging the Location Compliance Enforcement ladder (grace countdown →
 * compliance screen → `lockNow()` → unlock-verify loop).
 *
 * ## Why a standalone class
 *
 * The previous implementation used an anonymous receiver embedded inside
 * [LocationEnforcementController]. That worked, but the registration state was
 * tied to a single controller instance — and the controller is constructed
 * fresh in several places ([MainActivity], the foreground service, the
 * WorkManager worker, and the [AppLockDeviceAdminReceiver]). A standalone
 * class with a **process-global** registration flag (in its companion object)
 * guarantees that:
 *
 * - Only one receiver is ever registered at a time, regardless of how many
 *   controller instances call [register].
 * - The WorkManager worker can re-[register] the receiver on each 15-minute
 *   tick, **self-healing** the watch after the app process has been killed and
 *   the runtime receiver was lost (manifest registration of
 *   `PROVIDERS_CHANGED` is not possible on Android 8.0+ — it is not on the
 *   implicit-broadcast exception list — so runtime re-registration is the only
 *   recovery path).
 *
 * ## Why runtime (not manifest) registration
 *
 * `android.location.PROVIDERS_CHANGED` is a protected system broadcast. Since
 * Android 8.0 (API 26), apps can no longer register for most implicit
 * broadcasts in the manifest — `PROVIDERS_CHANGED` is **not** on the exception
 * list, so it must be registered at runtime via
 * [Context.registerReceiver]. The trade-off is that the receiver only lives
 * while the app process is alive; the WorkManager worker re-registers it on
 * each tick to recover after process death.
 *
 * ## Android 13+ (API 33+) flag
 *
 * Registered with [ContextCompat.RECEIVER_EXPORTED] because
 * `PROVIDERS_CHANGED` is a *system* broadcast; on Android 13+ a receiver
 * registered without an explicit flag defaults to `NOT_EXPORTED`, which
 * silently blocks system broadcasts. Mirrors the pattern used in
 * `LockoutManager` for `ACTION_USER_PRESENT`.
 *
 * ## Lifecycle
 *
 * - [register] is called by [LocationEnforcementController.startRealtimeWatch]
 *   (which is called from [AppLockDeviceAdminReceiver.onEnabled] /
 *   [AppLockDeviceAdminReceiver.onProfileProvisioningComplete] and
 *   [UninstallBlockController.setBlocked]) and by
 *   [LocationEnforcementWorker.doWork] on each periodic tick.
 * - [unregister] is called by
 *   [LocationEnforcementController.stopRealtimeWatch] when the block is turned
 *   OFF (before the Device Owner role is cleared).
 *
 * Both methods are idempotent and thread-safe (guarded by `@Synchronized` on the
 * companion object, since the worker thread and the platform thread may both
 * call [register]).
 */
class LocationProviderChangeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != ACTION_PROVIDERS_CHANGED) return
        val appContext = context?.applicationContext ?: return
        Log.i(TAG, "PROVIDERS_CHANGED received — checking location state")
        val controller = LocationEnforcementController.getInstance(appContext)
        if (!controller.isSupported()) {
            // Not a Device Owner (e.g. the role was cleared but the receiver
            // has not yet been unregistered). No-op — the next worker tick will
            // skip re-registration because isSupported() is false.
            Log.d(TAG, "onReceive — not supported (not Device Owner), skipping")
            return
        }
        // Initialize the compliance coordinator (idempotent) so it can read its
        // persisted state and engage / disengage the enforcement ladder. The
        // coordinator's onLocationDisabled / onLocationEnabled methods are
        // themselves guarded by isArmed(), so they are no-ops when the
        // Installment Locker's "Uninstall Block" protection is inactive.
        LocationComplianceCoordinator.init(appContext)
        if (!controller.isLocationEnabled()) {
            Log.w(TAG, "onReceive — location OFF; notifying compliance coordinator")
            LocationComplianceCoordinator.onLocationDisabled()
        } else {
            Log.d(TAG, "onReceive — location ON; notifying compliance coordinator")
            LocationComplianceCoordinator.onLocationEnabled()
        }
    }

    companion object {
        private const val TAG = "LocProviderChangeRx"

        /**
         * `LocationManager.PROVIDERS_CHANGED` action — the system broadcast
         * fired when a location provider's enabled state changes (e.g. the
         * user toggles GPS in Quick Settings or System Settings).
         *
         * The raw string is used directly for consistency with other raw-string
         * constants in this codebase; the value is stable and documented in
         * the Android source (`frameworks/base`).
         */
        private const val ACTION_PROVIDERS_CHANGED = "android.location.PROVIDERS_CHANGED"

        /**
         * The single shared receiver instance. Reused across register/unregister
         * calls so the system always sees the same [BroadcastReceiver] object.
         */
        private val receiver = LocationProviderChangeReceiver()

        /**
         * Process-global flag recording whether the receiver is currently
         * registered.
         *
         * Volatile because it is written on the WorkManager background thread
         * and the platform (MethodChannel / DeviceAdminReceiver) thread, and
         * read on the system's broadcast-delivery thread.
         */
        @Volatile
        private var registered = false

        /**
         * Registers the receiver for `PROVIDERS_CHANGED` if it is not already
         * registered.
         *
         * Idempotent and thread-safe. A no-op when already registered — this is
         * the key property that lets the WorkManager worker call [register] on
         * every 15-minute tick without leaking duplicate receivers.
         *
         * @param context Any context; the application context is used so the
         * receiver is not tied to an Activity lifecycle.
         */
        @Synchronized
        fun register(context: Context) {
            if (registered) {
                Log.d(TAG, "register() — already registered, ignoring")
                return
            }
            val appContext = context.applicationContext
            val filter = IntentFilter(ACTION_PROVIDERS_CHANGED)
            // PROVIDERS_CHANGED is a *system* broadcast, so the receiver MUST
            // be registered as EXPORTED to receive it. On Android 13+ (API 33+)
            // a receiver registered without an explicit flag defaults to
            // NOT_EXPORTED, which silently blocks system broadcasts.
            // RECEIVER_EXPORTED allows the system-originated broadcast through.
            // Mirrors the pattern used in LockoutManager for ACTION_USER_PRESENT.
            ContextCompat.registerReceiver(
                appContext,
                receiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED,
            )
            registered = true
            Log.i(TAG, "register() — PROVIDERS_CHANGED receiver registered")
        }

        /**
         * Unregisters the receiver if it is currently registered.
         *
         * Idempotent and thread-safe. Safe to call when not registered.
         */
        @Synchronized
        fun unregister(context: Context) {
            if (!registered) {
                Log.d(TAG, "unregister() — not registered, ignoring")
                return
            }
            val appContext = context.applicationContext
            runCatching { appContext.unregisterReceiver(receiver) }
            registered = false
            Log.i(TAG, "unregister() — PROVIDERS_CHANGED receiver unregistered")
        }

        /**
         * Returns `true` when the receiver is currently registered.
         *
         * Used by [LocationEnforcementWorker] to log the watch state on each
         * tick and by tests.
         */
        fun isRegistered(): Boolean = registered
    }
}
