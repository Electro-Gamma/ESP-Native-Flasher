package io.espflasher.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.espflasher.app.R
import io.espflasher.app.ui.MainActivity

/**
 * A minimal started (not bound) foreground service whose only job is to give
 * the process foreground priority - and a visible, honest notification -
 * while a device is connected or a flash operation is running. The actual
 * work (FlashOperationManager, the coroutine doing the flashing) still lives
 * in MainViewModel/viewModelScope exactly as before; this service doesn't
 * duplicate any of that. It exists purely so Android doesn't kill the app
 * process for being "in the background" in the middle of a multi-minute
 * erase or flash.
 *
 * MainViewModel starts this when a connection is established and stops it on
 * disconnect, updating its status text as operations progress.
 *
 * [ArduinoHexUploadFragment][io.espflasher.app.arduinohex.ArduinoHexUploadFragment]
 * shares this exact same service/notification/channel for its own
 * Upload/Read/Verify operations rather than standing up a second one - one
 * visible session notification for the whole app, not two competing ones.
 * Since a `Service` is a single instance per process, [start]/[stop] are
 * keyed by [owner] and reference-counted (see [activeOwners]) so the AVR
 * screen finishing its upload can't accidentally kill the notification the
 * ESP screen still needs for a live connection, or vice versa - the service
 * only actually stops once every owner that asked for it has let go.
 */
class FlashForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "esp_flasher_session"
        private const val NOTIFICATION_ID = 1001
        private const val EXTRA_STATUS = "status"
        private const val ACTION_STOP_OPERATION = "io.espflasher.app.action.STOP_OPERATION"

        /** Caller identifiers for the reference-counting described above. */
        const val OWNER_ESP = "esp"
        const val OWNER_AVR = "avr"

        /** Owners currently holding this service open - see the class doc comment. */
        private val activeOwners = mutableSetOf<String>()

        fun start(context: Context, status: String, owner: String = OWNER_ESP) {
            activeOwners.add(owner)
            val intent = Intent(context, FlashForegroundService::class.java).putExtra(EXTRA_STATUS, status)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        /** Same entry point as [start] - onStartCommand re-posts the notification with the new text. */
        fun updateStatus(context: Context, status: String, owner: String = OWNER_ESP) = start(context, status, owner)

        /**
         * Releases [owner]'s hold on the service; only actually stops it
         * (and drops the notification) once no other owner is still using
         * it. Safe to call even if [owner] never called [start] - removing
         * an absent entry from [activeOwners] is a no-op.
         */
        fun stop(context: Context, owner: String = OWNER_ESP) {
            activeOwners.remove(owner)
            if (activeOwners.isEmpty()) {
                context.stopService(Intent(context, FlashForegroundService::class.java))
            }
        }

        /**
         * MainViewModel points this at its own [io.espflasher.app.ui.MainViewModel.stop]
         * while it's alive (set in its init block, cleared in onCleared) so the
         * notification's Stop action has an actual running operation to cancel -
         * this service never touches [io.espflasher.app.flashing.FlashOperationManager]
         * itself, same division of responsibility as the rest of this class's doc
         * comment describes. A tap with nothing set (or nothing running) is a no-op.
         *
         * The AVR screen deliberately never assigns this: the underlying
         * `arduino-hex-upload` library (UsbSerialManager/UsbaspManager/
         * ArduinoIspManager) has no cancel hook for an in-progress
         * upload/read/verify, so there is nothing real to wire the button to
         * there - only a fake one that would look like it cancels without
         * actually stopping anything. While an AVR operation is showing this
         * notification, the Stop action still reflects whatever the ESP
         * screen last set here (or does nothing, if that's null) - a known,
         * intentionally-accepted limitation of this being one shared
         * callback slot rather than per-owner ones. Adding real AVR
         * cancellation would mean changing that reference library, which
         * this Activity's own doc comment deliberately avoids.
         */
        var onStopRequested: (() -> Unit)? = null
    }

    override fun onCreate() {
        super.onCreate()
        createChannelIfNeeded()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_OPERATION) {
            // The service is already running in the foreground at this point
            // (that's the only time this notification - and so this action -
            // exists), so this is just delivering an Intent to an existing
            // Service, not starting a new one; no startForeground() call is
            // required for this branch.
            onStopRequested?.invoke()
            return START_NOT_STICKY
        }
        val status = intent?.getStringExtra(EXTRA_STATUS) ?: getString(R.string.notification_status_idle)
        val notification = buildNotification(status)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(status: String): Notification {
        // Always opens the ESP Flasher page (MainActivity), even when an AVR
        // operation is what's actually showing this notification - one
        // shared notification pointing at one home base, rather than two
        // different tap destinations depending on which owner posted it.
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, FlashForegroundService::class.java).setAction(ACTION_STOP_OPERATION),
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openAppIntent)
            .addAction(R.drawable.ic_stop, getString(R.string.action_stop), stopIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID, getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
            }
            manager.createNotificationChannel(channel)
        }
    }
}
