package com.example.app_lock

import android.content.Intent
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import com.example.app_lock.admin.DeviceAdminOperations
import com.example.app_lock.admin.LocationEnforcementOperations
import com.example.app_lock.admin.UninstallBlockOperations
import com.example.app_lock.admin.UninstallBlockController
import com.example.app_lock.channels.EventBridge
import com.example.app_lock.channels.LockMethodChannel
import com.example.app_lock.channels.handlers.AppLockHandler
import com.example.app_lock.channels.handlers.KioskModeHandler
import com.example.app_lock.channels.handlers.PermissionHandler
import com.example.app_lock.channels.handlers.UninstallHandler
import com.example.app_lock.permissions.ActivityLauncher
import com.example.app_lock.permissions.PermissionEventBus
import com.example.app_lock.service.EngineBootstrap

/**
 * Host activity for the Flutter UI.
 *
 * Per requirements (P-06, P-15):
 * retrieves and attaches EventBridge and operational controllers from [EngineBootstrap].
 */
class MainActivity : FlutterActivity() {

    private lateinit var engineBootstrap: EngineBootstrap
    private var activityLauncher: ActivityLauncher? = null
    private val kioskHandler = KioskModeHandler()

    private val permissionBusListener = PermissionEventBus.Listener { granted ->
        engineBootstrap.eventBridge.emitPermissionChanged(granted)
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        val messenger = flutterEngine.dartExecutor.binaryMessenger
        engineBootstrap = EngineBootstrap.getInstance(this)

        // 1. Register EventBridge
        engineBootstrap.eventBridge.register(messenger)

        // 2. Bridge the system DeviceAdminReceiver → Flutter EventBridge
        PermissionEventBus.addListener(permissionBusListener)

        // 3. Activity launcher
        val launcher = ActivityLauncher { intent ->
            return@ActivityLauncher try {
                startActivity(intent)
                true
            } catch (t: Throwable) {
                false
            }
        }
        activityLauncher = launcher

        // Bind activityLauncher to the singleton UninstallBlockOperations
        engineBootstrap.uninstallBlockOperations.setActivityLauncher(launcher)

        // 4. Channel handlers
        val permissionHandler = PermissionHandler(
            context = this,
            eventPublisher = engineBootstrap.eventBridge,
            launcher = launcher,
        )
        val appLockHandler = AppLockHandler(
            context = this,
            eventPublisher = engineBootstrap.eventBridge,
            lockEngine = engineBootstrap.deviceAdminOperations,
        )
        val uninstallHandler = UninstallHandler(engineBootstrap.uninstallBlockOperations)

        // 6. Kiosk-mode handler — binds this Activity so Lock Task Mode
        //    (screen pinning) can disable the Home / Recents buttons while
        //    the Status ("App Lock Time counter") screen is visible.
        kioskHandler.attachActivity(this)

        // 7. Method channel registration
        LockMethodChannel(
            context = this,
            permissionHandler = permissionHandler,
            appLockHandler = appLockHandler,
            uninstallHandler = uninstallHandler,
            kioskHandler = kioskHandler,
        ).register(messenger)
    }

    override fun onDestroy() {
        PermissionEventBus.removeListener(permissionBusListener)
        // Clean up the activityLauncher attachment to avoid memory leaks
        engineBootstrap.uninstallBlockOperations.setActivityLauncher(null)
        activityLauncher = null
        // Release the kiosk pin (best-effort) and clear the activity reference.
        kioskHandler.disableKioskMode()
        kioskHandler.detachActivity()
        super.onDestroy()
    }
}
