package com.mindfulscroll.app.stats

import android.app.AppOpsManager
import android.content.Context
import android.os.Process

/**
 * Whether the user has granted "Usage access" (PACKAGE_USAGE_STATS is a special access
 * permission, not a runtime-requestable one - see AndroidManifest.xml for why it's still
 * declared). AppOpsManager is the only reliable way to check the current state.
 */
object UsageAccessChecker {

    @Suppress("DEPRECATION") // checkOpNoThrow(String,...) has been public since API 19; the
    // unsafeCheckOpNoThrow replacement only exists from API 29, above our minSdk of 26.
    fun isUsageAccessGranted(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }
}
