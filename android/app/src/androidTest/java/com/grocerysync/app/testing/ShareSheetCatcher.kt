package com.grocerysync.app.testing

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import androidx.core.content.IntentCompat
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Keeps Android's share sheet from opening and records what it would have shared: every chooser
 * (Intent.ACTION_CHOOSER) the app starts while this is open. Other activities start as usual.
 *
 * How: an instrumentation ActivityMonitor sees each activity the app's process starts before Android does, and
 * a result from onStartActivity stops that start. So the tests need no app to share to (the emulator may have
 * none), and nothing outside the app opens. Close it when done (use).
 */
class ShareSheetCatcher : AutoCloseable {

    private val choosers = CopyOnWriteArrayList<Intent>()

    private val monitor = object : Instrumentation.ActivityMonitor() {
        override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
            if (intent.action != Intent.ACTION_CHOOSER) return null
            choosers += Intent(intent)
            return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
        }
    }

    init {
        InstrumentationRegistry.getInstrumentation().addMonitor(monitor)
    }

    /** How many times the app opened the share sheet. */
    val count: Int get() = choosers.size

    /** What the share sheet opened last would have shared: the chooser's EXTRA_INTENT (ACTION_SEND). */
    fun lastShared(): Intent {
        val chooser = choosers.lastOrNull() ?: throw AssertionError("The app didn't open the share sheet")
        return IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java)
            ?: throw AssertionError("The share sheet has nothing to share (no EXTRA_INTENT): $chooser")
    }

    override fun close() {
        InstrumentationRegistry.getInstrumentation().removeMonitor(monitor)
    }
}
