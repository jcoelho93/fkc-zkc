package com.mindfulscroll.app.reflection

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.mindfulscroll.app.MainActivity
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** #33: the weekly notification arrives the way a reflection should - quietly, once, no badge. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class ReflectionNotifierTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private lateinit var notifier: ReflectionNotifier

    @Before
    fun setUp() {
        notifier = ReflectionNotifier(app)
    }

    @Test
    fun `the channel is low importance, silent and badge-free`() {
        notifier.ensureChannel()

        val channel = manager.getNotificationChannel(ReflectionNotifier.CHANNEL_ID)
        assertThat(channel).isNotNull()
        // Exactly LOW: no sound, no heads-up. Not DEFAULT, which makes a sound.
        assertThat(channel.importance).isEqualTo(NotificationManager.IMPORTANCE_LOW)
        assertThat(channel.canShowBadge()).isFalse()
        assertThat(channel.shouldVibrate()).isFalse()
        assertThat(channel.shouldShowLights()).isFalse()
    }

    @Test
    fun `the notification is low priority, uncoloured, and opens the reflection`() {
        notifier.ensureChannel()
        val notification = notifier.build()

        assertThat(notification.channelId).isEqualTo(ReflectionNotifier.CHANNEL_ID)
        @Suppress("DEPRECATION")
        assertThat(notification.priority).isEqualTo(NotificationCompat.PRIORITY_LOW)
        assertThat(notification.badgeIconType).isEqualTo(Notification.BADGE_ICON_NONE)
        assertThat(notification.color).isEqualTo(Notification.COLOR_DEFAULT)
        assertThat(notification.flags and Notification.FLAG_AUTO_CANCEL).isNotEqualTo(0)
        assertThat(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE).isNotEqualTo(0)
        // No delete intent: swiping it away triggers nothing, so nothing can follow it up.
        assertThat(notification.deleteIntent).isNull()

        val intent = shadowOf(notification.contentIntent).savedIntent
        assertThat(intent.component?.className).isEqualTo(MainActivity::class.java.name)
        assertThat(intent.getStringExtra(MainActivity.EXTRA_OPEN)).isEqualTo(MainActivity.OPEN_REFLECTION)
    }

    @Test
    fun `the copy states what is there and nothing more`() {
        val extras = notifier.build().extras
        val text = "${extras.getCharSequence(Notification.EXTRA_TITLE)} ${extras.getCharSequence(Notification.EXTRA_TEXT)}"
        assertThat(text).doesNotContain("!")
        assertThat(text.lowercase()).doesNotContain("don't forget")
        assertThat(text.lowercase()).doesNotContain("now")
    }

    @Test
    fun `without the permission it reports blocked and posts nothing`() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        assertThat(notifier.blockedReason()).contains("POST_NOTIFICATIONS")
        assertThat(notifier.post()).isFalse()
        assertThat(manager.activeNotifications).isEmpty()
    }

    @Test
    fun `with notifications switched off for the app it reports blocked`() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(manager).setNotificationsEnabled(false)

        assertThat(notifier.blockedReason()).contains("switched off")
        assertThat(notifier.post()).isFalse()
    }

    @Test
    fun `a post is confirmed showing, and a second one replaces rather than stacks`() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        assertThat(notifier.blockedReason()).isNull()
        assertThat(notifier.post()).isTrue()
        assertThat(notifier.post()).isTrue()

        assertThat(manager.activeNotifications.map { it.id }).containsExactly(ReflectionNotifier.NOTIFICATION_ID)
    }
}
