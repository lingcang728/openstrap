package org.openstrap.app.ui.workout

import org.openstrap.app.data.Route
import org.openstrap.app.data.WorkoutTarget
import org.openstrap.app.data.Workouts
import org.openstrap.app.device.ConnectionState
import org.openstrap.app.ui.glow.workoutClock

/*
 * What the Ready and live workout screens say (spec 6.3), and the small rules behind what they show: the split line's
 * ten seconds, the remembered view, the light that follows the zone. Pure, so it's tested without a workout running.
 */

/** The two views of a GPS workout's live screen, in swipe order. */
enum class LiveView {
    Heart, Map;

    companion object {
        /** The view saved as [code] (its ordinal), Heart when there's none or it's unknown. */
        fun of(code: Int?): LiveView = code?.let { entries.getOrNull(it) } ?: Heart
    }
}

/**
 * The figures the live screen's row can show either side of the moving time, in the order a tap steps through them.
 * Saved by name, so adding one later keeps the choices made.
 */
enum class SideFigure {
    Avg, Max, Kcal, Distance, Pace;

    companion object {
        fun of(name: String?): SideFigure? = entries.firstOrNull { it.name == name }
    }
}

/** The two side figures of the live row. */
data class Sides(val left: SideFigure, val right: SideFigure)

/** A finished kilometre on the live screen: which one ([index] from 0), when it ended, and its line. */
data class SplitLine(val index: Int, val endT: Long, val text: String)

object WorkoutCopy {
    /** How long a split line stays under the stats. */
    const val SPLIT_LINE_S = 10L

    /** At or under this, the Ready line mentions the strap's battery. */
    const val LOW_BATTERY = 20

    private const val KM = 1000.0

    /**
     * Ready's hero, the strap (spec 6.3): its [title], the [line] under it, and whether it's [live], the glow with the
     * heart rate, or an outline card while the strap is away.
     */
    data class ReadyHero(val title: String, val line: String, val live: Boolean)

    /** "Strap connected" and "Live from your strap.", with the battery when low; "Strap not connected" otherwise. */
    fun readyHero(connection: ConnectionState, bpm: Int?): ReadyHero = when (connection) {
        is ConnectionState.Connected -> ReadyHero(
            "Strap connected",
            listOfNotNull(
                if (bpm != null) "Live from your strap." else "Waiting for heart rate.",
                connection.batteryPercent?.takeIf { it <= LOW_BATTERY && !connection.charging }?.let { "Battery $it%." },
            ).joinToString(" "),
            live = true,
        )
        ConnectionState.Connecting, ConnectionState.Authenticating -> ReadyHero("Connecting to the strap", ANYWAY, live = false)
        ConnectionState.NotPaired -> ReadyHero("No strap paired", ANYWAY, live = false)
        ConnectionState.Disconnected, is ConnectionState.Failed -> ReadyHero("Strap not connected", ANYWAY, live = false)
    }

    private const val ANYWAY = "You can start anyway."

    /** Under GPS: what it gives, or what's missing without it. */
    fun gpsNote(on: Boolean, sportType: Int): String = when {
        on -> "On. Distance, pace and the map."
        sportType in Workouts.ON_FOOT -> "Off. Distance from the strap's steps, roughly."
        else -> "Off. No distance without GPS."
    }

    /** The GPS row while the phone's location is off or not allowed. Start still works, without GPS. */
    const val LOCATION_OFF = "Location is off"
    const val LOCATION_OFF_NOTE = "Turn it on for distance and the map, or start without it."

    /** Under Keep the route. */
    fun routeNote(on: Boolean): String = if (on) "On. Saved on this phone for the map." else "Off. Only the totals are kept."

    /** Start, naming the sport: "Start run", "Start HIIT", and "Start workout" for Other. */
    fun startLabel(sportType: Int): String = when (val name = Workouts.shortName(sportType)) {
        "Other" -> "Start workout"
        else -> "Start " + if (name.any { it.isLowerCase() }) name.lowercase() else name
    }

    /** The Ready info sheet's meaning. */
    const val READY_MEANING = "Heart rate comes from the strap every second. GPS is on for outdoor workouts, and you can turn it off."

    /** The Ready info sheet's rows: the strap always, GPS and the route for an [outdoor] sport. */
    fun readyRows(outdoor: Boolean): List<Pair<String, String>> = listOfNotNull(
        "Heart rate" to "The strap sends heart rate every second, with the phone's screen off too. If it drops out or " +
            "isn't connected at the start, recording carries on, and the missing minutes are filled from the strap's own " +
            "history after the next sync.",
        ("GPS" to "Outdoors, GPS uses the phone's location during this workout only, for distance, pace and the map. " +
            "Its switch turns it off for this workout. Map tiles come from OpenFreeMap, which sees roughly where the map " +
            "is. Route only on the map loads no tiles. Without GPS, walks and runs get a distance from the strap's " +
            "steps, marked ≈.").takeIf { outdoor },
        ("Keep the route" to "With Keep the route on, the route is saved on this phone for the map. Off, only the " +
            "totals are kept and the route is discarded when the workout ends.").takeIf { outdoor },
    )

    /** "Zone 3, aerobic" as its two parts, drawn as two text elements. */
    fun zoneTitle(zone: Int): Pair<String, String> = "Zone ${zone + 1}," to ZONE_NAMES[zone].lowercase()

    /** "9 min in this zone". */
    fun zoneTime(seconds: Int): String = if (seconds < 60) "Under a minute in this zone" else "${seconds / 60} min in this zone"

    /**
     * The line for the latest whole kilometre in [splits], for [SPLIT_LINE_S] after it ended, else null: "Km 3 in 5:42,
     * avg 152". The average is the readings in [history] (time, bpm) over that kilometre, which starts where the one
     * before ended, or at [firstT] for the first; without readings the line leaves it out.
     */
    fun splitLine(splits: List<Route.Split>, history: List<Pair<Long, Int>>, firstT: Long?, now: Long): SplitLine? {
        val i = splits.indexOfLast { it.meters >= KM }
        if (i < 0) return null
        val sp = splits[i]
        // A fix stamped a moment ahead of the phone's clock still shows; one long ago doesn't.
        if (now - sp.endT >= SPLIT_LINE_S || sp.endT - now > SPLIT_LINE_S) return null
        val from = splits.getOrNull(i - 1)?.endT ?: firstT ?: sp.endT
        val avg = averageOver(history, from, sp.endT)
        return SplitLine(i, sp.endT, "Km ${i + 1} in ${workoutClock(sp.seconds)}" + (avg?.let { ", avg $it" } ?: ""))
    }

    /** The average of the [readings] (time, bpm) from [from] to [to], whole bpm; null without any. */
    fun averageOver(readings: List<Pair<Long, Int>>, from: Long, to: Long): Int? =
        readings.filter { it.first in from..to }.takeIf { it.isNotEmpty() }?.map { it.second }?.average()?.toInt()

    /**
     * Every reading so far for [splitLine]: the session's [history], refreshed every few seconds, then the [recent]
     * live readings newer than it, so a kilometre's last seconds count.
     */
    fun readings(history: List<Pair<Long, Int>>, recent: List<Pair<Long, Int>>): List<Pair<Long, Int>> {
        val last = history.lastOrNull()?.first ?: return recent
        return history + recent.filter { it.first > last }
    }

    /** The index of the latest whole kilometre, or -1: the splits already done when the live screen opens. */
    fun wholeKms(splits: List<Route.Split>): Int = splits.indexOfLast { it.meters >= KM }

    /** Whether [line] is a kilometre not ticked for yet ([lastTicked] its index): one tick per split. */
    fun splitTick(line: SplitLine?, lastTicked: Int): Boolean = line != null && line.index > lastTicked

    /** Whether going from zone [previous] to [current] ticks: a change between two readings, not the first one. */
    fun zoneTick(previous: Int?, current: Int?): Boolean = previous != null && current != null && previous != current

    /* ------------------------------------------ Side figures ------------------------------------------ */

    /**
     * What this workout has to show either side of the moving time, in tap order: the average and peak always, kcal
     * with a profile ([kcal]), the distance once there is one, and the pace only with GPS ([gps], not from steps).
     */
    fun sideFigures(kcal: Boolean, distance: Boolean, gps: Boolean): List<SideFigure> = SideFigure.entries.filter {
        when (it) {
            SideFigure.Avg, SideFigure.Max -> true
            SideFigure.Kcal -> kcal
            SideFigure.Distance -> distance
            SideFigure.Pace -> distance && gps
        }
    }

    /**
     * The row's sides before any tap: kcal and Avg without a distance, the distance and kcal from steps,
     * the distance and the pace with GPS. Without a profile kcal's place goes to Avg, or Max beside it.
     */
    fun defaultSides(available: List<SideFigure>): Sides {
        val kcal = SideFigure.Kcal in available
        return when {
            SideFigure.Pace in available -> Sides(SideFigure.Distance, SideFigure.Pace)
            SideFigure.Distance in available -> Sides(SideFigure.Distance, if (kcal) SideFigure.Kcal else SideFigure.Avg)
            kcal -> Sides(SideFigure.Kcal, SideFigure.Avg)
            else -> Sides(SideFigure.Avg, SideFigure.Max)
        }
    }

    /**
     * The sides shown: the ones [saved] for this sport where this workout has them, else the default for that side.
     * The two are never the same figure: on a clash the left keeps its figure and the right moves on.
     */
    fun sides(saved: Sides?, available: List<SideFigure>): Sides {
        val default = defaultSides(available)
        val left = saved?.left?.takeIf { it in available } ?: default.left
        val right = saved?.right?.takeIf { it in available } ?: default.right
        return Sides(left, if (right == left) nextFigure(right, left, available) else right)
    }

    /** The figure after [current] in tap order among [available], wrapping round and skipping [other], the far side's. */
    fun nextFigure(current: SideFigure, other: SideFigure, available: List<SideFigure>): SideFigure {
        val order = SideFigure.entries.filter { it in available && it != other }
        if (order.isEmpty()) return current
        return order.firstOrNull { it.ordinal > current.ordinal } ?: order.first()
    }

    /** [sides] after a tap on the left ([left]) or the right figure. */
    fun tapped(sides: Sides, left: Boolean, available: List<SideFigure>): Sides =
        if (left) sides.copy(left = nextFigure(sides.left, sides.right, available))
        else sides.copy(right = nextFigure(sides.right, sides.left, available))

    /** The sides saved per sport type, "8=Kcal:Avg,1=Distance:Kcal"; unknown parts are dropped. */
    fun parseSides(text: String?): Map<Int, Sides> = text.orEmpty().split(',').mapNotNull { part ->
        val (k, v) = part.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
        val sport = k.trim().toIntOrNull() ?: return@mapNotNull null
        val (l, r) = v.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
        val left = SideFigure.of(l.trim()) ?: return@mapNotNull null
        val right = SideFigure.of(r.trim()) ?: return@mapNotNull null
        if (left == right) null else sport to Sides(left, right)
    }.toMap()

    fun formatSides(sides: Map<Int, Sides>): String =
        sides.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value.left.name}:${it.value.right.name}" }

    /** Under the row until the first tap on a side figure. */
    const val SIDES_HINT = "Tap a figure to change it"

    /** The label over Ready's targets: what the strap does for the one chosen. */
    fun targetHint(target: WorkoutTarget?): String = when (target) {
        is WorkoutTarget.Kcal -> "Target. The strap buzzes at ${target.kcal} active kcal."
        else -> "Target. The strap buzzes if you drift out."
    }

    /** The live heart card's progress toward a kcal target: "212 of 300 kcal". */
    fun kcalProgress(activeKcal: Float?, target: WorkoutTarget.Kcal): String = "${activeKcal?.toInt() ?: 0} of ${target.label}"

    /** "312 kcal" for the notification and the capsule; null before there's a figure. */
    fun kcalText(activeKcal: Float?): String? = activeKcal?.let { "${it.toInt()} kcal" }

    /**
     * How high the live heart card's light reaches for [zone] (spec 2.5): about a third of the card in zone 1, all of it
     * in zone 5. Null without a reading, for the card's usual light.
     */
    fun zoneLightLevel(zone: Int?): Float? = zone?.let { 0.35f + 0.65f * it.coerceIn(0, Workouts.ZONES - 1) / (Workouts.ZONES - 1) }
}
