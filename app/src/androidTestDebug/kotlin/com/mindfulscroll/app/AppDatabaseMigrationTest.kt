package com.mindfulscroll.app

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mindfulscroll.app.data.ALL_MIGRATIONS
import com.mindfulscroll.app.data.AppDatabase
import com.mindfulscroll.app.data.MIGRATION_1_2
import com.mindfulscroll.app.data.entity.IntentionKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every v0.2.x install in the wild is on schema 1, holding the only copy of that person's history:
 * there is no backup (allowBackup=false) and no server. A migration that is subtly wrong does not
 * fail quietly - Room verifies the migrated schema against its own expectation on open and throws
 * "Migration didn't properly handle" - so the app would simply refuse to start after an update.
 *
 * MigrationTestHelper opens a REAL database at version 1 (built from Room's own exported
 * 1.json, not from a schema written by hand here), runs the actual migration object the app
 * ships, and lets Room validate the result. That last part is the point: it is the same check
 * that runs on a user's phone.
 */
@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {

    private val testDbName = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate1To2_addsIntentionsAndKeepsExistingData() {
        // Seed a v1 database with a row in each table that already existed, so the test can tell
        // "added a table" apart from "rebuilt the database and lost everything".
        helper.createDatabase(testDbName, 1).use { db ->
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

        helper.runMigrationsAndValidate(testDbName, 2, true, MIGRATION_1_2)

        // Reopening through Room is the check that matters: it runs the same schema validation a
        // real device runs on update, and it is what would throw if the migration's DDL differed
        // from what Room expects by so much as a nullability.
        val db = Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
            testDbName,
        ).addMigrations(*ALL_MIGRATIONS).build()

        runBlocking {
            val app = db.monitoredAppDao().get("com.instagram.android")
            assertNotNull("the v1 monitored_apps row did not survive the migration", app)
            assertEquals(40, app!!.scrollThreshold)

            val stat = db.dailyAppStatDao().get("com.instagram.android", 20000)
            assertNotNull("the v1 daily_app_stats row did not survive the migration", stat)
            assertEquals(137, stat!!.scrollCount)

            // The new table has to be usable, not merely present.
            val id = db.intentionDao().insert(
                com.mindfulscroll.app.data.entity.IntentionEntity(
                    packageName = "com.instagram.android",
                    dateEpochDay = 20000,
                    sessionStartMillis = 5000,
                    promptedAtMillis = 5000,
                    respondedAtMillis = null,
                    kind = null,
                    note = null,
                ),
            )
            val unanswered = db.intentionDao().get(id)
            assertNotNull("intentions row could not be read back after migration", unanswered)
            assertNull("an unanswered prompt must store a null kind", unanswered!!.kind)

            db.intentionDao().update(unanswered.copy(kind = IntentionKind.HABIT, respondedAtMillis = 6000))
            assertEquals(IntentionKind.HABIT, db.intentionDao().get(id)!!.kind)
        }
        db.close()
    }
}
