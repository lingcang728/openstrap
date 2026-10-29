package org.openstrap.app.ui.workout

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.openstrap.app.data.Workouts
import org.openstrap.app.ui.dashboard.Info
import org.openstrap.app.ui.dashboard.TodayCopy
import org.openstrap.app.ui.dashboard.sportNoun
import org.openstrap.app.ui.glow.km
import org.openstrap.app.ui.glow.workoutClock

/*
 * What the page of a past workout says (spec 6.3, "Workout detail"): the sentence first ("Hard run, mostly in zone
 * 4."), the facts, the heart rate drop, and training effect and EPOC explained behind the (i). Pure, so it's tested
 * without a workout.
 */

/** A small fact on the workout's list: [name], [value] in dot-matrix digits, and the [status] under the name. */
data class WorkoutFact(val name: String, val value: String, val status: String?)

/** What a workout's other figures look like, for the strap's own record of a phone workout. */
data class EffectFigures(val aerobic: Float?, val anaerobic: Float?, val load: Int?, val recoveryMinutes: Int?)

/** The (i) on the Load card: the sheet's words, the bar of the 0 to 5 scale this workout is in, and its folded rows. */
data class EffectSheet(val info: Info, val here: Int?, val hereLine: String?, val rows: List<Pair<String, String>>)

object WorkoutDetailCopy {
    /** Shorter than this and a workout is "Short", whatever its Load says. */
    const val SHORT_S = 5 * 60L

    /** Less time in the zones than this says nothing about where the workout sat. */
    const val MIN_ZONE_S = 60

    /** A paused stretch shorter than this isn't worth a row. */
    const val MIN_PAUSED_S = 60L

    /** The effort word, read from [Workouts.effort]: half is your usual Load, full twice it or more. */
    enum class Effort(val word: String) { Easy("Easy"), Steady("Steady"), Hard("Hard"), VeryHard("Very hard") }

    /**
     * How hard a workout was from its [load] against your [usual] workout Load ([Workouts.usualLoad]). The bands match
     * the Load card's: under three quarters of your usual is Easy, within a quarter of it Steady, above that Hard, and
     * from 1.75 times it Very hard. Without a usual the fixed scale behind [Workouts.effort] is used. Null without Load.
     */
    fun effort(load: Int?, usual: Float?): Effort? {
        if (load == null || load <= 0) return null
        val e = Workouts.effort(load, usual)
        return when {
            e < 0.5f * (1 - TodayCopy.LOAD_BAND) -> Effort.Easy
            e <= 0.5f * (1 + TodayCopy.LOAD_BAND) -> Effort.Steady
            e < 0.875f -> Effort.Hard
            else -> Effort.VeryHard
        }
    }

    /** The sport as it reads mid-sentence: "run", "strength training", "HIIT". */
    fun sport(sportType: Int): String = sportNoun(sportType) ?: Workouts.sportName(sportType).let { name ->
        val first = name.substringBefore(' ')
        if (first.length > 1 && first.all { it.isUpperCase() }) name else name.replaceFirstChar { it.lowercase() }
    }

    /** The zone (0 for zone 1) with the most of [zoneSeconds]; null with less than [MIN_ZONE_S] in all of them. */
    fun mostlyZone(zoneSeconds: List<Int>): Int? {
        if (zoneSeconds.sum() < MIN_ZONE_S) return null
        return zoneSeconds.indices.maxByOrNull { zoneSeconds[it] }
    }

    /**
     * The hero's sentence: "Hard run, mostly in zone 4." The effort word from [effort], "Short" under [SHORT_S] of
     * [activeSeconds], and without Load just the sport ("A run", "Strength training"). The zone part goes when there's
     * too little zone time.
     */
    fun sentence(sportType: Int, activeSeconds: Long, load: Int?, usual: Float?, zoneSeconds: List<Int>): String {
        val sport = sport(sportType)
        val word = if (activeSeconds < SHORT_S) "Short" else effort(load, usual)?.word
        val head = when {
            word != null -> "$word $sport"
            sportNoun(sportType) != null -> "A $sport"
            else -> sport.replaceFirstChar { it.uppercase() }
        }
        return head + (mostlyZone(zoneSeconds)?.let { ", mostly in zone ${it + 1}" } ?: "") + "."
    }

    /** The hero's day: "Today", "Yesterday", "Tue 21 Oct", with the year when it isn't [today]'s. */
    fun day(start: Long, today: LocalDate, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        val d = Instant.ofEpochSecond(start).atZone(zone).toLocalDate()
        return when {
            d == today -> "Today"
            d == today.minusDays(1) -> "Yesterday"
            d.year == today.year -> d.format(DateTimeFormatter.ofPattern("EEE d MMM", locale))
            else -> d.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy", locale))
        }
    }

    /**
     * The workout's small facts, each left out when there's nothing to say: distance (from GPS, or estimated from
     * steps), time paused past [MIN_PAUSED_S], steps and calories. Calories lead with [activeKcal] and say the total
     * [calories] under it (calories spec 4.2); without an active figure of its own the total stands alone, in kcal.
     */
    fun facts(
        meters: Float?, fromSteps: Boolean, pausedSeconds: Long, steps: Int?, calories: Int?, activeKcal: Int? = null,
        locale: Locale = Locale.getDefault(),
    ): List<WorkoutFact> =
        listOfNotNull(
            meters?.takeIf { it >= 10f }?.let { m ->
                val unit = if (m >= 1000) "Kilometres" else "Metres"
                WorkoutFact("Distance", if (m >= 1000) km(m) else m.toInt().toString(), if (fromSteps) "$unit, estimated from your steps" else unit)
            },
            pausedSeconds.takeIf { it >= MIN_PAUSED_S }?.let { WorkoutFact("Paused", workoutClock(it), "Not counted in the time") },
            steps?.takeIf { it > 0 }?.let { WorkoutFact("Steps", "%,d".format(locale, it), null) },
            calories?.takeIf { it > 0 }?.let { total ->
                if (activeKcal != null && activeKcal != total) WorkoutFact("Calories", "%,d".format(locale, activeKcal), "Total %,d".format(locale, total))
                else WorkoutFact("Calories", "%,d".format(locale, total), "kcal")
            },
        )

    /**
     * The Load card's heart rate drop line: "Heart rate drop 34 bpm in the first minute, quick for you." Only after a
     * real effort ([meaningful]); against your [usual] drop when there is one ([SummaryCopy.usualDrop]). Null otherwise.
     */
    fun dropLine(drop: Int?, meaningful: Boolean, usual: Int?): String? {
        if (drop == null || drop <= 0 || !meaningful) return null
        val head = "Heart rate drop $drop bpm in the first minute"
        return when {
            usual == null -> "$head."
            drop >= usual + SummaryCopy.DROP_BAND -> "$head, quick for you."
            drop <= usual - SummaryCopy.DROP_BAND -> "$head, slow for you."
            else -> "$head, about usual for you."
        }
    }

    /** The 0 to 5 training effect, low to high, in plain words. */
    val EFFECT_SCALE = listOf("Light", "Keeps", "Builds", "Builds a lot", "Overreach")

    /** Which bar of [EFFECT_SCALE] a training effect is in. */
    fun effectBand(te: Float): Int = when {
        te < 2f -> 0
        te < 3f -> 1
        te < 4f -> 2
        te < 5f -> 3
        else -> 4
    }

    private fun one(x: Float) = String.format(Locale.ROOT, "%.1f", x)

    /**
     * The (i) on the Load card: Load (EPOC), training effect on its 0 to 5 scales, time to recover, the heart rate drop
     * and VO2 max, each explained plainly with this workout's value and left out when it has none. [fromPhone] says who
     * worked them out; [strap] is the strap's own record of a phone workout, when it kept one.
     */
    fun effectSheet(
        aerobic: Float?, anaerobic: Float?, load: Int?, usualLoad: Float?, recoveryMinutes: Int?,
        drop60: Int?, drop120: Int?, endBpm: Int?, meaningfulDrop: Boolean, vo2max: Float?,
        fromPhone: Boolean, strap: EffectFigures?,
    ): EffectSheet {
        val rows = listOfNotNull(
            load?.let { l ->
                val vs = usualLoad?.let { " Your usual is ${Math.round(it)}." } ?: ""
                "Load" to "Load on a workout is EPOC: the extra oxygen your body uses afterwards to recover, in ml per kg of " +
                    "body weight. The harder and longer the workout, the higher it goes. It isn't the day's Load, which " +
                    "counts every minute on another scale. This workout: $l.$vs"
            },
            if (aerobic != null || anaerobic != null) {
                "Training effect" to "Two scales from 0 to 5. Aerobic is what the workout did for your endurance, anaerobic " +
                    "for short, hard efforts. Under 2 trains little, 2 to 3 keeps what you have, 3 to 4 builds on it, 4 to 5 " +
                    "builds a lot, and 5 is more than your body can use right away. This workout: " +
                    listOfNotNull(aerobic?.let { "aerobic ${one(it)}" }, anaerobic?.let { "anaerobic ${one(it)}" }).joinToString(", ") + "."
            } else null,
            SummaryCopy.recoverSpan(recoveryMinutes)?.let {
                "Time to recover" to "How long until your body is ready for another hard workout, from the training effect " +
                    "and how tired you've been lately. This workout: $it."
            },
            drop60?.let { d ->
                val value = if (meaningfulDrop) {
                    "This workout: down $d in the first minute" + (drop120?.let { ", $it in two" } ?: "") + "."
                } else {
                    "You finished at ${endBpm ?: d} bpm, close to resting, so there was little to come down from and it isn't rated."
                }
                "Heart rate drop" to "How far your heart rate falls in the first minute after you stop. A bigger drop " +
                    "usually means a fitter heart, and under 12 bpm is on the low side. It only says something after a " +
                    "hard effort. $value"
            },
            vo2max?.let { "VO2 max" to "The most oxygen your body can use, in ml per kg per minute, estimated from this workout: ${Math.round(it)}." },
            "Where these come from" to (
                if (fromPhone) "Worked out by OpenStrap from your heart rate, on the strap's own scales." else "From the strap, worked out during the workout."
                ) + (strap?.let { s ->
                val figures = listOfNotNull(
                    s.aerobic?.let { "aerobic ${one(it)}" }, s.anaerobic?.let { "anaerobic ${one(it)}" }, s.load?.let { "Load $it" },
                    SummaryCopy.recoverSpan(s.recoveryMinutes)?.let { "time to recover $it" },
                )
                if (figures.isEmpty()) "" else " The strap recorded this workout too. Its own figures: ${figures.joinToString(", ")}."
            } ?: ""),
        )
        val info = Info(
            name = "Load and training effect",
            meaning = "What this workout asked of your body, and what it trained.",
            scale = if (aerobic != null) EFFECT_SCALE else emptyList(),
            how = "", moves = "", off = "",
        )
        return EffectSheet(
            info, aerobic?.let(::effectBand),
            aerobic?.let { "You are here. Aerobic ${one(it)} on the 0 to 5 scale." },
            rows,
        )
    }
}
