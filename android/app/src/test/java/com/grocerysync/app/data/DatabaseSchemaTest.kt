package com.grocerysync.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The upgrade of the local database, as far as the JVM allows (SQLite itself only runs on Android): a database
 * that an earlier app created, upgraded with [DatabaseSchema.upgrade], has exactly the tables and columns of a
 * fresh install, and the upgrade only adds columns (with defaults) or rewrites values, so it keeps the items and
 * the queued changes.
 */
class DatabaseSchemaTest {

    /** The tables the 1.1 app created (database version 2), copied from its GroceryDatabase. Never change this. */
    private val createdByApp11 = listOf(
        """
        CREATE TABLE items (
            key TEXT PRIMARY KEY NOT NULL,
            name TEXT NOT NULL,
            type TEXT NOT NULL,
            value TEXT NOT NULL,
            updated_at INTEGER NOT NULL DEFAULT 0,
            updated_by TEXT NOT NULL DEFAULT '',
            unit TEXT NOT NULL DEFAULT ''
        )
        """.trimIndent(),
        """
        CREATE TABLE outbox (
            seq INTEGER PRIMARY KEY AUTOINCREMENT,
            id TEXT NOT NULL UNIQUE,
            op TEXT NOT NULL,
            key TEXT NOT NULL,
            name TEXT NOT NULL,
            type TEXT,
            value TEXT,
            time INTEGER NOT NULL,
            status TEXT NOT NULL,
            error TEXT,
            unit TEXT
        )
        """.trimIndent(),
        "CREATE TABLE meta (k TEXT PRIMARY KEY NOT NULL, v TEXT)",
    )

    /** Version 1 (the app before units): the same without the unit columns that MIGRATION_1_TO_2 adds. */
    private val createdByApp10 = createdByApp11
        .map { it.replace(",\n    unit TEXT NOT NULL DEFAULT ''", "").replace(",\n    unit TEXT", "") }

    // ---- A tiny model of what CREATE TABLE / ALTER TABLE ... ADD COLUMN leave behind ----

    /** Table -> its column definitions in order (whitespace collapsed). */
    private fun schemaAfter(statements: List<String>, start: Map<String, List<String>> = emptyMap()): Map<String, List<String>> {
        val tables = start.mapValues { it.value.toMutableList() }.toMutableMap()
        for (sql in statements.map { it.replace(Regex("\\s+"), " ").trim() }) {
            CREATE.matchEntire(sql)?.let { match ->
                val name = match.groupValues[1]
                assertFalse("$name created twice", name in tables)
                tables[name] = match.groupValues[2].split(",").map { it.trim() }.toMutableList()
            } ?: ADD_COLUMN.matchEntire(sql)?.let { match ->
                val columns = tables[match.groupValues[1]] ?: error("no table ${match.groupValues[1]}")
                val name = columnName(match.groupValues[2])
                assertFalse("$name added twice", columns.any { columnName(it) == name })
                columns += match.groupValues[2].trim()
            }
        }
        return tables
    }

    private fun columnName(definition: String) = definition.trim().substringBefore(' ')

    @Test
    fun aFreshInstallIsVersion3WithAddedAtColumns() {
        assertEquals(3, DatabaseSchema.VERSION)
        val schema = schemaAfter(DatabaseSchema.CREATE)
        assertEquals(setOf("items", "outbox", "meta"), schema.keys)
        assertTrue("added_at INTEGER NOT NULL DEFAULT 0" in schema.getValue("items"))
        assertTrue("added_at INTEGER NOT NULL DEFAULT 0" in schema.getValue("outbox"))
    }

    @Test
    fun upgradingFrom11GivesTheFreshSchema() {
        val upgraded = schemaAfter(createdByApp11 + DatabaseSchema.upgrade(2))
        assertEquals(schemaAfter(DatabaseSchema.CREATE), upgraded)
    }

    @Test
    fun upgradingFromTheAppBeforeUnitsGivesTheFreshSchema() {
        assertEquals(schemaAfter(createdByApp11), schemaAfter(createdByApp10 + DatabaseSchema.MIGRATION_1_TO_2))
        assertEquals(schemaAfter(DatabaseSchema.CREATE), schemaAfter(createdByApp10 + DatabaseSchema.upgrade(1)))
    }

    @Test
    fun upgradeStepsRunInOrderAndOnlyWhenNeeded() {
        assertEquals(DatabaseSchema.MIGRATION_1_TO_2 + DatabaseSchema.MIGRATION_2_TO_3, DatabaseSchema.upgrade(1))
        assertEquals(DatabaseSchema.MIGRATION_2_TO_3, DatabaseSchema.upgrade(2))
        assertEquals(emptyList<String>(), DatabaseSchema.upgrade(3))
    }

    @Test
    fun upgradesKeepItemsAndQueuedChanges() {
        for (sql in DatabaseSchema.upgrade(1)) {
            val text = sql.uppercase()
            assertFalse(sql, "DROP " in text)
            assertFalse(sql, "RENAME " in text)
            assertFalse(sql, Regex("DELETE FROM (ITEMS|OUTBOX)\\b").containsMatchIn(text))
            assertFalse(sql, Regex("UPDATE OUTBOX SET (STATUS|SEQ|ID)\\b").containsMatchIn(text))
            // SQLite can only add a NOT NULL column with a default, which every existing row gets.
            if ("ADD COLUMN" in text && "NOT NULL" in text) assertTrue(sql, "DEFAULT" in text)
        }
    }

    @Test
    fun theUpgradeTo3FetchesTheWholeListOnce() {
        // So items get the Added At times a script that is already updated reports.
        assertTrue("DELETE FROM meta WHERE k = 'revision'" in DatabaseSchema.MIGRATION_2_TO_3)
    }

    private companion object {
        val CREATE = Regex("CREATE TABLE (\\w+) \\((.*)\\)")
        val ADD_COLUMN = Regex("ALTER TABLE (\\w+) ADD COLUMN (.*)")
    }
}
