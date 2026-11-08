package com.grocerysync.app.notify

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONException
import org.json.JSONObject

/**
 * Where [ChangeWatcher] keeps To buy as the person last saw it ([ListChangeWatch.seen]): an interface, so its tests
 * can keep it in memory.
 */
interface SeenList {
    /** The list as last seen; null when it never was (a new install, or the first check after updating to 1.6). */
    fun read(): Map<String, String>?

    fun write(seen: Map<String, String>)
}

/**
 * [SeenList] in a SharedPreferences file of its own (this phone only, never synced), as one JSON object of key to
 * value. Written with commit, not apply: the background check's process may end right after it.
 */
class SeenListStore internal constructor(private val prefs: SharedPreferences) : SeenList {

    constructor(context: Context) :
        this(context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))

    /** A stored text that can't be read (written by a newer version, say) counts as never seen. */
    override fun read(): Map<String, String>? {
        val stored = prefs.getString(KEY_SEEN, null) ?: return null
        return try {
            val json = JSONObject(stored)
            json.keys().asSequence().associateWith { json.getString(it) }
        } catch (e: JSONException) {
            null
        }
    }

    override fun write(seen: Map<String, String>) {
        prefs.edit(commit = true) { putString(KEY_SEEN, JSONObject(seen).toString()) }
    }

    private companion object {
        const val PREFS_NAME = "list_change_seen"
        const val KEY_SEEN = "seen"
    }
}
