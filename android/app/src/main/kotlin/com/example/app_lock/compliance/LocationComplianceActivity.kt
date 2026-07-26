package com.example.app_lock.compliance

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.core.content.ContextCompat
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

    /**
     * `true` once the Lock Task pin has been released (either via the
     * auto-dismiss path or via [onDestroy]). Guards [stopLockTaskMode] so the
     * pin is released exactly once and the allowlist is cleared exactly once,
     * even if both the synchronous release path and `onDestroy` run.
     */
    @Volatile
    private var lockTaskReleased = false

    /** Polls location state every [POLL_INTERVAL_MS]; auto-dismisses when ON. */
    private val locationPollRunnable = object : Runnable {
        override fun run() {
            refreshStatus()
            if (checkAndDismissIfLocationEnabled(tag = "locationPoll")) {
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

        // Intercept the back gesture so the screen cannot be dismissed by Back.
        onBackPressedDispatcher.addCallback(this) {
            Log.d(TAG, "back pressed — consumed (non-dismissible compliance screen)")
            // Consume the back press; do nothing.
        }

        // Pin via Lock Task Mode so Home / Recents cannot dismiss the screen.
        startLockTaskMode()
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
     * silently into the strong `LOCK_TASK_MODE_LOCKED` state), then calls
     * [startLockTask]. If the allowlist is not set (e.g. not Device Owner),
     * `startLockTask` would show the user-confirm dialog and pin into the weak
     * `LOCK_TASK_MODE_PINNED` state (screen pinning) — in that case we skip
     * pinning and rely on the back-gesture interception alone, because screen
     * pinning lets the user escape by holding Back + Recents.
     *
     * After pinning, logs the actual [LockTaskController.getLockTaskModeStateName]
     * so we can verify the strong mode was entered. Only
     * `LOCK_TASK_MODE_LOCKED` (state == 2) fully blocks Home / Recents.
     */
    private fun startLockTaskMode() {
        val controller = LocationComplianceCoordinator.lockTaskController
        val supported = controller.isSupported()
        
        // Arm as a fallback/safety measure (in case it wasn't armed before).
        val armed = controller.arm()
        
        Log.i(
            TAG,
            "startLockTaskMode — supported=$supported, armed=$armed",
        )
        if (!supported) {
            Log.w(TAG, "startLockTaskMode — Lock Task not supported (not Device Owner or admin inactive)")
            return
        }
        
        runCatching {
            startLockTask()
            Log.i(TAG, "startLockTaskMode — startLockTask() called successfully")
        }.onFailure { Log.e(TAG, "startLockTaskMode — startLockTask failed", it) }
        
        // Log the actual mode we ended up in. This is the key diagnostic: if
        // it says "PINNED" instead of "LOCKED", Home/Recents are NOT blocked.
        mainHandler.postDelayed({
            Log.i(TAG, "startLockTaskMode — actual state: ${controller.getLockTaskModeStateName()}")
        }, 500)
    }

    /**
     * Unpins this Activity via Lock Task Mode (best-effort).
     *
     * **Order matters:** `stopLockTask()` MUST be called BEFORE `disarm()`.
     * `stopLockTask()` releases the pin while the app is still allowlisted;
     * calling `disarm()` (which clears the allowlist) first can leave the
     * device in an inconsistent state where the pin cannot be cleanly
     * released. The previous implementation called `disarm()` from the
     * coordinator BEFORE `stopLockTask()` ran in `onDestroy`, which is the
     * inverted order — this is now corrected here.
     */
    private fun stopLockTaskMode() {
        if (lockTaskReleased) {
            Log.d(TAG, "stopLockTaskMode — already released; skipping")
            return
        }
        lockTaskReleased = true
        // 1. Release the pin FIRST (while still allowlisted).
        runCatching {
            // stopLockTask() is a safe no-op when not pinned: it throws
            // IllegalStateException, which runCatching captures below.
            stopLockTask()
            Log.i(TAG, "stopLockTaskMode — stopLockTask() called; state=${LocationComplianceCoordinator.lockTaskController.getLockTaskModeStateName()}")
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
        // 2. THEN clear the allowlist (disarm). Idempotent.
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
        // The user may have just returned from the system Location Settings
        // screen (the only navigation allowed while pinned). Check location
        // immediately so that:
        //  - backing out WITH location enabled  -> auto-dismisses instantly
        //    (releases the Lock Task pin so the user can navigate freely);
        //  - backing out WITHOUT enabling location -> stays pinned (the
        //    poller below keeps the screen trapped until GPS is turned on).
        refreshStatus()
        if (checkAndDismissIfLocationEnabled(tag = "onResume")) {
            return // finishing; do not start the pollers
        }
        // Start the pollers.
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
     * Checks whether the device location service is enabled and, if so,
     * auto-dismisses the compliance screen (releasing the Lock Task pin so
     * the user can navigate freely).
     *
     * This is the **kiosk release gate**: while the compliance Activity is
     * pinned the user may only reach Location Settings (via the "Enable
     * Location" button). On return, this check runs immediately in
     * [onResume] and again every [POLL_INTERVAL_MS] via [locationPollRunnable].
     * If GPS is on the screen finishes and the pin is released; if GPS is
     * still off the screen stays pinned and the user remains trapped.
     *
     * @param tag a log label identifying the caller ([onResume] vs. the poller).
     * @return `true` when location is enabled and the screen is finishing.
     */
    private fun checkAndDismissIfLocationEnabled(tag: String): Boolean {
        if (!LocationComplianceCoordinator.isLocationEnabled()) {
            return false
        }
        Log.i(TAG, "$tag — location ON; auto-dismissing (releasing kiosk pin)")
        // Notify the coordinator to clean up its state machine, cancel timers,
        // and broadcast DISMISS (for any other listeners). The coordinator's
        // onLocationEnabled() deliberately does NOT call releaseLockTask()
        // (disarm) anymore — the Activity owns the FULL release sequence
        // (stopLockTask() THEN disarm()) in the correct order. We do NOT rely
        // on the async DISMISS broadcast to finish this Activity — we call
        // finishCompliance() directly below so the release is synchronous and
        // deterministic (the previous implementation could get stuck pinned if
        // the broadcast was lost, and disarming before stopLockTask() could
        // leave the pin in an inconsistent state).
        LocationComplianceCoordinator.onLocationEnabled()
        // Synchronously finish + release the pin. finishCompliance() removes
        // the pollers and calls finish(); onDestroy() then calls
        // stopLockTaskMode() which calls stopLockTask() THEN disarm().
        finishCompliance()
        return true
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
