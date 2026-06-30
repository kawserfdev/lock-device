package com.example.app_lock.channels

import android.util.Log
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import com.example.app_lock.util.Constants

/**
 * Wraps the Flutter [EventChannel] used to push native → Flutter status updates.
 *
 * The Flutter side subscribes to `app_lock/events` and filters incoming maps by
 * their `type` field (see `NativeDataSource` in the Dart layer). This class is
 * the only place on the native side that holds the live [EventSink], so any
 * component that needs to notify Flutter does so through [emit].
 *
 * Threading contract: Flutter invokes `onListen` on the platform thread. We
 * therefore confine all [emit] calls to that same thread by posting through the
 * supplied [MethodChannel] task queue is unnecessary here — EventSink is
 * thread-safe to call from any thread, but we still guard against a null sink
 * (subscription cancelled / never started).
 *
 * No business logic: this is a thin transport.
 */
class LockEventChannel : EventChannel.StreamHandler {

    companion object {
        private const val TAG = "LockEventChannel"

        /**
         * Process-wide singleton reference to the active [LockEventChannel].
         *
         * Set by [com.example.app_lock.MainActivity] during channel registration
         * and cleared in `onDestroy`.
         *
         * Why this exists: the foreground service is **system-instantiated**
         * (Android creates it via `startForegroundService`), so it cannot
         * receive the channel through constructor injection. The service and
         * [com.example.app_lock.admin.DeviceAdminController] reach Flutter
         * through this holder instead.
         *
         * Volatile because it is written on the platform thread (during
         * `configureFlutterEngine` / `onDestroy`) but read from the service's
         * main-thread callbacks and potentially the timer callback.
         *
         * When `null` (Flutter not running), [emit] calls become no-ops — the
         * lock still executes, Flutter simply does not get notified. This
         * matches the architecture invariant §9.2: "Events dropped (not
         * buffered) when Flutter absent — locking unaffected."
         */
        @Volatile
        @JvmStatic
        var instance: LockEventChannel? = null
    }

    /**
     * The active sink, or `null` when Flutter has no listener attached.
     *
     * Volatile because [emit] may be called from any thread (e.g. a service
     * callback) while [onCancel] runs on the platform thread.
     */
    @Volatile
    private var sink: EventChannel.EventSink? = null

    /**
     * Registers this handler on a freshly created [EventChannel] bound to the
     * given binary messenger (typically the FlutterEngine's).
     */
    fun register(messenger: io.flutter.plugin.common.BinaryMessenger) {
        EventChannel(messenger, Constants.EVENT_CHANNEL_NAME)
            .setStreamHandler(this)
    }

    /**
     * Called by Flutter when a listener subscribes. We stash the sink so that
     * [emit] can push maps to Dart.
     */
    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        sink = events
        Log.d(TAG, "EventChannel listener attached")
    }

    /**
     * Called by Flutter when the listener cancels the subscription. We drop the
     * sink so subsequent [emit] calls become no-ops instead of crashing.
     */
    override fun onCancel(arguments: Any?) {
        sink = null
        Log.d(TAG, "EventChannel listener cancelled")
    }

    /**
     * Pushes an event map to Flutter if a listener is attached.
     *
     * Safe to call from any thread; silently no-ops when there is no sink.
     */
    fun emit(event: Map<String, Any?>) {
        sink?.success(event)
    }

    /**
     * Pushes an error to Flutter if a listener is attached.
     *
     * Used to surface unrecoverable native failures on the event stream.
     */
    fun emitError(code: String, message: String, details: Any? = null) {
        sink?.error(code, message, details)
    }

    /**
     * Emits a `permissionChanged` event.
     *
     * @param granted `true` when device-admin is active, `false` otherwise.
     */
    fun emitPermissionChanged(granted: Boolean) {
        emit(
            mapOf(
                Constants.KEY_EVENT_TYPE to Constants.EVENT_PERMISSION_CHANGED,
                Constants.KEY_GRANTED to granted,
            ),
        )
    }

    /**
     * Emits a `lockResult` event.
     *
     * @param result one of the `Constants.LOCK_RESULT_*` strings.
     */
    fun emitLockResult(result: String) {
        emit(
            mapOf(
                Constants.KEY_EVENT_TYPE to Constants.EVENT_LOCK_RESULT,
                Constants.KEY_RESULT to result,
            ),
        )
    }

    /**
     * Emits a `serviceStateChanged` event.
     *
     * @param status one of the `Constants.SERVICE_STATUS_*` strings.
     */
    fun emitServiceStateChanged(status: String) {
        emit(
            mapOf(
                Constants.KEY_EVENT_TYPE to Constants.EVENT_SERVICE_STATE_CHANGED,
                Constants.KEY_STATUS to status,
            ),
        )
    }

    /**
     * Emits a `lockoutStateChanged` event.
     *
     * @param active `true` when the post-lock lockout window opens (unlock
     * attempts will be re-locked), `false` when it elapses.
     */
    fun emitLockoutStateChanged(active: Boolean) {
        emit(
            mapOf(
                Constants.KEY_EVENT_TYPE to Constants.EVENT_LOCKOUT_STATE_CHANGED,
                Constants.KEY_ACTIVE to active,
            ),
        )
    }

    /**
     * Emits an `uninstallBlockChanged` event.
     *
     * @param blocked `true` when the uninstall-block restriction has been
     * applied (the user cannot uninstall the app), `false` when it has been
     * removed (the user may uninstall normally).
     */
    fun emitUninstallBlockChanged(blocked: Boolean) {
        emit(
            mapOf(
                Constants.KEY_EVENT_TYPE to Constants.EVENT_UNINSTALL_BLOCK_CHANGED,
                Constants.KEY_BLOCKED to blocked,
            ),
        )
    }
}
