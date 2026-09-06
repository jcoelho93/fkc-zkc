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

/** Every migration, in one place, so DatabaseModule cannot forget to register one. */
val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2)
