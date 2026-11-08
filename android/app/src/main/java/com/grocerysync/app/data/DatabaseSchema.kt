package com.grocerysync.app.data

/**
 * The local SQLite schema of [GroceryDatabase] and its upgrades, as plain SQL. Android-free so that the
 * upgrade path is unit-tested on the JVM (see DatabaseSchemaTest).
 *
 * Versions: 1 = whole-number quantities (type "integer"); 2 = exact amounts with units (app 1.1);
 * 3 = Added At (app 1.2).
 */
internal object DatabaseSchema {
    const val VERSION = 3

    const val T_ITEMS = "items"
    const val T_OUTBOX = "outbox"
    const val T_META = "meta"

    const val C_KEY = "key"
    const val C_NAME = "name"
    const val C_TYPE = "type"
    const val C_VALUE = "value"
    const val C_UPDATED_AT = "updated_at"
    const val C_UPDATED_BY = "updated_by"
    const val C_SEQ = "seq"
    const val C_ID = "id"
    const val C_OP = "op"
    const val C_TIME = "time"
    const val C_STATUS = "status"
    const val C_ERROR = "error"
    const val C_UNIT = "unit"
    const val C_ADDED_AT = "added_at"
    const val C_K = "k"
    const val C_V = "v"

    const val M_REVISION = "revision"
    const val M_SPREADSHEET = "spreadsheet_name"
    const val M_LAST_SUCCESS = "last_success_at"
    const val M_SERVER_VERSION = "server_version"

    /** The web app URL that answered last (1.6): the mirror is its list. A meta row, so no new schema version. */
    const val M_SHEET_URL = "sheet_url"

    /**
     * A new install. Columns added by an upgrade come last, in the order the upgrades add them, so a fresh
     * database and an upgraded one have the same tables.
     */
    val CREATE = listOf(
        """
        CREATE TABLE $T_ITEMS (
            $C_KEY TEXT PRIMARY KEY NOT NULL,
            $C_NAME TEXT NOT NULL,
            $C_TYPE TEXT NOT NULL,
            $C_VALUE TEXT NOT NULL,
            $C_UPDATED_AT INTEGER NOT NULL DEFAULT 0,
            $C_UPDATED_BY TEXT NOT NULL DEFAULT '',
            $C_UNIT TEXT NOT NULL DEFAULT '',
            $C_ADDED_AT INTEGER NOT NULL DEFAULT 0
        )
        """.trimIndent(),
        """
        CREATE TABLE $T_OUTBOX (
            $C_SEQ INTEGER PRIMARY KEY AUTOINCREMENT,
            $C_ID TEXT NOT NULL UNIQUE,
            $C_OP TEXT NOT NULL,
            $C_KEY TEXT NOT NULL,
            $C_NAME TEXT NOT NULL,
            $C_TYPE TEXT,
            $C_VALUE TEXT,
            $C_TIME INTEGER NOT NULL,
            $C_STATUS TEXT NOT NULL,
            $C_ERROR TEXT,
            $C_UNIT TEXT,
            $C_ADDED_AT INTEGER NOT NULL DEFAULT 0
        )
        """.trimIndent(),
        "CREATE TABLE $T_META ($C_K TEXT PRIMARY KEY NOT NULL, $C_V TEXT)",
    )

    /**
     * Units. Items get a unit column ("" = none) and their quantities become type "quantity". Queued
     * changes keep a null unit: they were made without units, so the sheet keeps the item's unit; they
     * stay PENDING and are sent as they are. The saved revision is dropped, so the first pull after the
     * update fetches the whole list (with units and decimals) even if the sheet hasn't changed.
     */
    val MIGRATION_1_TO_2 = listOf(
        "ALTER TABLE $T_ITEMS ADD COLUMN $C_UNIT TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE $T_OUTBOX ADD COLUMN $C_UNIT TEXT",
        "UPDATE $T_ITEMS SET $C_TYPE = 'quantity' WHERE $C_TYPE = 'integer'",
        "UPDATE $T_OUTBOX SET $C_TYPE = 'quantity' WHERE $C_TYPE = 'integer'",
        "DELETE FROM $T_META WHERE $C_K = '$M_REVISION'",
    )

    /**
     * Added At. Items and queued changes get an added_at column; every row they already have keeps all its
     * data and gets 0 (unknown: the item reads "Updated by" until the sheet says when it was added). Queued
     * changes stay as they are (PENDING or FAILED) and are sent without an "addedAt" hint, as the app before
     * this version would have. The saved revision is dropped, so the first pull after the update fetches the
     * whole list with the Added At times the sheet already has (a script updated before the app).
     */
    val MIGRATION_2_TO_3 = listOf(
        "ALTER TABLE $T_ITEMS ADD COLUMN $C_ADDED_AT INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE $T_OUTBOX ADD COLUMN $C_ADDED_AT INTEGER NOT NULL DEFAULT 0",
        "DELETE FROM $T_META WHERE $C_K = '$M_REVISION'",
    )

    /** The statements that bring a database of [oldVersion] to [VERSION], in order (none when it is current). */
    fun upgrade(oldVersion: Int): List<String> = buildList {
        require(oldVersion >= 1) { "Unknown database version $oldVersion" }
        if (oldVersion < 2) addAll(MIGRATION_1_TO_2)
        if (oldVersion < 3) addAll(MIGRATION_2_TO_3)
    }
}
