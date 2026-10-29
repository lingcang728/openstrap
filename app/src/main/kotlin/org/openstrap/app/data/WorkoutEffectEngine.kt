package org.openstrap.app.data

import org.openstrap.app.data.db.AppWorkout
import org.openstrap.app.data.db.AppWorkoutHeartRate
import org.openstrap.app.data.db.HealthDao
import org.openstrap.app.data.settings.UserProfile
import java.time.Instant
import java.time.ZoneId

/**
 * Loads what [WorkoutEffect] needs for one phone workout and runs it, as [RecoveryEngine] does for [Recovery]: resting
 * and max HR by the app's own rules, the earlier workouts' EPOC (the phone's and the strap's, each workout once) for the
 * activity class, fitness and fatigue, and recovery time still owed.
 */
class WorkoutEffectEngine(private val dao: HealthDao) {

    /**
     * [w] with its effect figures from [hr] over its shown range. [route] is the route in memory at the end; otherwise
     * a kept route is read back, and without either a VO2max worked out at the end is kept as it was.
     */
    suspend fun withEffect(
        w: AppWorkout, hr: List<AppWorkoutHeartRate>, profile: UserProfile, route: List<Route.Point>? = null,
        zone: ZoneId = ZoneId.systemDefault(),
    ): AppWorkout {
        val end = w.end ?: return w
        val from = w.from
        val engine = RecoveryEngine(dao)
        val date = Instant.ofEpochSecond(from).atZone(zone).toLocalDate()
        val maxHr = engine.maxHeartRate(profile, date, zone)
        val restingHr = restingHr(from, zone)

        val loads = earlierLoads(w, zone)
        val load28 = loads.filter { it.first >= from - 28 * DAY }.sumOf { it.second.toDouble() }.toFloat()
        val today = dayStart(from, zone)
        val daily = (TREND_DAYS - 1 downTo 0).map { back ->
            val d = today - back * DAY
            loads.filter { dayStart(it.first, zone) == d }.sumOf { it.second.toDouble() }.toFloat()
        }
        val trend = Recovery.fitnessFatigue(daily).last()

        val points = route ?: if (w.keepRoute) dao.routeOnce(w.start).map { it.point } else null
        val shownHr = WorkoutRecorder.expand(WorkoutRecorder.shown(w, hr, end)).map { it.timestamp to it.bpm }
        val r = WorkoutEffect.compute(
            w.sportType, shownHr, from, end, w.pauseRanges, points?.filter { it.t in from..end }.orEmpty(),
            restingHr, maxHr, load28, trend, carriedMinutes(w),
        )
        // kcal from scratch over the range as it now stands, so the saved total isn't fitted to the old one.
        val kcal = runCatching { engine.phoneWorkoutKcal(w.copy(totalKcal = null), profile, profile.age(date), restingHr, maxHr, engine.vo2max(profile, date, zone), end, hr) }
            .getOrNull()
        return WorkoutRecorder.withKcal(w, kcal).copy(
            epoc = r.epoc, aerobicTrainingEffect = r.aerobicTe, recoveryMinutes = r.recoveryMinutes,
            vo2max = if (points == null) w.vo2max else r.vo2max,
        )
    }

    /**
     * Works out and saves the kcal of a finished workout that has none yet (one from before version 20), without
     * touching its effect. Nothing is written without a profile.
     */
    suspend fun backfillKcal(w: AppWorkout, profile: UserProfile, zone: ZoneId = ZoneId.systemDefault()) {
        val end = w.end ?: return
        if (w.totalKcal != null) return
        val engine = RecoveryEngine(dao)
        val date = Instant.ofEpochSecond(w.from).atZone(zone).toLocalDate()
        val minutes = engine.phoneWorkoutKcal(
            w, profile, profile.age(date), restingHr(w.from, zone), engine.maxHeartRate(profile, date, zone), engine.vo2max(profile, date, zone), end,
        ) ?: return
        val kcal = WorkoutRecorder.kcal(minutes)
        dao.updateAppWorkoutKcal(w.start, kcal.active, kcal.total)
    }

    /** Daytime resting HR at [t]: the strap's daily values over [Recovery.RHR_DAYS], else [DEFAULT_RESTING_HR]. */
    private suspend fun restingHr(t: Long, zone: ZoneId): Float = Recovery.daytimeRestingHr(
        dao.restingHrOnce(t - Recovery.RHR_DAYS * DAY, t).associate { it.day to it.bpm.toFloat() }, emptyMap(), dayStart(t, zone),
    ) ?: DEFAULT_RESTING_HR

    /** Works out and saves the figures of a finished workout that has none yet (one from before version 14). */
    suspend fun backfill(w: AppWorkout, profile: UserProfile) {
        if (w.end == null || w.epoc != null) return
        val hr = dao.appWorkoutHeartRateOnce(w.start)
        if (hr.isEmpty()) return
        val e = withEffect(w, hr, profile)
        // Nothing to save without a valid reading; writing anyway would only wake every reader of the table.
        if (e.epoc == null) return
        dao.updateAppWorkoutEffect(w.start, e.epoc, e.aerobicTrainingEffect, e.recoveryMinutes, e.vo2max)
    }

    /**
     * (start, EPOC) of every workout in the [TREND_DAYS] before [w]: the phone's own, and the strap's load (Zepp saves
     * int EPOC as it, native 04 §2.1) unless a phone workout covers the same time, so nothing counts twice.
     */
    private suspend fun earlierLoads(w: AppWorkout, zone: ZoneId): List<Pair<Long, Float>> {
        val since = dayStart(w.from, zone) - (TREND_DAYS - 1) * DAY
        val phone = dao.appWorkoutsOnce(since, w.from).filter { it.start != w.start && it.end != null && it.from < w.from }
        val strap = dao.workoutsOnce(since, w.from).filter { s ->
            s.start < w.from && !(s.start < w.end!! && s.end > w.from) && phone.none { p -> s.start < p.end!! && s.end > p.from }
        }
        return phone.mapNotNull { p -> p.epoc?.let { p.from to it } } + strap.mapNotNull { s -> s.trainingLoad?.let { s.start to it.toFloat() } }
    }

    /** Recovery still owed at [w]'s start from the latest earlier workouts, phone or strap, in minutes. */
    private suspend fun carriedMinutes(w: AppWorkout): Int {
        val since = w.from - (WorkoutEffect.MAX_RECOVERY_H * 3600).toLong()
        val phone = dao.appWorkoutsOnce(since, w.from).filter { it.start != w.start && it.end != null && it.end <= w.from }
            .mapNotNull { p -> p.recoveryMinutes?.let { p.end!! to it } }
        val strap = dao.sportEffectsOnce(since, w.from).mapNotNull { e -> e.recoveryMinutes?.let { e.end to it } }
        return (phone + strap).maxOfOrNull { (end, minutes) -> minutes - ((w.from - end) / 60).toInt() }?.coerceAtLeast(0) ?: 0
    }

    private fun dayStart(t: Long, zone: ZoneId) = Instant.ofEpochSecond(t).atZone(zone).toLocalDate().atStartOfDay(zone).toEpochSecond()

    private companion object {
        const val DAY = 86_400L
        /** Fitness takes 42 days to build (Recovery.CTL_DAYS); the activity class uses the last 28 of them. */
        const val TREND_DAYS = Recovery.CTL_DAYS
        const val DEFAULT_RESTING_HR = 60f
    }
}
