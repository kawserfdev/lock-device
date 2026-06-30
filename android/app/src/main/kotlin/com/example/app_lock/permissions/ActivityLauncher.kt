package com.example.app_lock.permissions

import android.content.Intent

/**
 * Abstraction over launching an [Intent] via an [android.app.Activity].
 *
 * Why an interface instead of calling `context.startActivity` directly? The
 * [com.example.app_lock.channels.handlers.PermissionHandler] must not hold a
 * reference to an Activity (Activities are short-lived and leak-prone), and a
 * plain [android.content.Context] cannot launch an
 * `ACTION_ADD_DEVICE_ADMIN` intent (it requires the `FLAG_ACTIVITY_NEW_TASK`
 * dance and yields a poor UX). By depending on this tiny interface, the handler
 * stays testable and the Activity is the only place that owns the launch.
 */
fun interface ActivityLauncher {

    /**
     * Starts an activity for the given [intent].
     *
     * @return `true` if the launch was dispatched, `false` if no activity could
     * handle it (e.g. the system admin screen is unavailable).
     */
    fun launch(intent: Intent): Boolean
}
