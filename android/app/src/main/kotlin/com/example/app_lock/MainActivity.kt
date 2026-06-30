package com.example.app_lock

import android.content.Intent
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import com.example.app_lock.admin.DeviceAdminController
import com.example.app_lock.admin.UninstallBlockController
import com.example.app_lock.channels.LockEventChannel
import com.example.app_lock.channels.LockMethodChannel
import com.example.app_lock.channels.handlers.AppLockHandler
import com.example.app_lock.channels.handlers.PermissionHandler
import com.example.app_lock.channels.handlers.UninstallHandler
import com.example.app_lock.permissions.ActivityLauncher
import com.example.app_lock.permissions.PermissionEventBus
import com.example.app_lock.service.LockoutManager

/**
 * Host activity for the Flutter UI.
 *
 * Per the architecture review §3.2, this class contains **registration only** —
 * no business logic. Its jobs are:
 * 1. Construct the native collaborators (event channel, lock engine, handlers,
 *    method channel) and register them on the [FlutterEngine]'s binary messenger
 *    inside [configureFlutterEngine].
 * 2. Provide an [ActivityLauncher] so [PermissionHandler] can fire the system
 *    device-admin enable screen without holding an Activity reference itself.
 * 3. Bridge [PermissionEventBus] (fed by the system-instantiated
 *    [com.example.app_lock.admin.AppLockDeviceAdminReceiver]) onto the
 *    [LockEventChannel] so Flutter receives `permissionChanged` events.
 *
 * The Dart side discovers these channels by name (see
 * `lib/core/constants/channel_constants.dart`), so the names used here MUST
 * match `Constants.METHOD_CHANNEL_NAME` / `Constants.EVENT_CHANNEL_NAME`.
 */
class MainActivity : FlutterActivity() {

    private lateinit var eventChannel: LockEventChannel

    /**
     * Forwards [PermissionEventBus] changes onto the Flutter EventChannel.
     *
     * Held as a field so we can unregister it in [onDestroy] and avoid leaking
     * the listener beyond the Activity lifetime.
     */
    private val permissionBusListener = PermissionEventBus.Listener { granted ->
        eventChannel.emitPermissionChanged(granted)
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        val messenger = flutterEngine.dartExecutor.binaryMessenger

        // 1. Event channel — holds the sink handlers push events through.
        //    Also published to the static holder so the system-instantiated
        //    foreground service and the lock engine can emit events without
        //    constructor injection.
        eventChannel = LockEventChannel().also {
            it.register(messenger)
            LockEventChannel.instance = it
        }

        // 2. Bridge the system DeviceAdminReceiver → Flutter EventChannel.
        PermissionEventBus.addListener(permissionBusListener)

        // 3. Initialise the lockout manager with an application context so it
        //    can register its USER_PRESENT receiver and own a lock engine for
        //    re-locking during the post-lock lockout window. Idempotent.
        LockoutManager.init(this)

        // 4. Native collaborators.
        val lockEngine = DeviceAdminController(context = this, eventChannel = eventChannel)
        val uninstallController = UninstallBlockController(context = this, eventChannel = eventChannel)

        // 5. Activity launcher — lets the permission handler start the system
        //    admin screen without owning an Activity reference.
        val activityLauncher = ActivityLauncher { intent ->
            return@ActivityLauncher try {
                startActivity(intent)
                true
            } catch (t: Throwable) {
                false
            }
        }

        // 6. Channel handlers — thin adapters over the collaborators.
        val permissionHandler = PermissionHandler(
            context = this,
            eventChannel = eventChannel,
            launcher = activityLauncher,
        )
        val appLockHandler = AppLockHandler(
            context = this,
            eventChannel = eventChannel,
            lockEngine = lockEngine,
        )
        val uninstallHandler = UninstallHandler(uninstallController)

        // 7. Method channel — owns the dispatch table.
        LockMethodChannel(
            permissionHandler = permissionHandler,
            appLockHandler = appLockHandler,
            uninstallHandler = uninstallHandler,
        ).register(messenger)
    }

    override fun onDestroy() {
        PermissionEventBus.removeListener(permissionBusListener)
        // Drop the static reference so the service does not keep emitting into
        // a detached sink after the Activity is gone.
        LockEventChannel.instance = null
        super.onDestroy()
    }
}
