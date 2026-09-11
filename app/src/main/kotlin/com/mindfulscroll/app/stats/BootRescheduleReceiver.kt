package com.mindfulscroll.app.stats

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mindfulscroll.app.data.AppSettings
import com.mindfulscroll.app.grayscale.GrayscaleEntryPoint
import dagger.hilt.android.EntryPointAccessors

/** Re-enqueues the daily maintenance work, and the weekly reflection job if its prompt is on,
 *  after a reboot. WorkManager also persists periodic work across reboots on its own, so this is
 *  a defensive no-op in the common case (enqueueUniquePeriodicWork + KEEP makes it idempotent
 *  either way).
 *
 *  Also puts back grayscale (#27) if the phone went down while it was on. Colour correction is a
 *  persistent system setting, so a phone that died or was restarted with a monitored app in front
 *  would otherwise boot gray and stay that way until the accessibility service reconnects - or
 *  indefinitely, if the service has been turned off in the meantime. */
class BootRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            rescheduleWork(context)
            EntryPointAccessors.fromApplication(context.applicationContext, GrayscaleEntryPoint::class.java)
                .grayscaleController()
                .restoreIfApplied("device booted")
        }
    }

    companion object {
        /** The WorkManager half of a boot, apart from the grayscale restore so it can be tested
         *  without the Hilt graph. Not Hilt-injected: AppSettings only reads SharedPreferences
         *  here. */
        internal fun rescheduleWork(context: Context) {
            WorkScheduler.scheduleDailyMaintenance(context)
            WorkScheduler.syncWeeklyReflection(
                context,
                AppSettings(context.applicationContext).weeklyReflectionPromptEnabledNow(),
            )
        }
    }
}
