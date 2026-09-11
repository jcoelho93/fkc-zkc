package com.mindfulscroll.app.reflection

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.mindfulscroll.app.MainActivity
import com.mindfulscroll.app.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The weekly "your reflection is there" notification, and nothing else: this app posts no other
 * notification.
 *
 * Delivery is deliberately low-key (#33). A notification that invites reflection but arrives
 * like an alert would reproduce the notification-driven checking this app exists to interrupt:
 *
 * - IMPORTANCE_LOW: no sound, no vibration, no heads-up. It waits in the shade.
 * - No launcher badge, on the channel and on the notification.
 * - No accent colour and a plain ring icon: nothing alarm-coded.
 * - One fixed id, so a week that was not looked at is replaced, never stacked into a pile.
 * - No delete intent and no follow-up. Dismissing it is the end of it until the next week.
 */
@Singleton
class ReflectionNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val manager = NotificationManagerCompat.from(context)

    fun ensureChannel() {
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName("Weekly reflection")
                .setDescription("Once a week, when the reflection has something in it.")
                .setShowBadge(false)
                .setVibrationEnabled(false)
                .setLightsEnabled(false)
                .build(),
        )
    }

    /**
     * Whether a post can actually reach the screen: the runtime permission on API 33+, the app's
     * notifications not switched off in Android settings, and this channel not blocked. Checked
     * before posting so a blocked prompt is recorded as blocked, rather than as a post that
     * silently went nowhere.
     */
    fun blockedReason(): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return "POST_NOTIFICATIONS not granted"
        }
        if (!manager.areNotificationsEnabled()) return "notifications switched off for the app in Android settings"
        val channel = manager.getNotificationChannelCompat(CHANNEL_ID)
        if (channel != null && channel.importance == NotificationManagerCompat.IMPORTANCE_NONE) {
            return "weekly reflection channel switched off in Android settings"
        }
        return null
    }

    /**
     * Posts the notification and reports whether it is actually showing afterwards. notify()
     * returning is only evidence it was attempted - the same distinction as overlay windows
     * added versus drawn.
     */
    // Lint cannot follow the permission check into blockedReason(); it is the first thing here.
    @SuppressLint("MissingPermission")
    fun post(): Boolean {
        ensureChannel()
        // Checked again, not only by the caller: the permission can be revoked in between.
        if (blockedReason() != null) return false
        manager.notify(NOTIFICATION_ID, build())
        val system = context.getSystemService(NotificationManager::class.java)
        return system.activeNotifications.any { it.id == NOTIFICATION_ID }
    }

    internal fun build() = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification_reflection)
        .setContentTitle("Weekly reflection")
        .setContentText("The past 7 days: what you opened apps for, and what you felt you got.")
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setBadgeIconType(NotificationCompat.BADGE_ICON_NONE)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setAutoCancel(true)
        .setContentIntent(openReflectionIntent())
        .build()

    private fun openReflectionIntent(): PendingIntent =
        PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_REFLECTION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    companion object {
        const val CHANNEL_ID = "weekly_reflection"
        const val NOTIFICATION_ID = 6
    }
}
