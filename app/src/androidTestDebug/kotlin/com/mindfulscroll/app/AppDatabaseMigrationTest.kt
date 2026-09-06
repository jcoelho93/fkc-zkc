package com.mindfulscroll.app

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mindfulscroll.app.data.ALL_MIGRATIONS
import com.mindfulscroll.app.data.AppDatabase
import com.mindfulscroll.app.data.MIGRATION_1_2
import com.mindfulscroll.app.data.MIGRATION_2_3
import com.mindfulscroll.app.data.entity.IntentionEntity
import com.mindfulscroll.app.data.entity.IntentionKind
import com.mindfulscroll.app.data.entity.MonitoredAppEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every install holds the only copy of that person's history: there is no backup
 * (allowBackup=false) and no server. A migration that is subtly wrong does not fail quietly -
 * Room verifies the migrated schema against its own expectation on open and throws "Migration
 * didn't properly handle" - so the app would simply refuse to start after an update.
 *
 * MigrationTestHelper opens a REAL database at the starting version (built from Room's own
 * exported JSON in app/schemas, not from a schema written by hand here), runs the actual
 * migration object the app ships, and lets Room validate the result. That last part is the
 * point: it is the same check that runs on a user's phone.
 */
@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate1To2_addsIntentionsAndKeepsExistingData() {
        val dbName = "migration-1-2-test.db"

        // Seed a v1 database with a row in each table that already existed, so the test can tell
        // "added a table" apart from "rebuilt the database and lost everything".
        helper.createDatabase(dbName, 1).use { db ->
            db.execSQL(
                "INSERT INTO monitored_apps " +
                    "(packageName, appLabel, isMonitored, scrollThreshold, timeThresholdMinutes, frictionMode, addedAtMillis) " +
                    "VALUES ('com.instagram.android', 'Instagram', 1, 40, 10, 'COUNTDOWN', 1000)",
            )
            db.execSQL(
                "INSERT INTO daily_app_stats (packageName, dateEpochDay, scrollCount, foregroundTimeMillis, updatedAtMillis) " +
                    "VALUES ('com.instagram.android', 20000, 137, 600000, 1000)",
            )
        }

        // Stops at 2 rather than reopening through Room at the current version, because 2 -> 3
        // deliberately drops monitored_apps - see migrate2To3 below. Asserting the v1 row survived
        // is only meaningful at the version this migration produces. runMigrationsAndValidate
        // performs the same schema validation a real device does, so nothing is lost by not
        // reopening here.
        helper.runMigrationsAndValidate(dbName, 2, true, MIGRATION_1_2).use { db ->
            assertEquals(
                "the v1 monitored_apps row did not survive the 1 -> 2 migration",
                1,
                db.countRows("monitored_apps"),
            )
            assertEquals(
                "the v1 daily_app_stats row did not survive the 1 -> 2 migration",
                1,
                db.countRows("daily_app_stats"),
            )
            assertEquals("the intentions table was not created", 0, db.countRows("intentions"))
        }
    }

    /**
     * The migration that retires FrictionMode (#3), and the only one in this app that throws data
     * away on purpose. This test exists to pin down exactly *which* data, because "we accepted
     * some loss here" is precisely the kind of decision that quietly widens later.
     *
     * `monitored_apps` is configuration - which apps are watched and their two thresholds -
     * re-created by picking apps in Settings in under a minute. Everything in
     * `daily_app_stats`, `overlay_events` and `intentions` is the history this app exists to
     * accumulate, has no other copy anywhere, and must come through untouched.
     */
    @Test
    fun migrate2To3_dropsMonitoredAppsAndKeepsEveryHistoryTable() {
        val dbName = "migration-2-3-test.db"

        helper.createDatabase(dbName, 2).use { db ->
            db.execSQL(
                "INSERT INTO monitored_apps " +
                    "(packageName, appLabel, isMonitored, scrollThreshold, timeThresholdMinutes, frictionMode, addedAtMillis) " +
                    "VALUES ('com.instagram.android', 'Instagram', 1, 40, 10, 'TYPED_PHRASE', 1000)",
            )
            db.execSQL(
                "INSERT INTO daily_app_stats (packageName, dateEpochDay, scrollCount, foregroundTimeMillis, updatedAtMillis) " +
                    "VALUES ('com.instagram.android', 20000, 137, 600000, 1000)",
            )
            db.execSQL(
                "INSERT INTO overlay_events " +
                    "(packageName, dateEpochDay, shownAtMillis, scrollCountAtTrigger, sessionTimeMillisAtTrigger, choice, respondedAtMillis) " +
                    "VALUES ('com.instagram.android', 20000, 5000, 40, 600000, 'CONTINUE', 6000)",
            )
            db.execSQL(
                "INSERT INTO intentions " +
                    "(packageName, dateEpochDay, sessionStartMillis, promptedAtMillis, respondedAtMillis, kind, note) " +
                    "VALUES ('com.instagram.android', 20000, 5000, 5000, 5500, 'HABIT', NULL)",
            )
        }

        helper.runMigrationsAndValidate(dbName, 3, true, MIGRATION_2_3).use { db ->
            assertEquals(
                "monitored_apps should have been dropped and recreated empty - this migration " +
                    "deliberately discards it, and a surviving row means the DROP silently did " +
                    "nothing and the dead frictionMode column is still there",
                0,
                db.countRows("monitored_apps"),
            )
            // The three that must never be casualties. Named individually rather than looped, so
            // a failure says which history was lost.
            assertEquals(
                "daily_app_stats was destroyed by a migration that had no business touching it",
                1,
                db.countRows("daily_app_stats"),
            )
            assertEquals(
                "overlay_events was destroyed by a migration that had no business touching it",
                1,
                db.countRows("overlay_events"),
            )
            assertEquals(
                "intentions was destroyed by a migration that had no business touching it",
                1,
                db.countRows("intentions"),
            )
        }

        // Reopening through Room is the check that matters most: it runs the same schema
        // validation a real device runs on update, and it is what would throw if the recreated
        // table's DDL differed from what Room expects by so much as a nullability.
        val db = Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
            dbName,
        ).addMigrations(*ALL_MIGRATIONS).build()

        runBlocking {
            // Recreated, not merely absent: a table that exists but cannot be written to would
            // leave the user unable to re-pick the apps this migration just made them re-pick.
            db.monitoredAppDao().upsertAll(
                listOf(
                    MonitoredAppEntity(
                        packageName = "com.instagram.android",
                        appLabel = "Instagram",
                        isMonitored = true,
                        scrollThreshold = 40,
                        timeThresholdMinutes = 10,
                        addedAtMillis = 2000,
                    ),
                ),
            )
            val reAdded = db.monitoredAppDao().get("com.instagram.android")
            assertNotNull("monitored_apps could not be written after being recreated", reAdded)
            assertEquals(40, reAdded!!.scrollThreshold)

            val stat = db.dailyAppStatDao().get("com.instagram.android", 20000)
            assertNotNull("the v2 daily_app_stats row did not survive", stat)
            assertEquals(137, stat!!.scrollCount)

            // Intentions still round-trip, including the null kind that records "prompt shown and
            // ignored" - the row the weekly report (#6) must be able to count.
            val id = db.intentionDao().insert(
                IntentionEntity(
                    packageName = "com.instagram.android",
                    dateEpochDay = 20000,
                    sessionStartMillis = 7000,
                    promptedAtMillis = 7000,
                    respondedAtMillis = null,
                    kind = null,
                    note = null,
                ),
            )
            val unanswered = db.intentionDao().get(id)
            assertNotNull("intentions row could not be read back after migration", unanswered)
            assertNull("an unanswered prompt must store a null kind", unanswered!!.kind)

            db.intentionDao().update(unanswered.copy(kind = IntentionKind.HABIT, respondedAtMillis = 8000))
            assertEquals(IntentionKind.HABIT, db.intentionDao().get(id)!!.kind)
        }
        db.close()
    }

    private fun SupportSQLiteDatabase.countRows(table: String): Int =
        query("SELECT COUNT(*) FROM `$table`").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
}
