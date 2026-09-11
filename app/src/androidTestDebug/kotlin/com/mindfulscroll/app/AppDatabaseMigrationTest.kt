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
import com.mindfulscroll.app.data.MIGRATION_3_4
import com.mindfulscroll.app.data.MIGRATION_4_5
import com.mindfulscroll.app.data.MIGRATION_5_6
import com.mindfulscroll.app.data.entity.IntentionEntity
import com.mindfulscroll.app.data.entity.IntentionKind
import com.mindfulscroll.app.data.entity.MonitoredAppEntity
import com.mindfulscroll.app.data.entity.OverlayChoice
import com.mindfulscroll.app.data.entity.PauseOutcome
import kotlinx.coroutines.flow.first
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

        // Reopening through Room runs every remaining migration up to the current version and
        // applies the same schema validation a real device does on update - which is what would
        // throw if the recreated table's DDL differed from Room's expectation by so much as a
        // nullability.
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

    /**
     * The mindful pause (#5) records intention, outcome and choice as one row, so overlay_events
     * gains three nullable columns.
     *
     * The opposite shape to 2 -> 3, and the test says so: this one must lose nothing at all. A
     * pause the user answered before the update is history, not configuration, and there is no
     * other copy of it.
     */
    @Test
    fun migrate3To4_addsIntentionColumnsAndKeepsEveryExistingRow() {
        val dbName = "migration-3-4-test.db"

        helper.createDatabase(dbName, 3).use { db ->
            db.execSQL(
                "INSERT INTO overlay_events " +
                    "(packageName, dateEpochDay, shownAtMillis, scrollCountAtTrigger, sessionTimeMillisAtTrigger, choice, respondedAtMillis) " +
                    "VALUES ('com.instagram.android', 20000, 5000, 40, 600000, 'CONTINUE', 6000)",
            )
            db.execSQL(
                "INSERT INTO daily_app_stats (packageName, dateEpochDay, scrollCount, foregroundTimeMillis, updatedAtMillis) " +
                    "VALUES ('com.instagram.android', 20000, 137, 600000, 1000)",
            )
        }

        helper.runMigrationsAndValidate(dbName, 4, true, MIGRATION_3_4).use { db ->
            assertEquals(
                "the v3 overlay_events row did not survive - this migration adds nullable columns " +
                    "and must not touch a single existing row",
                1,
                db.countRows("overlay_events"),
            )
            assertEquals(1, db.countRows("daily_app_stats"))
        }

        val db = Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
            dbName,
        ).addMigrations(*ALL_MIGRATIONS).build()

        runBlocking {
            val events = db.overlayEventDao().observeForDayRange(20000, 20000).first()
            assertEquals(1, events.size)
            val migrated = events.first()
            // The pre-existing values are the point: an ALTER TABLE that quietly rewrote the row
            // would still leave exactly one row behind.
            assertEquals(OverlayChoice.CONTINUE, migrated.choice)
            assertEquals(40, migrated.scrollCountAtTrigger)
            // Null is correct rather than merely tolerated: a pause shown before this release
            // genuinely had no intention recorded against it, and #6 must not read that as
            // "asked and ignored".
            assertNull("a pre-existing pause cannot have an intention", migrated.intentionId)
            assertNull(migrated.intentionKind)
            assertNull(migrated.outcome)

            // And the new columns are writable, not merely present.
            db.overlayEventDao().update(migrated.copy(outcome = PauseOutcome.NOT_REALLY))
            val reread = db.overlayEventDao().get(migrated.id)!!
            assertEquals(PauseOutcome.NOT_REALLY, reread.outcome)
        }
        db.close()
    }

    /**
     * Times-opened (#28): daily_app_stats gains a nullable open count.
     *
     * Two things are pinned here. No existing day loses anything. And those days come through as
     * NULL, "not counted", rather than 0: a v4 day with an hour of foreground time was certainly
     * opened, and a 0 would say it wasn't.
     */
    @Test
    fun migrate4To5_addsOpenCountAsNotCountedForEveryExistingDay() {
        val dbName = "migration-4-5-test.db"

        helper.createDatabase(dbName, 4).use { db ->
            db.execSQL(
                "INSERT INTO daily_app_stats (packageName, dateEpochDay, scrollCount, foregroundTimeMillis, updatedAtMillis) " +
                    "VALUES ('com.instagram.android', 20000, 137, 3600000, 1000)",
            )
        }

        helper.runMigrationsAndValidate(dbName, 5, true, MIGRATION_4_5).use { db ->
            assertEquals(
                "the v4 daily_app_stats row did not survive a migration that only adds a column",
                1,
                db.countRows("daily_app_stats"),
            )
        }

        val db = Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
            dbName,
        ).addMigrations(*ALL_MIGRATIONS).build()

        runBlocking {
            val migrated = db.dailyAppStatDao().get("com.instagram.android", 20000)
            assertNotNull("the v4 day could not be read back through Room at v5", migrated)
            assertEquals(137, migrated!!.scrollCount)
            assertEquals(3_600_000L, migrated.foregroundTimeMillis)
            assertNull(
                "a day recorded before opens were counted must read as not counted (null), not " +
                    "as zero opens",
                migrated.openCount,
            )

            // Writable, and a day created after the migration counts from zero.
            db.dailyAppStatDao().upsert(migrated.copy(dateEpochDay = 20001, openCount = 3))
            assertEquals(3, db.dailyAppStatDao().get("com.instagram.android", 20001)!!.openCount)
        }
        db.close()
    }

    /**
     * Grayscale (#27): monitored_apps gains a per-app opt-in, NOT NULL DEFAULT 0.
     *
     * Every existing app must keep its thresholds and monitored switch - monitored_apps was
     * dropped outright once before (MIGRATION_2_3), so "config survives" is worth pinning - and
     * must come through with grayscale off, since nobody opted into a feature that did not exist.
     */
    @Test
    fun migrate5To6_addsGrayscaleOffForEveryExistingApp() {
        val dbName = "migration-5-6-test.db"

        helper.createDatabase(dbName, 5).use { db ->
            db.execSQL(
                "INSERT INTO monitored_apps " +
                    "(packageName, appLabel, isMonitored, scrollThreshold, timeThresholdMinutes, addedAtMillis) " +
                    "VALUES ('com.instagram.android', 'Instagram', 0, 55, 7, 1234)",
            )
        }

        helper.runMigrationsAndValidate(dbName, 6, true, MIGRATION_5_6).use { db ->
            assertEquals(
                "the v5 monitored_apps row did not survive a migration that only adds a column",
                1,
                db.countRows("monitored_apps"),
            )
        }

        val db = Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
            dbName,
        ).addMigrations(*ALL_MIGRATIONS).build()

        runBlocking {
            val migrated = db.monitoredAppDao().get("com.instagram.android")
            assertNotNull("the v5 app could not be read back through Room at v6", migrated)
            assertEquals("Instagram", migrated!!.appLabel)
            assertEquals(false, migrated.isMonitored)
            assertEquals(55, migrated.scrollThreshold)
            assertEquals(7, migrated.timeThresholdMinutes)
            assertEquals(1234L, migrated.addedAtMillis)
            assertEquals("an app from before grayscale existed must come through with it off", false, migrated.grayscaleEnabled)

            // Writable, not merely present.
            db.monitoredAppDao().update(migrated.copy(grayscaleEnabled = true))
            assertEquals(true, db.monitoredAppDao().get("com.instagram.android")!!.grayscaleEnabled)
        }
        db.close()
    }

    private fun SupportSQLiteDatabase.countRows(table: String): Int =
        query("SELECT COUNT(*) FROM `$table`").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
}
