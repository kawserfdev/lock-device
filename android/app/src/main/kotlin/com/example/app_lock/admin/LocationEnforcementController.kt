package com.example.app_lock.admin

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Forces the device location (GPS) ON while the Installment Locker's
 * "Uninstall Block" protection is active, so the device stays trackable.
 *
 * ## Why this exists
 *
 * Android provides **no** user-restriction that locks location *ON*.
 * `UserManager.DISALLOW_SHARE_LOCATION` does the *opposite* — it forces
 * location OFF and grays out the toggle (a privacy restriction). Therefore,
 * to keep location on we must:
 *
 * 1. **Force-enable** location via [DevicePolicyManager.setSecureSetting]
 *    (Device Owner only). This sets the system-wide location mode to
 *    "high accuracy" (or flips the `LOCATION_ENABLED` flag on API 28+).
 * 2. **Periodically re-apply** the setting via [LocationEnforcementWorker],
 *    because the user can still toggle location off in Settings — there is no
 *    restriction that locks the toggle ON. The worker re-enables it within
 *    ~15 minutes (WorkManager's minimum periodic interval).
 * 3. **Watch in real time** via [startRealtimeWatch], which delegates to the
 *    standalone [LocationProviderChangeReceiver] — a runtime
 *    [android.content.BroadcastReceiver] for
 *    `android.location.PROVIDERS_CHANGED` that re-applies [apply] the instant
 *    the user toggles location off, closing the ~15-minute gap left by the
 *    periodic worker. The worker re-registers the receiver on each tick so it
 *    self-heals after process death.
 *
 * ## Capability
 *
 * `setSecureSetting` requires the caller to be the **Device Owner** (a Profile
 * Owner cannot set `Settings.Secure` values, and a plain Device Admin cannot
 * either). [isSupported] therefore returns `true` only when
 * [DevicePolicyManager.isDeviceOwnerApp] is true. The caller (typically
 * [UninstallBlockController]) must also confirm the admin component is active.
 *
 * ## API-level handling
 *
 * - **API < 28**: location is controlled by `Settings.Secure.LOCATION_MODE`,
 *   which accepts `LOCATION_MODE_HIGH_ACCURACY` (3). `setSecureSetting`
 *   writes this value on behalf of the Device Owner.
 * - **API >= 28**: `LOCATION_MODE` was deprecated; location is controlled by
 *   the `Settings.Secure.LOCATION_ENABLED` boolean ("1" / "0"). We set both
 *   keys for robustness — writing `LOCATION_MODE` is a harmless no-op on newer
 *   OS versions and writing `LOCATION_ENABLED` is the authoritative switch.
 *
 * Construction mirrors [DeviceAdminController]: MainActivity injects the
 * application context; the controller is safe to construct from the service
 * path too.
 */
class LocationEnforcementController(
    private val context: Context,
) {

    companion object {
        private const val TAG = "LocationEnforceCtrl"

        /**
         * WorkManager unique-work name for the periodic re-apply worker.
         *
         * Used by both [LocationEnforcementController] (to enqueue/cancel) and
         * [LocationEnforcementWorker] (it does not need the name itself, but it
         * is documented here as the single source of truth).
         */
        const val WORK_NAME = "app_lock_location_enforcement"

        /**
         * Re-apply interval (ms). WorkManager enforces a minimum of 15 minutes
         * for periodic work; this value is the documented target.
         */
        const val REAPPLY_INTERVAL_MINUTES = 15L

        /**
         * `Settings.Secure.LOCATION_ENABLED` key — the authoritative location
         * on/off switch on API 28+ (Pie).
         *
         * This is a `@hide` constant (value `"location_enabled"`) not exposed
         * in the public SDK, so the raw string value is used directly — it is
         * stable and documented in the Android source (`frameworks/base`).
         * Mirrors the pattern used for `DISALLOW_CONFIG_APPS` in
         * [UninstallBlockController].
         */
        private const val LOCATION_ENABLED_KEY = "location_enabled"
    }

    private val devicePolicyManager: DevicePolicyManager by lazy {
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    }

    private val adminComponent: ComponentName by lazy {
        AppLockDeviceAdminReceiver.componentName(context)
    }

    /**
     * Returns `true` when this app can force location on.
     *
     * Requires the Device Owner role (for `setSecureSetting`) **and** an active
     * admin component. A Profile Owner or plain Device Admin cannot set
     * `Settings.Secure` values.
     */
    fun isSupported(): Boolean {
        val adminActive = devicePolicyManager.isAdminActive(adminComponent)
        val deviceOwner = devicePolicyManager.isDeviceOwnerApp(context.packageName)
        val supported = adminActive && deviceOwner
        Log.d(
            TAG,
            "isSupported() -> adminActive=$adminActive, deviceOwner=$deviceOwner, supported=$supported",
        )
        return supported
    }

    /**
     * Returns `true` when location is currently enabled at the system level.
     *
     * Reads the live setting (not the enforced intent) so the caller can detect
     * when the user has toggled location off between worker runs.
     */
    fun isLocationEnabled(): Boolean {
        val enabled = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // Settings.Secure.LOCATION_ENABLED is a @hide constant (value
                // "location_enabled") not exposed in the public SDK, so the raw
                // string key is used directly — it is stable and documented in
                // the Android source (`frameworks/base`).
                Settings.Secure.getInt(
                    context.contentResolver,
                    LOCATION_ENABLED_KEY,
                    0,
                ) == 1
            } else {
                Settings.Secure.getInt(
                    context.contentResolver,
                    Settings.Secure.LOCATION_MODE,
                    Settings.Secure.LOCATION_MODE_OFF,
                ) != Settings.Secure.LOCATION_MODE_OFF
            }
        } catch (t: Throwable) {
            Log.w(TAG, "isLocationEnabled() — read failed, assuming off", t)
            false
        }
        Log.d(TAG, "isLocationEnabled() -> $enabled")
        return enabled
    }

    /**
     * Force-enables location (high accuracy) via the Device Owner's
     * `setSecureSetting` privilege.
     *
     * Writes both `LOCATION_MODE` (for API < 28) and `LOCATION_ENABLED`
     * (for API >= 28) so the setting is authoritative on every OS version the
     * app targets. Writing the deprecated key on newer versions is a harmless
     * no-op.
     *
     * @return `true` when the setting was written; `false` when the app is not
     * a Device Owner, the admin is not active, or the call threw.
     */
    fun apply(): Boolean {
        if (!isSupported()) {
            Log.w(TAG, "apply() -> not supported (not Device Owner / admin inactive)")
            return false
        }
        return try {
            // 1. LOCATION_MODE = high accuracy (API < 28 authoritative key;
            //    harmless no-op on newer versions).
            devicePolicyManager.setSecureSetting(
                adminComponent,
                Settings.Secure.LOCATION_MODE,
                Settings.Secure.LOCATION_MODE_HIGH_ACCURACY.toString(),
            )

            // 2. LOCATION_ENABLED = 1 (API >= 28 authoritative key).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                devicePolicyManager.setSecureSetting(
                    adminComponent,
                    LOCATION_ENABLED_KEY,
                    "1",
                )
            }

            Log.i(TAG, "Location forced ON (high accuracy)")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "apply() — setSecureSetting failed", t)
            false
        }
    }

    /**
     * Releases forced-location control.
     *
     * There is nothing to "undo" at the policy level — `setSecureSetting` writes
     * a value but does not install a persistent restriction, so when the
     * Installment Locker no longer wants to enforce location we simply stop
     * re-applying it (the periodic worker is cancelled by the caller). The user
     * regains full control of the location toggle immediately.
     *
     * This method is intentionally a no-op log point: it documents the contract
     * (release = stop enforcing) without mutating settings, so the user's last
     * chosen location state is preserved rather than being forced off.
     */
    fun release() {
        Log.i(TAG, "release() — location enforcement stopped; user regains control")
    }

   /**
    * Enqueues the periodic [LocationEnforcementWorker] that re-applies the
    * forced location-on setting.
    *
    * Uses [ExistingPeriodicWorkPolicy.KEEP] so calling this multiple times
    * (e.g. if the block is toggled on, off, then on again) does not create
    * duplicate workers — the existing periodic work, if any, is kept.
    *
    * The interval is [REAPPLY_INTERVAL_MINUTES] (15 minutes), which is
    * WorkManager's enforced minimum for periodic work. A short initial delay
    * is not set because [apply] is called synchronously by the caller before
    * scheduling, so the first worker run is only a re-apply ~15 minutes later.
    */
   fun scheduleReapply() {
       val request = PeriodicWorkRequestBuilder<LocationEnforcementWorker>(
           REAPPLY_INTERVAL_MINUTES,
           TimeUnit.MINUTES,
       ).build()

       WorkManager.getInstance(context).enqueueUniquePeriodicWork(
           WORK_NAME,
           ExistingPeriodicWorkPolicy.KEEP,
           request,
       )
       Log.i(TAG, "scheduleReapply() — periodic worker enqueued (${REAPPLY_INTERVAL_MINUTES} min)")
   }

   /**
    * Cancels the periodic [LocationEnforcementWorker].
    *
    * Called when the Installment Locker's "Uninstall Block" is turned OFF (or
    * the Device Owner role is cleared). After cancellation the user regains
    * full control of the location toggle.
    */
   fun cancelReapply() {
       WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
       Log.i(TAG, "cancelReapply() — periodic worker cancelled")
   }

    /**
     * Starts the real-time location watch.
     *
     * Delegates to [LocationProviderChangeReceiver.register], which registers
     * a runtime [android.content.BroadcastReceiver] for
     * `android.location.PROVIDERS_CHANGED` so that the instant the user
     * toggles location off in Quick Settings or System Settings, [apply] is
     * called to force it back on — closing the ~15-minute gap left by the
     * periodic [LocationEnforcementWorker].
     *
     * Idempotent: the registration state is process-global (in the receiver's
     * companion object), so calling this from multiple controller instances
     * (MainActivity, the foreground service, the worker, the
     * [AppLockDeviceAdminReceiver]) never leaks duplicate receivers.
     *
     * A no-op when [isSupported] is `false` (not Device Owner / admin
     * inactive), so it is safe to call from
     * [AppLockDeviceAdminReceiver.onEnabled] before the Device Owner role has
     * been granted.
     *
     * Called by:
     * - [AppLockDeviceAdminReceiver.onEnabled] /
     *   [AppLockDeviceAdminReceiver.onProfileProvisioningComplete] when the
     *   Device Owner role is granted.
     * - [UninstallBlockController.setBlocked] when the block is turned ON.
     * - [LocationEnforcementWorker.doWork] on each 15-minute tick (self-heal
     *   after process death).
     */
    fun startRealtimeWatch() {
        if (!isSupported()) {
            Log.w(TAG, "startRealtimeWatch() — not supported (not Device Owner / admin inactive)")
            return
        }
        LocationProviderChangeReceiver.register(context)
    }

    /**
     * Stops the real-time location watch.
     *
     * Delegates to [LocationProviderChangeReceiver.unregister]. Idempotent —
     * safe to call when the watch is not running.
     *
     * Called by [UninstallBlockController.setBlocked] when the block is turned
     * OFF (before the Device Owner role is cleared), so the receiver does not
     * fire after the app loses the privilege needed to call [apply].
     */
    fun stopRealtimeWatch() {
        LocationProviderChangeReceiver.unregister(context)
    }
}
