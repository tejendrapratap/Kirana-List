package com.grocerysync.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerClockTest {

    private var local = 10_000L
    private val clock = ServerClock { local }

    @Test
    fun usesLocalClockUntilTheServerAnswers() {
        assertEquals(10_000L, clock.now())
    }

    @Test
    fun appliesOffsetMeasuredAtTheMiddleOfTheRequest() {
        clock.onServerTime(serverTime = 50_000L, sentAt = 9_000L, receivedAt = 11_000L)
        assertEquals(50_000L, clock.now())
        local += 500
        assertEquals(50_500L, clock.now())
    }

    @Test
    fun neverGoesBackwards() {
        clock.onServerTime(serverTime = 50_000L, sentAt = 10_000L, receivedAt = 10_000L)
        assertEquals(50_000L, clock.now())
        // A later, smaller offset must not produce an earlier edit time.
        clock.onServerTime(serverTime = 40_000L, sentAt = 10_000L, receivedAt = 10_000L)
        assertEquals(50_000L, clock.now())
        local += 20_000
        assertEquals(60_000L, clock.now())
    }

    @Test
    fun ignoresMissingServerTime() {
        clock.onServerTime(serverTime = 0L, sentAt = 1L, receivedAt = 2L)
        assertEquals(10_000L, clock.now())
    }

    @Test
    fun atLeastIsRememberedSoLaterEditsAreNeverStampedEarlier() {
        // The item was last changed at 25 000 by a phone whose clock is right; this clock is behind.
        assertEquals(25_000L, clock.atLeast(25_000L)) // e.g. a delete stamped after the version seen
        assertEquals(25_000L, clock.now()) // the Undo that follows is not older than the delete
        local += 100
        assertEquals(25_000L, clock.now())
        assertEquals(25_000L, clock.atLeast(1_000L))
    }

    @Test
    fun offsetSurvivesARestart() {
        val store = MemoryOffsetStore()
        ServerClock(store) { local }.onServerTime(serverTime = 130_000L, sentAt = 10_000L, receivedAt = 10_000L)
        assertEquals(120_000L, store.saved)

        // Restarted offline: no server answer yet, but the last measured offset still applies.
        val restarted = ServerClock(store) { local }
        assertEquals(130_000L, restarted.now())
    }

    @Test
    fun aFreshMeasurementWinsOverTheStoredOffset() {
        val store = MemoryOffsetStore(saved = 500_000L)
        val restarted = ServerClock(store) { local }
        restarted.onServerTime(serverTime = 12_000L, sentAt = 10_000L, receivedAt = 10_000L)
        assertEquals(12_000L, restarted.now())
        assertEquals(2_000L, store.saved)
    }

    @Test
    fun onlySavesWhenTheOffsetReallyChanged() {
        val store = MemoryOffsetStore()
        val clock = ServerClock(store) { local }
        clock.onServerTime(serverTime = 15_000L, sentAt = 10_000L, receivedAt = 10_000L)
        clock.onServerTime(serverTime = 15_300L, sentAt = 10_000L, receivedAt = 10_000L) // jitter
        clock.onServerTime(serverTime = 14_800L, sentAt = 10_000L, receivedAt = 10_000L)
        assertEquals(1, store.saves)
        clock.onServerTime(serverTime = 20_000L, sentAt = 10_000L, receivedAt = 10_000L)
        assertEquals(2, store.saves)
        assertEquals(10_000L, store.saved)
    }

    private class MemoryOffsetStore(var saved: Long? = null) : ServerClock.OffsetStore {
        var saves = 0

        override fun load(): Long? = saved

        override fun save(offsetMs: Long) {
            saved = offsetMs
            saves++
        }
    }
}
