package org.openstrap.app.ui.workout

import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openstrap.app.data.Route

/** The summary right after a workout: the heart rate drop, Load, splits and a personal best (spec 6.3). */
class SummaryCopyTest {
    private val end = 1_000_000L
    private val maxHr = 190

    /** A second a reading: [bpm] up to the end, then falling by [perSecond] for two minutes. */
    private fun readings(bpm: Int, perSecond: Double): List<Pair<Long, Int>> =
        (-60L..120L).map { s -> (end + s) to if (s <= 0) bpm else (bpm - perSecond * s).toInt() }

    @Test fun `while the first minute runs it says so, then the drop`() {
        val r = readings(160, 0.5).filter { it.first <= end + 20 }
        val watching = SummaryCopy.calmDown(r, end, now = end + 20, live = true, maxHr = maxHr, usualDrop = null)
        assertTrue(watching.watching)
        assertEquals(SummaryCopy.WATCHING, watching.text)
        // Two seconds past the minute the median there is complete.
        assertTrue(SummaryCopy.calmDown(r, end, end + 62, live = true, maxHr = maxHr, usualDrop = null).watching)
        val done = SummaryCopy.calmDown(readings(160, 0.5), end, end + 63, live = true, maxHr = maxHr, usualDrop = null)
        assertFalse(done.watching)
        assertEquals(30, done.drop)
        assertEquals("Down 30 in the first minute.", done.text)
    }

    @Test fun `stored readings are never watched`() {
        val done = SummaryCopy.calmDown(readings(160, 0.5), end, end + 10, live = false, maxHr = maxHr, usualDrop = null)
        assertFalse(done.watching)
        assertEquals(30, done.drop)
    }

    @Test fun `no readings after the end, no drop`() {
        val r = readings(160, 0.5).filter { it.first <= end }
        val c = SummaryCopy.calmDown(r, end, end + 300, live = true, maxHr = maxHr, usualDrop = 20)
        assertNull(c.drop)
        assertEquals(SummaryCopy.NO_DROP_DATA, c.text)
    }

    @Test fun `the drop against your usual`() {
        assertEquals("Down 34 in the first minute. A quick drop for you.", SummaryCopy.dropLine(34, 160, maxHr, 25))
        assertEquals("Down 28 in the first minute. About usual for you.", SummaryCopy.dropLine(28, 160, maxHr, 25))
        assertEquals("Down 16 in the first minute. A slow drop for you.", SummaryCopy.dropLine(16, 160, maxHr, 25))
        assertEquals("Down 16 in the first minute.", SummaryCopy.dropLine(16, 160, maxHr, null))
        // Ending close to resting there's little to come down from: no comparing.
        assertEquals("Down 12 in the first minute, from an easy finish.", SummaryCopy.dropLine(12, 100, maxHr, 25))
        assertEquals("No drop in the first minute.", SummaryCopy.dropLine(-3, 160, maxHr, 25))
        assertEquals(SummaryCopy.NO_DROP_DATA, SummaryCopy.dropLine(null, 160, maxHr, 25))
    }

    @Test fun `usual drop is a median once there are three`() {
        assertNull(SummaryCopy.usualDrop(listOf(20, 30)))
        assertEquals(25, SummaryCopy.usualDrop(listOf(20, 25, 40)))
        assertEquals(23, SummaryCopy.usualDrop(listOf(20, 25, 21, 40)))
        // Only the latest ten count.
        assertEquals(10, SummaryCopy.usualDrop(List(10) { 10 } + List(10) { 50 }))
    }

    @Test fun `load against your usual`() {
        assertEquals("Above your usual of 80", SummaryCopy.loadLine(120, 80f))
        assertEquals("About your usual", SummaryCopy.loadLine(90, 80f))
        assertEquals("Below your usual of 80", SummaryCopy.loadLine(40, 80f))
        assertEquals("Learning your usual", SummaryCopy.loadLine(40, null))
        assertEquals("From this run", SummaryCopy.loadTitle(1))
        assertEquals("From this workout", SummaryCopy.loadTitle(52))
    }

    @Test fun `time to recover and the workout line`() {
        assertEquals("Time to recover 40 min", SummaryCopy.timeToRecover(40))
        assertEquals("Time to recover 14 h", SummaryCopy.timeToRecover(14 * 60 + 10))
        assertNull(SummaryCopy.timeToRecover(0))
        assertEquals("5.21 km, average 142 bpm. Paused 4 min.", SummaryCopy.workoutLine(5210f, false, 142, 250))
        assertEquals("≈ 820 m.", SummaryCopy.workoutLine(820f, true, null, 30))
        assertEquals("Average 120 bpm.", SummaryCopy.workoutLine(null, false, 120, 0))
        assertNull(SummaryCopy.workoutLine(null, false, null, 0))
        assertEquals("18:02–18:40, 38:12", SummaryCopy.trimHeader("18:02", "18:40", 38 * 60 + 12))
        assertEquals("1.02 km, average 150 bpm", SummaryCopy.lapLine(1020.0, 150))
        assertEquals("Average 150 bpm", SummaryCopy.lapLine(null, 150))
        assertNull(SummaryCopy.lapLine(null, null))
    }

    @Test fun `splits list whole kilometres then a last part`() {
        val splits = listOf(
            Route.Split(0, 100L + 342, 342, 1000.0),
            Route.Split(1, 100L + 700, 358, 1000.0),
            Route.Split(2, 100L + 850, 150, 430.0),
        )
        val hr = (100L..950L).map { it to if (it < 442) 150 else 160 }
        val rows = SummaryCopy.splitRows(splits, hr, firstT = 100L)
        assertEquals(listOf("Km 1", "Km 2", "Last 430 m"), rows.map { it.name })
        assertEquals("5:42", rows[0].time)
        assertEquals(150, rows[0].averageBpm)
        assertEquals(160, rows[1].averageBpm)
        // A last bit under 100 m is left out.
        assertEquals(2, SummaryCopy.splitRows(splits.dropLast(1) + Route.Split(2, 900, 20, 60.0), hr, 100L).size)
        assertNull(SummaryCopy.splitRows(splits, emptyList(), 100L)[0].averageBpm)
    }

    private fun day(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).atStartOfDay(ZoneOffset.UTC).toEpochSecond()
    private fun run(at: Long, km: Float?, minutes: Long = 30) = BestCandidate(at, 1, minutes * 60, km?.times(1000f))
    private fun best(w: BestCandidate, others: List<BestCandidate>) = SummaryCopy.personalBest(w, others, ZoneOffset.UTC, Locale.UK)

    @Test fun `personal best, farthest since the last farther one`() {
        val now = day(2026, 10, 20)
        val others = listOf(run(day(2026, 3, 5), 8f), run(day(2026, 6, 1), 5f), run(day(2026, 8, 1), 6f), run(day(2026, 10, 1), 5f))
        assertEquals("Farthest run since March.", best(run(now, 7f), others))
        // Beating only two since then is too little to call.
        assertNull(best(run(now, 7f, minutes = 20), others.filter { it.start != day(2026, 8, 1) }))
        // A farther one under a month ago: nothing to say.
        assertNull(best(run(now, 4.9f, minutes = 20), others + run(day(2026, 10, 10), 6f, minutes = 25)))
        // From another year, the year shows.
        val lastYear = listOf(run(day(2025, 3, 5), 8f)) + List(3) { run(day(2025, 11, 1 + it), 5f) }
        assertEquals("Farthest run since March 2025.", best(run(day(2026, 1, 20), 7f), lastYear))
    }

    @Test fun `personal best yet only with enough before`() {
        val now = day(2026, 10, 20)
        val few = List(3) { run(day(2026, 9, 1 + it), 5f) }
        assertNull(best(run(now, 7f), few))
        val many = List(5) { run(day(2026, 9, 1 + it), 5f) }
        assertEquals("Your farthest run yet.", best(run(now, 7f), many))
    }

    @Test fun `personal best falls back to the longest by time`() {
        val now = day(2026, 10, 20)
        val walks = listOf(BestCandidate(day(2026, 2, 1), 3, 90 * 60, null)) + List(3) { BestCandidate(day(2026, 9, 1 + 7 * it), 3, 40 * 60, null) }
        assertEquals("Longest walk since February.", best(BestCandidate(now, 3, 60 * 60, null), walks))
        // Other sports never count.
        assertNull(best(BestCandidate(now, 3, 60 * 60, null), walks.map { it.copy(sportType = 1) }))
        // Too short to be a best.
        assertNull(best(BestCandidate(now, 3, 5 * 60, null), emptyList()))
    }

    @Test
    fun `the last part is what the workout's distance leaves after the whole kilometres`() {
        // The raw route runs to 2185 m, the workout's smoothed distance is 2176 m.
        val splits = listOf(Route.Split(0, 300, 300, 1000.0), Route.Split(1, 600, 300, 1000.0), Route.Split(2, 640, 40, 185.0))
        assertEquals("Last 176 m", SummaryCopy.splitRows(splits, emptyList(), 0L, totalMeters = 2176f).last().name)
        assertEquals("Last 185 m", SummaryCopy.splitRows(splits, emptyList(), 0L).last().name)
    }

    @Test fun `the workout card says its calories, active first`() {
        assertEquals("486 active kcal, total 1,612", SummaryCopy.kcalLine(486, 1612, Locale.UK))
        assertEquals("612 kcal", SummaryCopy.kcalLine(null, 612, Locale.UK))
        assertNull(SummaryCopy.kcalLine(null, null))
        assertNull(SummaryCopy.kcalLine(0, 0))
    }
}
