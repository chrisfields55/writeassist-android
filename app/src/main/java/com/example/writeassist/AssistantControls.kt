package com.example.writeassist

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.view.accessibility.AccessibilityManager
import android.accessibilityservice.AccessibilityServiceInfo

internal object AssistantControls {
    const val ENABLED = "assistant_enabled"
    const val NOTIFICATION = "assistant_notification"
    private const val CHANNEL = "assistant_controls"
    private const val ID = 23

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean(ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putBoolean(ENABLED, enabled).apply()
        TileService.requestListeningState(context, ComponentName(context, AssistantTile::class.java))
    }

    fun serviceEnabled(context: Context): Boolean =
        context.getSystemService(AccessibilityManager::class.java)
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.name == WritingAccessibilityService::class.java.name &&
                it.resolveInfo.serviceInfo.packageName == context.packageName }

    fun cancelNotification(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(ID)
    }

    fun updateNotification(context: Context) {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (!prefs.getBoolean(NOTIFICATION, false) ||
            (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED)) {
            cancelNotification(context)
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Writing assistant controls", NotificationManager.IMPORTANCE_LOW)
        )
        val active = enabled(context)
        val toggle = PendingIntent.getBroadcast(context, 0,
            Intent(context, AssistantToggleReceiver::class.java).putExtra(ENABLED, !active),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        manager.notify(ID, Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_assistant_tile)
            .setContentTitle("WriteAssist")
            .setContentText(if (active) "Writing assistant active" else "Writing assistant paused")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, if (active) "Pause" else "Resume", toggle).build())
            .setOngoing(true)
            .build())
    }
}

class AssistantToggleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AssistantControls.setEnabled(context, intent.getBooleanExtra(AssistantControls.ENABLED, true))
    }
}

class AssistantTile : TileService() {
    override fun onStartListening() = refreshTile()

    override fun onClick() {
        if (AssistantControls.serviceEnabled(this)) {
            AssistantControls.setEnabled(this, !AssistantControls.enabled(this))
        }
        refreshTile()
    }

    private fun refreshTile() {
        qsTile?.apply {
            state = when {
                !AssistantControls.serviceEnabled(this@AssistantTile) -> Tile.STATE_UNAVAILABLE
                AssistantControls.enabled(this@AssistantTile) -> Tile.STATE_ACTIVE
                else -> Tile.STATE_INACTIVE
            }
            updateTile()
        }
    }
}
