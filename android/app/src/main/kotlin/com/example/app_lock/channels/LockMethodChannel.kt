package com.example.app_lock.channels

import android.content.Context
import android.content.Intent
import android.provider.Settings
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import com.example.app_lock.admin.LocationEnforcementController
import com.example.app_lock.channels.handlers.AppLockHandler
import com.example.app_lock.channels.handlers.KioskModeHandler
import com.example.app_lock.channels.handlers.PermissionHandler
import com.example.app_lock.channels.handlers.UninstallHandler
import com.example.app_lock.util.Constants

/**
 * Wraps the Flutter [MethodChannel] used for request/response commands.
 *
 * This is the *only* place on the native side that owns the [MethodChannel]
 * reference and the dispatch table. Each incoming method name is routed to the
 * appropriate handler ([PermissionHandler] or [AppLockHandler]); the handler's
 * return value is marshalled back to Flutter as the `result`.
 *
 * Threading: Flutter invokes `onMethodCall` on the platform thread, so handlers
 * must not block. Long work belongs in the foreground service.
 *
 * Error model (§4.1): unexpected failures are surfaced as structured
 * `PlatformException{code, message, details}` via `result.error(...)`. Missing
 * methods surface as `notImplemented`.
 */
class LockMethodChannel(
    private val context: Context,
    private val permissionHandler: PermissionHandler,
    private val appLockHandler: AppLockHandler,
    private val uninstallHandler: UninstallHandler,
    private val kioskHandler: KioskModeHandler,
) : MethodChannel.MethodCallHandler {

    /**
     * Registers this handler on a freshly created [MethodChannel] bound to the
     * given binary messenger.
     */
    fun register(messenger: BinaryMessenger) {
        MethodChannel(messenger, Constants.METHOD_CHANNEL_NAME).setMethodCallHandler(this)
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        try {
            val value: Any? = when (call.method) {
                Constants.METHOD_CHECK_PERMISSION -> permissionHandler.checkPermission()
                Constants.METHOD_REQUEST_PERMISSION -> permissionHandler.requestPermission()
                Constants.METHOD_IS_PROVISIONING_ALLOWED -> permissionHandler.isProvisioningAllowed()
                Constants.METHOD_LOCK_NOW -> appLockHandler.lockNow()
                Constants.METHOD_LOCK_FOR_DURATION -> {
                    val raw = call.argument<Number>(Constants.KEY_DURATION_MS)
                    val durationMs = raw?.toLong() ?: Constants.LOCKOUT_DURATION_MS
                    appLockHandler.lockForDuration(durationMs)
                }
                Constants.METHOD_START_SERVICE -> appLockHandler.startService()
                Constants.METHOD_STOP_SERVICE -> appLockHandler.stopService()
                Constants.METHOD_IS_SERVICE_RUNNING -> appLockHandler.isServiceRunning()
                Constants.METHOD_SET_UNINSTALL_BLOCKED -> {
                    val blocked = call.argument<Boolean>(Constants.KEY_BLOCKED) ?: false
                    uninstallHandler.setUninstallBlocked(blocked)
                }
                Constants.METHOD_IS_UNINSTALL_BLOCKED -> uninstallHandler.isUninstallBlocked()
                Constants.METHOD_IS_UNINSTALL_BLOCK_SUPPORTED -> uninstallHandler.isUninstallBlockSupported()
                Constants.METHOD_CHECK_LOCATION_ENABLED -> {
                    LocationEnforcementController.getInstance(context).isLocationEnabled()
                }
                Constants.METHOD_OPEN_LOCATION_SETTINGS -> {
                    runCatching {
                        val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                        true
                    }.getOrElse { false }
                }
                Constants.METHOD_ENABLE_KIOSK_MODE -> kioskHandler.enableKioskMode()
                Constants.METHOD_DISABLE_KIOSK_MODE -> kioskHandler.disableKioskMode()
                else -> {
                    result.notImplemented()
                    return
                }
            }
            result.success(value)
        } catch (t: Throwable) {
            // Structured error so the Dart side receives a PlatformException
            // rather than a silent drop.
            result.error("native_error", t.message ?: "Unknown native error", null)
        }
    }
}
