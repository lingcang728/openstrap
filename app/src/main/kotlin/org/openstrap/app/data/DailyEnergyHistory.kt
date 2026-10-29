package org.openstrap.app.data

import kotlinx.coroutines.flow.first
import org.openstrap.app.data.db.DailyEnergyRow
import org.openstrap.app.data.db.HealthDao
import org.openstrap.app.data.settings.UserProfile
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Keeps [DailyEnergyRow] for every day with data, so week, month and year views read a row rather than run the model.
 * The day shown in the app writes its own row each time [RecoveryEngine.compute] works it out ([HealthRepository]);
 * this fills the rest after a sync: days without a row (the first run backfills them all), the last [RECENT_DAYS] (a
 * sync can still bring their minutes or a workout), and every day when the profile has changed. Phone workouts from
 * before version 20 get their kcal first, so their days count it.
 */
class DailyEnergyHistory(private val dao: HealthDao) {

    /** The days written. */
    suspend fun update(
        profile: UserProfile, profileChanged: Boolean, zone: ZoneId = ZoneId.systemDefault(), now: Long = System.currentTimeMillis() / 1000,
    ): Int {
        if (EnergyExpenditure.bmrPerMinute(profile, profile.age()) == null) return 0
        val effects = WorkoutEffectEngine(dao)
        for (w in dao.appWorkoutsAll()) if (w.end != null && w.totalKcal == null) runCatching { effects.backfillKcal(w, profile, zone) }

        val today = Instant.ofEpochSecond(now).atZone(zone).toLocalDate()
        val have = dao.dailyEnergyDaysOnce().toHashSet()
        val days = dao.hoursWithData().first().mapTo(sortedSetOf()) { Instant.ofEpochSecond(it * 3600).atZone(zone).toLocalDate() }
            .filter { d -> d <= today && (profileChanged || d > today.minusDays(RECENT_DAYS.toLong()) || DayWindow.of(d, zone).from !in have) }
        if (days.isEmpty()) return 0

        // Inputs the whole model would take a day to give for each past day: resting HR as the workouts take it, max HR
        // as of today, VO2max once a week. They only move the HR-assisted minutes; opening a day rewrites its row exactly.
        val engine = RecoveryEngine(dao)
        val maxHr = engine.maxHeartRate(profile, today, zone)
        val vo2 = HashMap<LocalDate, Float?>()
        var written = 0
        for (d in days) {
            val from = DayWindow.of(d, zone).from
            val restingHr = Recovery.daytimeRestingHr(
                dao.restingHrOnce(from - Recovery.RHR_DAYS * 86_400L, from).associate { it.day to it.bpm.toFloat() }, emptyMap(), from,
            ) ?: DEFAULT_RESTING_HR
            val monday = d.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
            val v = vo2.getOrPut(monday) { runCatching { engine.vo2max(profile, d, zone) }.getOrNull() }
            val e = runCatching { engine.energy(d, profile, restingHr, maxHr, v, zone, now) }.getOrNull() ?: continue
            dao.insertDailyEnergy(listOf(row(from, e, now)))
            written++
        }
        return written
    }

    companion object {
        /** Days back from today rewritten after every sync: the strap's history reaches about a week. */
        const val RECENT_DAYS = 7
        private const val DEFAULT_RESTING_HR = 60f

        /** [e] as the row for the day starting at [day]. */
        fun row(day: Long, e: EnergyExpenditure.Day, now: Long) = DailyEnergyRow(
            day = day,
            updatedAt = now,
            totalKcal = e.totalKcal,
            activeKcal = e.activeKcal,
            bmrKcal = e.bmrKcal,
            minutesWithData = e.minutesWithData,
            partial = e.partial,
            workoutKcal = e.workoutKcal,
            walkingKcal = e.walkingKcal,
            hourly = e.hourly.total.joinToString(",") { "%.1f".format(java.util.Locale.ROOT, it) },
        )

        /**
         * What in the profile moves a day's energy: weight, height, age, sex and a tested max HR. When it differs from
         * the one the rows were written with, every row is written again.
         */
        fun profileKey(p: UserProfile): Long = listOf(p.weightKg, p.heightCm, p.age(), p.sex, p.maxHeartRate).hashCode().toLong()
    }
}
