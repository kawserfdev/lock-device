package com.example.app_lock.channels

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.app_lock.admin.EventPublisher
import com.example.app_lock.util.Constants
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel

/**
 * Thread-safe bridge / event channel wrapper that buffers native events
 * emitted before the Flutter [MainActivity] attaches (P-06, P-07).
 *
 * Implements [EventChannel.StreamHandler] to hook into the Flutter Engine,
 * and [EventPublisher] to allow native controllers to trigger notifications.
 */
class EventBridge : EventPublisher, EventChannel.StreamHandler {

    companion object {
        private const val TAG = "EventBridge"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val eventBuffer = mutableListOf<Map<String, Any?>>()

    @Volatile
    private var sink: EventChannel.EventSink? = null

    /**
     * Registers this StreamHandler on a binary messenger.
     */
    fun register(messenger: BinaryMessenger) {
        EventChannel(messenger, Constants.EVENT_CHANNEL_NAME)
            .setStreamHandler(this)
    }

    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        mainHandler.post {
            sink = events
            Log.d(TAG, "EventChannel listener attached")
            if (events != null) {
                synchronized(eventBuffer) {
                    Log.d(TAG, "Flushing ${eventBuffer.size} buffered events to Flutter")
                    for (event in eventBuffer) {
                        events.success(event)
                    }
                    eventBuffer.clear()
                }
            }
        }
    }

    override fun onCancel(arguments: Any?) {
        mainHandler.post {
            sink = null
            Log.d(TAG, "EventChannel listener detached")
        }
    }

    private fun emit(event: Map<String, Any?>) {
        mainHandler.post {
            val currentSink = sink
            if (currentSink != null) {
                currentSink.success(event)
            } else {
                synchronized(eventBuffer) {
                    if (sink == null) {
                        Log.d(TAG, "Flutter detached. Buffering event: $event")
                        eventBuffer.add(event)
                    } else {
                        sink?.success(event)
                    }
                }
            }
        }
    }

    override fun emitPermissionChanged(granted: Boolean) {
        emit(
            mapOf(
                Constants.KEY_EVENT_TYPE to Constants.EVENT_PERMISSION_CHANGED,
                Constants.KEY_GRANTED to granted,
            ),
        )
    }

    override fun emitLockResult(result: String) {
        emit(
            mapOf(
                Constants.KEY_EVENT_TYPE to Constants.EVENT_LOCK_RESULT,
                Constants.KEY_RESULT to result,
            ),
        )
    }

    override fun emitServiceStateChanged(status: String) {
        emit(
            mapOf(
                Constants.KEY_EVENT_TYPE to Constants.EVENT_SERVICE_STATE_CHANGED,
                Constants.KEY_STATUS to status,
            ),
        )
    }

    override fun emitLockoutStateChanged(active: Boolean) {
        emit(
            mapOf(
                Constants.KEY_EVENT_TYPE to Constants.EVENT_LOCKOUT_STATE_CHANGED,
                Constants.KEY_ACTIVE to active,
            ),
        )
    }

    override fun emitUninstallBlockChanged(blocked: Boolean) {
        emit(
            mapOf(
                Constants.KEY_EVENT_TYPE to Constants.EVENT_UNINSTALL_BLOCK_CHANGED,
                Constants.KEY_BLOCKED to blocked,
            ),
        )
    }

    override fun emitLocationComplianceStateChanged(state: String) {
        emit(
            mapOf(
                Constants.KEY_EVENT_TYPE to "locationComplianceStateChanged",
                "state" to state,
            ),
        )
    }
}
