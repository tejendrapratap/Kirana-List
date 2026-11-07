package com.grocerysync.app.data

import android.content.Context
import androidx.core.content.edit
import kotlin.math.abs

/**
 * Estimates the server's clock so edit times are comparable with times written by other phones
 * and by the sheet itself. A phone clock that runs behind would otherwise make fresh edits look
 * older than the row they change, and the sheet would reject them as stale.
 *
 * The measured offset is kept in [offsetStore], so edits made offline right after the app was
 * restarted still use it.
 */
class ServerClock(
    private val offsetStore: OffsetStore? = null,
    private val localClock: () -> Long = System::currentTimeMillis,
) {

    /** Persists the measured offset between app runs. Calls may block (they run off the main thread). */
    interface OffsetStore {
        fun load(): Long?
        fun save(offsetMs: Long)
    }

    private var offsetMs: Long = 0L
    private var savedOffsetMs: Long? = null
    private var loaded = offsetStore == null
    private var lastIssued: Long = Long.MIN_VALUE

    /** Estimated server time; never goes backwards, so later edits always get later times. */
    @Synchronized
    fun now(): Long {
        ensureLoaded()
        val estimate = maxOf(localClock() + offsetMs, lastIssued)
        lastIssued = estimate
        return estimate
    }

    /**
     * An edit time no earlier than [time] (e.g. the time of the version the user was looking at).
     * Later calls return at least this value too, so an edit that follows (an Undo after a delete,
     * say) is never stamped before one already issued.
     */
    @Synchronized
    fun atLeast(time: Long): Long {
        val issued = maxOf(now(), time)
        lastIssued = issued
        return issued
    }

    fun localNow(): Long = localClock()

    /**
     * Records the `serverTime` of a response for a request sent at [sentAt] and answered at
     * [receivedAt] (both local clock); the server is assumed to have read its clock halfway through.
     */
    fun onServerTime(serverTime: Long, sentAt: Long, receivedAt: Long) {
        if (serverTime <= 0L || receivedAt < sentAt) return
        val measured = serverTime - (sentAt + (receivedAt - sentAt) / 2)
        val toSave = synchronized(this) {
            ensureLoaded()
            offsetMs = measured
            // Network jitter moves the estimate by a few hundred ms; only a real change is worth a write.
            val saved = savedOffsetMs
            if (saved == null || abs(saved - measured) >= SAVE_THRESHOLD_MS) {
                savedOffsetMs = measured
                measured
            } else {
                null
            }
        }
        if (toSave != null) offsetStore?.save(toSave)
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        offsetStore?.load()?.let {
            offsetMs = it
            savedOffsetMs = it
        }
    }

    private companion object {
        const val SAVE_THRESHOLD_MS = 1_000L
    }
}

/** [ServerClock.OffsetStore] in private SharedPreferences (read lazily, off the main thread). */
class PrefsClockOffsetStore(context: Context) : ServerClock.OffsetStore {
    private val appContext = context.applicationContext
    private val prefs by lazy { appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    override fun load(): Long? = if (prefs.contains(KEY_OFFSET)) prefs.getLong(KEY_OFFSET, 0L) else null

    override fun save(offsetMs: Long) {
        prefs.edit { putLong(KEY_OFFSET, offsetMs) }
    }

    private companion object {
        const val PREFS_NAME = "grocery_sync_clock"
        const val KEY_OFFSET = "server_offset_ms"
    }
}
