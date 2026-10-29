package org.openstrap.app.ui.workout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openstrap.app.data.WorkoutTarget
import org.openstrap.app.data.Route
import org.openstrap.app.device.ConnectionState

/** The Ready and live workout screens' words and rules (spec 6.3). */
class WorkoutCopyTest {
    private val connected = ConnectionState.Connected(batteryPercent = 80, firmware = null)

    @Test fun `the ready hero is the strap in every state`() {
        val live = WorkoutCopy.ReadyHero("Strap connected", "Live from your strap.", live = true)
        assertEquals(live, WorkoutCopy.readyHero(connected, 72))
        assertEquals(live.copy(line = "Waiting for heart rate."), WorkoutCopy.readyHero(connected, null))
        assertEquals(live.copy(line = "Live from your strap. Battery 15%."), WorkoutCopy.readyHero(connected.copy(batteryPercent = 15), 72))
        // Charging, a low battery is on its way up: not worth a word.
        assertEquals(live, WorkoutCopy.readyHero(connected.copy(batteryPercent = 15, charging = true), 72))
        val away = WorkoutCopy.ReadyHero("Strap not connected", "You can start anyway.", live = false)
        assertEquals(away.copy(title = "Connecting to the strap"), WorkoutCopy.readyHero(ConnectionState.Connecting, null))
        assertEquals(away.copy(title = "Connecting to the strap"), WorkoutCopy.readyHero(ConnectionState.Authenticating, null))
        assertEquals(away, WorkoutCopy.readyHero(ConnectionState.Disconnected, 72))
        assertEquals(away, WorkoutCopy.readyHero(ConnectionState.Failed("gone"), null))
        assertEquals(away.copy(title = "No strap paired"), WorkoutCopy.readyHero(ConnectionState.NotPaired, null))
    }

    @Test fun `gps and route notes`() {
        assertEquals("Off. No distance without GPS.", WorkoutCopy.gpsNote(false, 4))
        assertEquals("Off. Distance from the strap's steps, roughly.", WorkoutCopy.gpsNote(false, 1))
        assertEquals("On. Distance, pace and the map.", WorkoutCopy.gpsNote(true, 1))
        assertEquals("Off. Only the totals are kept.", WorkoutCopy.routeNote(false))
        assertEquals(1, WorkoutCopy.readyRows(outdoor = false).size)
        assertEquals(listOf("Heart rate", "GPS", "Keep the route"), WorkoutCopy.readyRows(outdoor = true).map { it.first })
    }

    @Test fun `zone name and time in it`() {
        assertEquals("Zone 3," to "tempo", WorkoutCopy.zoneTitle(2))
        assertEquals("9 min in this zone", WorkoutCopy.zoneTime(9 * 60 + 59))
        assertEquals("Under a minute in this zone", WorkoutCopy.zoneTime(42))
    }

    @Test fun `no workout string uses an interpunct, semicolon or em dash`() {
        val strings = listOf(
            WorkoutCopy.readyHero(connected.copy(batteryPercent = 5), 60).line, WorkoutCopy.readyHero(ConnectionState.NotPaired, null).line,
            WorkoutCopy.LOCATION_OFF, WorkoutCopy.LOCATION_OFF_NOTE, WorkoutCopy.startLabel(1),
            WorkoutCopy.gpsNote(true, 1), WorkoutCopy.gpsNote(false, 1), WorkoutCopy.gpsNote(false, 4),
            WorkoutCopy.routeNote(true), WorkoutCopy.routeNote(false), WorkoutCopy.READY_MEANING, WorkoutCopy.zoneTime(600),
        ) + WorkoutCopy.readyRows(true).flatMap { listOf(it.first, it.second) } + (0..4).flatMap { WorkoutCopy.zoneTitle(it).toList() }
        strings.forEach { s -> assertFalse("\"$s\"", s.any { it == '·' || it == ';' || it == '—' }) }
    }

    private val splits = listOf(
        Route.Split(0, endT = 1_300, seconds = 300, meters = 1_002.0),
        Route.Split(1, endT = 1_642, seconds = 342, meters = 1_004.0),
        Route.Split(2, endT = 1_700, seconds = 58, meters = 180.0),
    )
    private val history = listOf(1_000L to 120, 1_300L to 140, 1_400L to 150, 1_642L to 154, 1_650L to 170)

    @Test fun `the split line shows the latest whole kilometre for ten seconds`() {
        val line = WorkoutCopy.splitLine(splits, history, firstT = 1_000, now = 1_645)
        // Km 2 ran from 1300 to 1642: readings 140, 150 and 154.
        assertEquals(SplitLine(1, 1_642, "Km 2 in 5:42, avg 148"), line)
        assertEquals(line, WorkoutCopy.splitLine(splits, history, 1_000, now = 1_651))
        assertNull(WorkoutCopy.splitLine(splits, history, 1_000, now = 1_652))
        // A fix stamped just ahead of the phone's clock still shows.
        assertEquals(line, WorkoutCopy.splitLine(splits, history, 1_000, now = 1_640))
    }

    @Test fun `the first kilometre starts at the first fix, and no readings drop the average`() {
        val first = splits.take(1)
        assertEquals("Km 1 in 5:00, avg 130", WorkoutCopy.splitLine(first, history, firstT = 1_000, now = 1_300)?.text)
        assertEquals("Km 1 in 5:00", WorkoutCopy.splitLine(first, emptyList(), firstT = 1_000, now = 1_300)?.text)
        // Only a partial kilometre so far: nothing to say.
        assertNull(WorkoutCopy.splitLine(listOf(Route.Split(0, 1_100, 60, 300.0)), history, 1_000, 1_100))
        assertNull(WorkoutCopy.splitLine(emptyList(), history, null, 1_100))
    }

    @Test fun `readings add the live ones newer than the history`() {
        val recent = listOf(1_640L to 151, 1_642L to 154, 1_644L to 160)
        // The history runs to 1642: only the 1644 reading is new.
        assertEquals(history.take(4) + (1_644L to 160), WorkoutCopy.readings(history.take(4), recent))
        assertEquals(recent, WorkoutCopy.readings(emptyList(), recent))
    }

    @Test fun `one tick per split`() {
        val line = WorkoutCopy.splitLine(splits, history, 1_000, 1_645)
        assertEquals(1, WorkoutCopy.wholeKms(splits))
        assertEquals(-1, WorkoutCopy.wholeKms(splits.drop(2)))
        assertTrue(WorkoutCopy.splitTick(line, lastTicked = 0))
        assertFalse(WorkoutCopy.splitTick(line, lastTicked = 1))
        assertFalse(WorkoutCopy.splitTick(null, lastTicked = -1))
    }

    @Test fun `a zone tick needs a change between two readings`() {
        assertTrue(WorkoutCopy.zoneTick(1, 2))
        assertTrue(WorkoutCopy.zoneTick(3, 2))
        assertFalse(WorkoutCopy.zoneTick(2, 2))
        assertFalse(WorkoutCopy.zoneTick(null, 2))
        assertFalse(WorkoutCopy.zoneTick(2, null))
    }

    @Test fun `the last view is remembered, Heart by default`() {
        assertEquals(LiveView.Heart, LiveView.of(null))
        assertEquals(LiveView.Map, LiveView.of(LiveView.Map.ordinal))
        assertEquals(LiveView.Heart, LiveView.of(LiveView.Heart.ordinal))
        assertEquals(LiveView.Heart, LiveView.of(7))
        assertEquals(LiveView.Heart, LiveView.of(-1))
    }

    @Test fun `the light rises with the zone`() {
        assertNull(WorkoutCopy.zoneLightLevel(null))
        assertEquals(0.35f, WorkoutCopy.zoneLightLevel(0)!!, 1e-6f)
        assertEquals(1f, WorkoutCopy.zoneLightLevel(4)!!, 1e-6f)
        val levels = (0..4).map { WorkoutCopy.zoneLightLevel(it)!! }
        assertEquals(levels.sorted(), levels)
    }

    /* ------------------------------------------ Side figures ------------------------------------------ */

    private val indoor = WorkoutCopy.sideFigures(kcal = true, distance = false, gps = false)
    private val steps = WorkoutCopy.sideFigures(kcal = true, distance = true, gps = false)
    private val gps = WorkoutCopy.sideFigures(kcal = true, distance = true, gps = true)

    @Test fun `each kind of workout opens on its own side figures`() {
        assertEquals(listOf(SideFigure.Avg, SideFigure.Max, SideFigure.Kcal), indoor)
        assertEquals(listOf(SideFigure.Avg, SideFigure.Max, SideFigure.Kcal, SideFigure.Distance), steps)
        assertEquals(SideFigure.entries, gps)
        assertEquals(Sides(SideFigure.Kcal, SideFigure.Avg), WorkoutCopy.sides(null, indoor))
        assertEquals(Sides(SideFigure.Distance, SideFigure.Kcal), WorkoutCopy.sides(null, steps))
        assertEquals(Sides(SideFigure.Distance, SideFigure.Pace), WorkoutCopy.sides(null, gps))
        // Without a profile kcal isn't offered, and the row is the average and the peak as before.
        val noKcal = WorkoutCopy.sideFigures(kcal = false, distance = false, gps = false)
        assertEquals(Sides(SideFigure.Avg, SideFigure.Max), WorkoutCopy.sides(null, noKcal))
        assertEquals(Sides(SideFigure.Distance, SideFigure.Avg), WorkoutCopy.sides(null, WorkoutCopy.sideFigures(kcal = false, distance = true, gps = false)))
    }

    @Test fun `a tap steps through what the workout has, skipping the far side`() {
        // Indoor: kcal | Avg. The left goes on past Avg (the right's) to Max, then wraps to kcal.
        var s = WorkoutCopy.sides(null, indoor)
        s = WorkoutCopy.tapped(s, left = true, indoor)
        assertEquals(Sides(SideFigure.Max, SideFigure.Avg), s)
        s = WorkoutCopy.tapped(s, left = true, indoor)
        assertEquals(Sides(SideFigure.Kcal, SideFigure.Avg), s)
        // GPS: Distance | Pace. The right wraps from Pace to Avg, then Max, kcal, and past Distance back to Pace.
        s = WorkoutCopy.sides(null, gps)
        val rights = (1..4).map { s = WorkoutCopy.tapped(s, left = false, gps); s.right }
        assertEquals(listOf(SideFigure.Avg, SideFigure.Max, SideFigure.Kcal, SideFigure.Pace), rights)
        assertTrue(rights.none { it == SideFigure.Distance })
    }

    @Test fun `a saved choice holds where the workout has it`() {
        val saved = Sides(SideFigure.Max, SideFigure.Pace)
        assertEquals(saved, WorkoutCopy.sides(saved, gps))
        // Indoor there's no pace: that side takes its default.
        assertEquals(Sides(SideFigure.Max, SideFigure.Avg), WorkoutCopy.sides(saved, indoor))
        // Distance chosen on the left, before there is one: the default left, kcal, and the right moves off it.
        assertEquals(Sides(SideFigure.Kcal, SideFigure.Avg), WorkoutCopy.sides(Sides(SideFigure.Distance, SideFigure.Kcal), indoor))
        // Kept per sport through the setting's text; broken parts are dropped.
        val all = mapOf(8 to Sides(SideFigure.Kcal, SideFigure.Max), 1 to saved)
        assertEquals("1=Max:Pace,8=Kcal:Max", WorkoutCopy.formatSides(all))
        assertEquals(all, WorkoutCopy.parseSides(WorkoutCopy.formatSides(all)))
        assertEquals(mapOf(8 to Sides(SideFigure.Kcal, SideFigure.Max)), WorkoutCopy.parseSides("8=Kcal:Max,2=Kcal:Kcal,3=Steps:Avg,x=Avg:Max,"))
        assertEquals(emptyMap<Int, Sides>(), WorkoutCopy.parseSides(null))
    }

    @Test fun `kcal reads as whole active kcal`() {
        assertEquals("312 kcal", WorkoutCopy.kcalText(312.9f))
        assertNull(WorkoutCopy.kcalText(null))
    }

    @Test fun `a kcal target says when the strap buzzes and how far the workout is`() {
        assertEquals("Target. The strap buzzes at 300 active kcal.", WorkoutCopy.targetHint(WorkoutTarget.Kcal(300)))
        assertEquals("Target. The strap buzzes if you drift out.", WorkoutCopy.targetHint(WorkoutTarget.Zones(1, 2)))
        assertEquals("Target. The strap buzzes if you drift out.", WorkoutCopy.targetHint(null))
        assertEquals("212 of 300 kcal", WorkoutCopy.kcalProgress(212.8f, WorkoutTarget.Kcal(300)))
        assertEquals("0 of 500 kcal", WorkoutCopy.kcalProgress(null, WorkoutTarget.Kcal(500)))
        listOf(WorkoutCopy.targetHint(WorkoutTarget.Kcal(800)), WorkoutCopy.kcalProgress(1f, WorkoutTarget.Kcal(800)))
            .forEach { l -> listOf("·", ";", "—").forEach { b -> assertFalse("$l has $b", l.contains(b)) } }
    }
}
