package com.grocerysync.app.notify

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONException
import org.json.JSONObject

/**
 * What [ChangeWatcher] remembers between checks:
 * - [sheet]: the web app URL the list came from. A list kept from another sheet (before the phone switched to this
 *   one) is never compared with this one's: everything on it would look new.
 * - [seen]: To buy as the person last saw it ([ListChangeWatch.seen]). An empty one is a list seen too (everything
 *   bought): what is added to it later is news.
 * - [notice]: the change notification posted since and not seen yet, or null.
 */
data class SeenState(val sheet: String, val seen: Map<String, String>, val notice: PostedNotice? = null)

/**
 * A change notification posted and not seen yet: To buy when it was posted ([list], in [ListChangeWatch.seen]'s form)
 * and the changes it tells ([told], [ListChangeWatch.told]). Taken away without the app being opened (swiped), what
 * it told counts as seen: [list] becomes the list seen.
 */
data class PostedNotice(val list: Map<String, String>, val told: Map<String, String>)

/** Where [ChangeWatcher] keeps its [SeenState]: an interface, so its tests can keep it in memory. */
interface SeenList {
    /** What was stored; null when nothing was (a new install, the first check after updating to 1.6, or forgotten). */
    fun read(): SeenState?

    /** Stores [state]; null forgets what was stored, so the next check only looks. */
    fun write(state: SeenState?)
}

/**
 * [SeenList] in a SharedPreferences file of its own (this phone only, never synced), as one JSON object. Written
 * with commit, not apply: the background check's process may end right after it.
 */
class SeenListStore internal constructor(private val prefs: SharedPreferences) : SeenList {

    constructor(context: Context) :
        this(context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))

    /** A stored text that can't be read (written by a newer or an earlier build, say) counts as never seen. */
    override fun read(): SeenState? {
        val stored = prefs.getString(KEY_SEEN, null) ?: return null
        return try {
            val json = JSONObject(stored)
            val notice = json.optJSONObject(NOTICE)?.let {
                PostedNotice(list = it.getJSONObject(LIST).toMap(), told = it.getJSONObject(TOLD).toMap())
            }
            SeenState(sheet = json.getString(SHEET), seen = json.getJSONObject(SEEN).toMap(), notice = notice)
        } catch (e: JSONException) {
            null
        }
    }

    override fun write(state: SeenState?) {
        if (state == null) {
            prefs.edit(commit = true) { remove(KEY_SEEN) }
            return
        }
        val json = JSONObject()
            .put(SHEET, state.sheet)
            .put(SEEN, JSONObject(state.seen))
        state.notice?.let { notice ->
            json.put(NOTICE, JSONObject().put(LIST, JSONObject(notice.list)).put(TOLD, JSONObject(notice.told)))
        }
        prefs.edit(commit = true) { putString(KEY_SEEN, json.toString()) }
    }

    private fun JSONObject.toMap(): Map<String, String> = keys().asSequence().associateWith { getString(it) }

    private companion object {
        const val PREFS_NAME = "list_change_seen"
        const val KEY_SEEN = "seen"
        const val SHEET = "sheet"
        const val SEEN = "seen"
        const val NOTICE = "notice"
        const val LIST = "list"
        const val TOLD = "told"
    }
}
