package com.mindfulscroll.app.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The first real migration this app has had. Every v0.2.x install in the wild is on schema 1, and
 * Room verifies the resulting schema against its own expectation on open - so a statement that
 * differs from what Room would have generated, even by a nullability or an index name, fails at
 * startup with "Migration didn't properly handle" rather than quietly working.
 *
 * Both statements below are therefore copied verbatim out of Room's own exported schema
 * (app/schemas/com.mindfulscroll.app.data.AppDatabase/2.json, `createSql`), with only the
 * ${'$'}{TABLE_NAME} placeholder substituted. They are deliberately NOT typed out by hand.
 *
 * Purely additive: intention capture adds a table and touches nothing that already exists, so
 * there is no data to move and nothing for an existing install to lose.
 */
val MIGRATION_1_2 = Migration(1, 2) { db: SupportSQLiteDatabase ->
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS `intentions` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`packageName` TEXT NOT NULL, " +
            "`dateEpochDay` INTEGER NOT NULL, " +
            "`sessionStartMillis` INTEGER NOT NULL, " +
            "`promptedAtMillis` INTEGER NOT NULL, " +
            "`respondedAtMillis` INTEGER, " +
            "`kind` TEXT, " +
            "`note` TEXT)",
    )
    db.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_intentions_packageName_sessionStartMillis` " +
            "ON `intentions` (`packageName`, `sessionStartMillis`)",
    )
}

/**
 * Retires FrictionMode (see issue #3). The countdown / typed-phrase gate is gone, so the column
 * that chose between them means nothing.
 *
 * **This one deliberately discards data**, which is why it is worth reading carefully before
 * copying its shape anywhere else.
 *
 * `monitored_apps` is configuration, not history: which apps are watched and their two
 * thresholds. It is re-created by picking apps in Settings, which takes under a minute. The
 * tables that hold the data this app exists to accumulate - `daily_app_stats`, `overlay_events`,
 * `intentions` - are untouched here, and must stay that way. Losing a scroll history has no
 * remedy; there is no backup and no server.
 *
 * That distinction is the entire reason this is a drop-and-recreate rather than the usual
 * create-new / copy / drop / rename. SQLite gained `DROP COLUMN` only in 3.35 (API 31) and this
 * app supports API 26, so preserving the rows would mean the table-rebuild dance - the riskiest
 * shape of migration there is. Choosing not to preserve four columns of re-pickable config
 * removes that risk entirely rather than managing it.
 *
 * The CREATE statement is copied verbatim from Room's own exported schema
 * (app/schemas/com.mindfulscroll.app.data.AppDatabase/3.json, `createSql`) with only the
 * ${'$'}{TABLE_NAME} placeholder substituted, exactly as MIGRATION_1_2 is. Room verifies the
 * resulting schema on open, so a statement differing even by a nullability fails at startup.
 */
val MIGRATION_2_3 = Migration(2, 3) { db: SupportSQLiteDatabase ->
    db.execSQL("DROP TABLE IF EXISTS `monitored_apps`")
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS `monitored_apps` (" +
            "`packageName` TEXT NOT NULL, " +
            "`appLabel` TEXT NOT NULL, " +
            "`isMonitored` INTEGER NOT NULL, " +
            "`scrollThreshold` INTEGER NOT NULL, " +
            "`timeThresholdMinutes` INTEGER NOT NULL, " +
            "`addedAtMillis` INTEGER NOT NULL, " +
            "PRIMARY KEY(`packageName`))",
    )
}

/**
 * The mindful pause (#5) records intention, outcome and choice as one row, so overlay_events
 * gains the intention half.
 *
 * Purely additive, and pointedly so after MIGRATION_2_3: all three columns are nullable, which
 * means plain ALTER TABLE ADD COLUMN with no table rebuild and nothing to copy. Existing rows keep
 * every value they had and get nulls for the new ones - which is also semantically right, since
 * pauses shown before this release genuinely had no intention recorded against them.
 *
 * Column types are taken from Room's exported schema
 * (app/schemas/com.mindfulscroll.app.data.AppDatabase/4.json): INTEGER for the id, TEXT for both
 * enums, all nullable. Room verifies the result on open, so a mismatched affinity fails at startup.
 */
val MIGRATION_3_4 = Migration(3, 4) { db: SupportSQLiteDatabase ->
    db.execSQL("ALTER TABLE `overlay_events` ADD COLUMN `intentionId` INTEGER")
    db.execSQL("ALTER TABLE `overlay_events` ADD COLUMN `intentionKind` TEXT")
    db.execSQL("ALTER TABLE `overlay_events` ADD COLUMN `outcome` TEXT")
}

/** Every migration, in one place, so DatabaseModule cannot forget to register one. */
val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
