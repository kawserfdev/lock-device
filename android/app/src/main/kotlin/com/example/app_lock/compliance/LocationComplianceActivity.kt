package com.example.app_lock.compliance

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.addCallback
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.example.app_lock.R

/**
 * Full-screen, non-dismissible compliance Activity shown when the user has
 * disabled Device Location and the 2-minute grace countdown has expired.
 *
 * ## UX
 *
 * Material Design layout ([R.layout.activity_location_compliance]) showing:
 * - **Title** — "Location Required".
 * - **Description** — explains that Device Location must remain enabled.
 * - **Countdown** — the remaining time before the device is locked (rendered
 *   every second from [LocationComplianceCoordinator.getCountdownRemainingMs]).
 * - **Current Location Status** — ON / OFF, polled every 2s.
 * - **Device Status** — Locked / Unlocked (from the coordinator state).
 * - **Compliance Status** — the current [LocationComplianceState] name.
 * - **Buttons**:
 *   - *Enable Location* — opens Android Location Settings.
 *   - *Retry Detection* — re-checks location immediately (manual refresh).
 *   - *Emergency Contact* — opens the dialer with a preset number (optional).
 *
 * ## Non-dismissible
 *
 * The back gesture is consumed via [onBackPressedDispatcher.addCallback] so
 * the user cannot dismiss the screen by pressing Back (Android 13+ predictive-
 * back compatible). Lock Task Mode (pinned via [LockTaskController]) hides the
 * Home / Recents buttons so the user cannot leave the screen that way either.
 *
 * ## Auto-dismiss
 *
 * A poller checks [LocationEnforcementController.isLocationEnabled] every 2s.
 * When location is ON, the Activity calls [LocationComplianceCoordinator.onLocationEnabled]
 * (which cleans up the whole enforcement stack) and finishes itself. A
 * [BroadcastReceiver] for [LocationComplianceCoordinator.ACTION_DISMISS_COMPLIANCE]
 * is also registered so the coordinator can dismiss the Activity from outside
 * (e.g. when the worker detects location was restored).
 *
 * ## Show-when-locked / turn-screen-on
 *
 * Uses [setShowWhenLocked] and [setTurnScreenOn] (API 27+ replacements for the
 * deprecated `FLAG_SHOW_WHEN_LOCKED` / `FLAG_TURN_SCREEN_ON`) so the screen
 * appears even when the device is locked. `FLAG_KEEP_SCREEN_ON` keeps the
 * display on while the Activity is visible so the countdown is always
 * readable.
 *
 * ## Android 12–15 notes
 *
 * - `onBackPressedDispatcher.addCallback` is the Android 13+ predictive-back-
 *   compatible way to intercept the back gesture.
 * - `setShowWhenLocked` / `setTurnScreenOn` are the modern (API 27+) APIs.
 * - Starting an activity from the background is permitted for Device Owner
 *   apps, so the coordinator can launch this Activity from a service /
 *   receiver context.
 */
class LocationComplianceActivity : ComponentActivity() {

    companion object {
        private const val TAG = "LocComplianceActivity"

        /** Poll interval (ms) for re-checking location state while visible. */
        private const val POLL_INTERVAL_MS = 2_000L

        /** UI refresh interval (ms) for the countdown text. */
        private const val COUNTDOWN_TICK_MS = 1_000L

        /**
         * Releases Lock Task Mode for the given context (best-effort, from
         * outside the Activity). Called by the coordinator when location is
         * restored.
         */
        fun releaseLockTask(context: Context?) {
            val appContext = context?.applicationContext ?: return
            // The Activity itself calls stopLockTask() in its own onDestroy.
            // From outside, we just disarm the allowlist so a future launch
            // does not re-pin. The Activity's own stopLockTask() handles the
            // in-flight pin.
            runCatching { LockTaskController(appContext).disarm() }
                .onFailure { Log.w(TAG, "releaseLockTask() — disarm failed", it) }
        }
    }



    private lateinit var countdownText: TextView
    private lateinit var locationStatusText: TextView
    private lateinit var deviceStatusText: TextView
    private lateinit var complianceStatusText: TextView

    private val mainHandler = Handler(Looper.getMainLooper())

    private var isPinned = false
    private lateinit var backPressCallback: OnBackPressedCallback

    /** Polls location state every [POLL_INTERVAL_MS]; auto-dismisses when ON. */
    private val locationPollRunnable = object : Runnable {
        override fun run() {
            refreshStatus()
            if (LocationComplianceCoordinator.isLocationEnabled()) {
                Log.i(TAG, "locationPoll — location ON; auto-dismissing")
                LocationComplianceCoordinator.onLocationEnabled()
                return // do not re-post; we are finishing
            }
            mainHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    /** Refreshes the countdown text every [COUNTDOWN_TICK_MS]. */
    private val countdownTickRunnable = object : Runnable {
        override fun run() {
            updateCountdownText()
            mainHandler.postDelayed(this, COUNTDOWN_TICK_MS)
        }
    }

    /** Dismisses the Activity when the coordinator broadcasts DISMISS. */
    private val dismissReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == LocationComplianceCoordinator.ACTION_DISMISS_COMPLIANCE) {
                Log.i(TAG, "DISMISS_COMPLIANCE received — finishing")
                finishCompliance()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "onCreate() — compliance Activity launched")

        // Keep the screen on and show over the lock screen.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        setContentView(R.layout.activity_location_compliance)

        bindViews()
        wireButtons()

        // Initialize back press callback, default to false (disabled).
        // It will be enabled dynamically when the timer is active.
        backPressCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                Log.d(TAG, "back pressed — consumed (non-dismissible compliance screen while timer is running)")
            }
        }
        onBackPressedDispatcher.addCallback(this, backPressCallback)
    }

    private fun bindViews() {
        countdownText = findViewById(R.id.compliance_countdown)
        locationStatusText = findViewById(R.id.compliance_location_status)
        deviceStatusText = findViewById(R.id.compliance_device_status)
        complianceStatusText = findViewById(R.id.compliance_compliance_status)
    }

    private fun wireButtons() {
        findViewById<Button>(R.id.compliance_btn_enable_location)
            .setOnClickListener {
                Log.i(TAG, "Enable Location button tapped — opening Location Settings")
                runCatching {
                    val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                }.onFailure { Log.e(TAG, "failed to open Location Settings", it) }
            }

        findViewById<Button>(R.id.compliance_btn_retry)
            .setOnClickListener {
                Log.d(TAG, "Retry Detection button tapped — refreshing status")
                refreshStatus()
            }

        findViewById<Button>(R.id.compliance_btn_emergency)
            .setOnClickListener {
                Log.i(TAG, "Emergency Contact button tapped — opening dialer")
                runCatching {
                    val intent = Intent(Intent.ACTION_DIAL)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                }.onFailure { Log.e(TAG, "failed to open dialer", it) }
            }
    }

    /**
     * Pins this Activity via Lock Task Mode.
     *
     * First arms the [LockTaskController] allowlist (so `startLockTask` pins
     * silently), then calls [startLockTask]. If the allowlist is not set
     * (e.g. not Device Owner), `startLockTask` would show the user-confirm
     * dialog — in that case we skip pinning and rely on the back-gesture
     * interception alone.
     */
    private fun startLockTaskMode() {
        val armed = LocationComplianceCoordinator.lockTaskController.arm()
        if (!armed || !LocationComplianceCoordinator.lockTaskController.isAllowlisted()) {
            Log.w(TAG, "startLockTaskMode — allowlist not set; skipping pin (non-DO or arm failed)")
            return
        }
        runCatching {
            startLockTask()
            Log.i(TAG, "startLockTaskMode — Lock Task Mode started")
        }.onFailure { Log.e(TAG, "startLockTaskMode — startLockTask failed", it) }
    }

    /**
     * Unpins this Activity via Lock Task Mode (best-effort).
     */
    private fun stopLockTaskMode() {
        runCatching {
            // stopLockTask() is a safe no-op when not pinned: it throws
            // IllegalStateException, which runCatching captures below. We do
            // NOT use ActivityManager.isLockTaskModeActive() (deprecated API 29)
            // or ActivityManager.lockTaskModeState (API 29+) because the
            // try/catch path is simpler and works on every API level.
            stopLockTask()
            Log.i(TAG, "stopLockTaskMode — Lock Task Mode stopped")
        }.onFailure { e ->
            // IllegalStateException is the expected "not pinned" case (e.g. we
            // never started Lock Task Mode, or the user already left it) — log
            // at debug so it does not pollute the log as a warning.
            if (e is IllegalStateException) {
                Log.d(TAG, "stopLockTaskMode — not in Lock Task Mode (no-op)")
            } else {
                Log.w(TAG, "stopLockTaskMode — stopLockTask failed", e)
            }
        }
        runCatching { LocationComplianceCoordinator.lockTaskController.disarm() }
            .onFailure { Log.w(TAG, "stopLockTaskMode — disarm failed", it) }
    }

    override fun onResume() {
        super.onResume()
        // Register the dismiss receiver (NOT_EXPORTED — internal app broadcast).
        ContextCompat.registerReceiver(
            this,
            dismissReceiver,
            IntentFilter(LocationComplianceCoordinator.ACTION_DISMISS_COMPLIANCE),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // Start the pollers.
        refreshStatus()
        updateCountdownText()
        mainHandler.postDelayed(locationPollRunnable, POLL_INTERVAL_MS)
        mainHandler.postDelayed(countdownTickRunnable, COUNTDOWN_TICK_MS)
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(locationPollRunnable)
        mainHandler.removeCallbacks(countdownTickRunnable)
        runCatching { unregisterReceiver(dismissReceiver) }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "onDestroy() — compliance Activity destroyed")
        stopLockTaskMode()
        // Notify the coordinator that the screen was dismissed without
        // location being enabled (e.g. the OS killed the activity, or the
        // user somehow escaped). The coordinator escalates to LOCKED.
        // This is a no-op if location was already restored (state != COMPLIANCE_SCREEN).
        LocationComplianceCoordinator.onComplianceScreenDismissed()
    }

    /**
     * Refreshes the location / device / compliance status fields from the
     * live settings and the coordinator state.
     */
    private fun refreshStatus() {
        val locationOn = LocationComplianceCoordinator.isLocationEnabled()
        locationStatusText.text = getString(
            if (locationOn) R.string.location_compliance_status_on
            else R.string.location_compliance_status_off,
        )
        val state = LocationComplianceCoordinator.getState()
        deviceStatusText.text = getString(
            if (state == LocationComplianceState.LOCKED) R.string.location_compliance_device_locked
            else R.string.location_compliance_device_unlocked,
        )
        complianceStatusText.text = getString(
            R.string.location_compliance_compliance_status,
            state.name,
        )
    }

    /**
     * Updates the countdown text from the coordinator's remaining-ms query.
     */
    private fun updateCountdownText() {
        val remainingMs = LocationComplianceCoordinator.getCountdownRemainingMs()
        countdownText.text = if (remainingMs > 0L) {
            formatCountdown(remainingMs)
        } else {
            getString(R.string.location_compliance_countdown_expired)
        }
        countdownText.visibility = if (remainingMs > 0L) View.VISIBLE else View.GONE

        if (remainingMs > 0L) {
            if (!isPinned) {
                isPinned = true
                Log.i(TAG, "Timer active — disabling/hiding navigation buttons (Lock Task + Immersive Mode)")
                startLockTaskMode()
                hideSystemUI()
                backPressCallback.isEnabled = true
            }
        } else {
            if (isPinned) {
                isPinned = false
                Log.i(TAG, "Timer ended — re-enabling/showing navigation buttons")
                stopLockTaskMode()
                showSystemUI()
                backPressCallback.isEnabled = false
            }
        }
    }

    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        }
    }

    private fun showSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowCompat.setDecorFitsSystemWindows(window, true)
            window.insetsController?.show(WindowInsets.Type.navigationBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    /**
     * Finishes the compliance screen cleanly (used by the dismiss receiver and
     * the auto-dismiss path).
     */
    private fun finishCompliance() {
        mainHandler.removeCallbacks(locationPollRunnable)
        mainHandler.removeCallbacks(countdownTickRunnable)
        finish()
    }

    /**
     * Formats a millisecond duration as `M:SS`.
     */
    private fun formatCountdown(ms: Long): String {
        val totalSeconds = (ms / 1000L).toInt()
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%d:%02d", minutes, seconds)
    }
}
