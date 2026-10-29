package org.openstrap.app.data

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.openstrap.app.data.db.NapEdit
import org.openstrap.app.data.db.SleepSession
import org.openstrap.app.data.db.SleepStage

class NapTimesTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private fun at(h: Int, m: Int, day: Int = 30) = LocalDateTime.of(2026, 10, day, h, m).atZone(zone).toEpochSecond()
    private val nap = SleepSession(at(14, 10), at(14, 48), nap = true)

    @Test fun `a changed nap takes the user's times, anything else stays`() {
        val edits = listOf(NapEdit(nap.start, at(14, 2), at(14, 55)))
        val main = SleepSession(at(0, 30), at(7, 0))
        val out = NapTimes.applyAll(listOf(main, nap), edits)
        assertSame(main, out[0])
        assertEquals(at(14, 2), out[1].start)
        assertEquals(at(14, 55), out[1].end)
        // A main sleep starting at the same moment is never moved.
        assertSame(main, NapTimes.apply(main, mapOf(main.start to NapEdit(main.start, 1, 2))))
    }

    @Test fun `stages are trimmed to the nap's times`() {
        val stages = listOf(
            SleepStage(at(14, 10), at(14, 20), nap.start, SleepStage.LIGHT),
            SleepStage(at(14, 20), at(14, 40), nap.start, SleepStage.DEEP),
            SleepStage(at(14, 40), at(14, 48), nap.start, SleepStage.LIGHT),
        )
        val out = NapTimes.clip(stages, at(14, 15), at(14, 40))
        assertEquals(listOf(at(14, 15) to at(14, 20), at(14, 20) to at(14, 40)), out.map { it.start to it.end })
    }

    @Test fun `times that can't be a nap are refused`() {
        val now = at(18, 0)
        assertNull(NapTimes.problem(nap, at(14, 0), at(15, 0), now))
        assertEquals("Waking up has to come after falling asleep.", NapTimes.problem(nap, at(14, 30), at(14, 20), now))
        assertEquals("A nap needs at least 5 minutes.", NapTimes.problem(nap, at(14, 30), at(14, 33), now))
        assertEquals("A nap can be 4 hours at most.", NapTimes.problem(nap, at(11, 0), at(15, 30), now))
        assertEquals("That's later than now.", NapTimes.problem(nap, at(16, 0), at(18, 30), now))
        assertEquals("Keep it within 3 hours of what the strap saw.", NapTimes.problem(nap, at(10, 30), at(11, 0), now))
    }

    @Test fun `the strap's own times again drop the edit`() {
        assertNull(NapTimes.edit(nap, nap.start, nap.end))
        assertEquals(NapEdit(nap.start, at(14, 5), nap.end), NapTimes.edit(nap, at(14, 5), nap.end))
    }

    @Test fun `a picked clock is read on the day nearest the nap`() {
        assertEquals(at(14, 5), NapTimes.atNear(14 * 60 + 5, nap.start, zone))
        // A late nap running past midnight: 00:20 is the next day's.
        assertEquals(at(0, 20, day = 31), NapTimes.atNear(20, at(23, 50), zone))
        // And 23:40 picked for a nap the strap started at 00:10 is the evening before.
        assertEquals(at(23, 40, day = 29), NapTimes.atNear(23 * 60 + 40, at(0, 10), zone))
    }
}
