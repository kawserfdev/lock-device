package com.example.app_lock.compliance

/**
 * Configuration options for the Location Compliance Enforcement System.
 */
object ComplianceConfig {
    /**
     * Grace period (in milliseconds) the app waits after the user turns OFF
     * Device Location (GPS) before launching the native
     * [com.example.app_lock.compliance.LocationComplianceActivity].
     *
     * Flow:
     *   GPS OFF  →  start grace timer  →  wait LOCATION_OFF_GRACE_PERIOD_MS
     *            →  if GPS turns ON before timeout → cancel timer (no screen)
     *            →  else → launch LocationComplianceActivity
     *
     * This is the SINGLE source of truth for the pre-compliance-screen delay.
     * Change this value to adjust the grace period everywhere in the project.
     * It does NOT affect the countdown timer shown INSIDE the compliance
     * screen (that is [com.example.app_lock.compliance.LocationComplianceCoordinator.COMPLIANCE_LOCK_COUNTDOWN_MS]).
     *
     * Default: 2 minutes (120_000 ms).
     */
    const val LOCATION_OFF_GRACE_PERIOD_MS = 1 * 60 * 1000L
}
