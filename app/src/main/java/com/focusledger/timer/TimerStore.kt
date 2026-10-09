package com.focusledger.timer

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.util.Calendar

/**
 * Timer state, keyed entirely by LABEL rather than by screen position — rows
 * reorder freely (manual, alphabetical, by goal, by remaining) with no risk of
 * time landing on the wrong timer.
 *
 * Model B: the on-screen counters are DAY totals, zeroed at midnight. A
 * parallel set of SESSION totals backs the Day/Session toggle. Neither affects
 * the CSV log, which is the permanent record.
 *
 * Tapping a running timer does nothing; only Stop / Done for now stops it.
 */
object TimerStore {

    /** Sentinel meaning "nothing is running". */
    const val NONE = ""

    private const val PREFS = "focus_ledger_prefs"
    private const val KEY_ACTIVE_LABEL = "active_label"
    private const val KEY_RUN_START_TS = "run_start_ts"
    private const val KEY_SESSION_START_TS = "session_start_ts"
    private const val KEY_REMINDER_BASE_TS = "reminder_base_ts"
    private const val KEY_COUNTER_DAY = "counter_day_stamp"
    private const val KEY_PENDING_ADJ = "pending_adj_minutes"
    private const val KEY_PENDING_NOTE = "pending_note"
    private const val KEY_DAY_MAP = "day_accum_map"
    private const val KEY_SESSION_MAP = "session_accum_map"

    private fun p(c: Context): SharedPreferences =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- label-keyed accumulator maps -------------------------------------

    private fun readMap(c: Context, key: String): JSONObject =
        try { JSONObject(p(c).getString(key, "{}") ?: "{}") } catch (e: Exception) { JSONObject() }

    private fun writeMap(c: Context, key: String, obj: JSONObject) {
        p(c).edit().putString(key, obj.toString()).apply()
    }

    private fun getMapValue(c: Context, key: String, label: String): Long =
        readMap(c, key).optLong(label, 0L)

    private fun addMapValue(c: Context, key: String, label: String, delta: Long) {
        val obj = readMap(c, key)
        obj.put(label, obj.optLong(label, 0L) + delta)   // no flooring: negatives allowed
        writeMap(c, key, obj)
    }

    // ---- active timer ------------------------------------------------------

    /** Label of the running timer, or NONE if idle. */
    fun getActiveLabel(c: Context): String = p(c).getString(KEY_ACTIVE_LABEL, NONE) ?: NONE

    fun isRunning(c: Context): Boolean = getActiveLabel(c) != NONE

    fun getRunStartMs(c: Context): Long = p(c).getLong(KEY_RUN_START_TS, 0L)

    // ---- sessions ----------------------------------------------------------

    fun getSessionStartMs(c: Context): Long {
        val v = p(c).getLong(KEY_SESSION_START_TS, 0L)
        return if (v == 0L) System.currentTimeMillis() else v
    }

    fun hasOpenSession(c: Context): Boolean =
        p(c).getLong(KEY_SESSION_START_TS, 0L) != 0L

    fun beginNewSession(c: Context) {
        p(c).edit()
            .putLong(KEY_SESSION_START_TS, System.currentTimeMillis())
            .putString(KEY_SESSION_MAP, "{}")
            .putInt(KEY_PENDING_ADJ, 0)
            .putString(KEY_PENDING_NOTE, "")
            .apply()
    }

    fun endSession(c: Context) {
        p(c).edit().putLong(KEY_SESSION_START_TS, 0L).apply()
    }

    // ---- day rollover ------------------------------------------------------

    private fun dayStamp(ms: Long = System.currentTimeMillis()): Int {
        val cal = Calendar.getInstance().apply { timeInMillis = ms }
        return cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
    }

    /**
     * Brings the counters up to date with the calendar. Safe to call often;
     * it does nothing unless a midnight has passed.
     *
     * Two things, in this order:
     *
     *  1. **A running timer is cut at every midnight it has crossed**, judged
     *     from the run's own start time. Each finished day becomes its own log
     *     row and the run carries on from the latest midnight. This used to
     *     happen only in the background service, which kept the date in
     *     memory \u2014 so when the phone restarted the service overnight, as
     *     Samsung phones do to save battery, midnight went unnoticed and a
     *     whole night's run was counted against the next day.
     *
     *  2. **The day counters are zeroed** if the saved date isn't today.
     *
     * The cut banks only into the session total, never the day counters: by
     * the time it runs the day counters may already belong to today, and
     * yesterday's hours must not land there.
     */
    fun rolloverDayCountersIfNeeded(c: Context) {
        catchUpMidnights(c)

        val today = dayStamp()
        if (!p(c).contains(KEY_COUNTER_DAY)) {
            p(c).edit().putInt(KEY_COUNTER_DAY, today).apply()
            return
        }
        if (p(c).getInt(KEY_COUNTER_DAY, today) != today) {
            p(c).edit()
                .putString(KEY_DAY_MAP, "{}")
                .putInt(KEY_COUNTER_DAY, today)
                .apply()
        }
    }

    /** Cuts a running timer at each midnight between its start and now. */
    private fun catchUpMidnights(c: Context) {
        val label = getActiveLabel(c)
        if (label == NONE) return
        var runStart = p(c).getLong(KEY_RUN_START_TS, 0L)
        if (runStart <= 0L) return
        val todayMidnight = midnightOf(System.currentTimeMillis())
        if (runStart >= todayMidnight) return

        // A run left going for several days is cut at each midnight, so every
        // day it covered gets its own row. Capped as a guard against a
        // corrupt start time producing thousands of rows.
        var boundary = midnightOf(runStart) + DAY_MS
        var cuts = 0
        while (boundary <= todayMidnight && cuts < 60) {
            addMapValue(c, KEY_SESSION_MAP, label, boundary - runStart)
            LogStore.logRun(
                c, getSessionStartMs(c), runStart, boundary, label,
                takePendingAdjustment(c), takePendingNote(c)
            )
            runStart = boundary
            boundary += DAY_MS
            cuts++
        }
        p(c).edit()
            .putLong(KEY_RUN_START_TS, runStart)
            .putLong(KEY_REMINDER_BASE_TS, maxOf(runStart, p(c).getLong(KEY_REMINDER_BASE_TS, runStart)))
            .apply()
    }

    private fun midnightOf(ms: Long): Long = Calendar.getInstance().apply {
        timeInMillis = ms
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private const val DAY_MS = 86_400_000L

    // ---- reminders ---------------------------------------------------------

    fun msSinceReminderBase(c: Context): Long {
        val base = p(c).getLong(KEY_REMINDER_BASE_TS, 0L)
        if (base == 0L) return 0L
        return (System.currentTimeMillis() - base).coerceAtLeast(0L)
    }

    fun resetReminderBase(c: Context) {
        p(c).edit().putLong(KEY_REMINDER_BASE_TS, System.currentTimeMillis()).apply()
    }

    // ---- start / stop ------------------------------------------------------

    private fun bank(c: Context, label: String, ms: Long) {
        addMapValue(c, KEY_DAY_MAP, label, ms)
        addMapValue(c, KEY_SESSION_MAP, label, ms)
    }

    /** Starts [label]. Starting the already-running label is a no-op. */
    fun start(c: Context, label: String) {
        rolloverDayCountersIfNeeded(c)
        // Settle against the outgoing label before the active one changes.
        TaskStore.settleDoing(c)
        if (getActiveLabel(c) == label) return
        if (isRunning(c)) stopInternal(c)

        val now = System.currentTimeMillis()
        SettingsStore.setLastLabel(c, label)
        p(c).edit()
            .putString(KEY_PENDING_NOTE, "")
            .putString(KEY_ACTIVE_LABEL, label)
            .putLong(KEY_RUN_START_TS, now)
            .putLong(KEY_REMINDER_BASE_TS, now)
            .apply()

        // If this label already has a task marked doing, it starts accruing
        // now rather than waiting to be re-marked.
        TaskStore.onLabelStarted(c, label)
    }

    /** Stops the running timer, banking its time and writing a log row. */
    fun stop(c: Context) {
        if (!isRunning(c)) return
        rolloverDayCountersIfNeeded(c)
        // Fold any task time accrued during this run before the label stops,
        // or it would be counted against a timer that is no longer going.
        TaskStore.settleDoing(c)
        stopInternal(c)
        p(c).edit()
            .putString(KEY_ACTIVE_LABEL, NONE)
            .remove(KEY_RUN_START_TS)
            .remove(KEY_REMINDER_BASE_TS)
            .apply()
    }

    private fun stopInternal(c: Context) {
        val label = getActiveLabel(c)
        if (label == NONE) return
        val now = System.currentTimeMillis()
        val runStart = p(c).getLong(KEY_RUN_START_TS, now)
        val elapsed = (now - runStart).coerceAtLeast(0L)

        bank(c, label, elapsed)
        LogStore.logRun(
            c, getSessionStartMs(c), runStart, now, label,
            takePendingAdjustment(c), takePendingNote(c)
        )
    }

    /**
     * Kept for the service's call. Everything it did is now in
     * [rolloverDayCountersIfNeeded], which judges midnight from the run's own
     * start time rather than from a date held in memory.
     */
    fun splitAtMidnight(c: Context, @Suppress("UNUSED_PARAMETER") boundaryMs: Long) =
        rolloverDayCountersIfNeeded(c)

    // ---- adjustments -------------------------------------------------------

    /**
     * Note being written against the run in progress. The run has no CSV row
     * until it stops, so it's held here and written with the row. Saved on
     * every edit, so an interruption doesn't lose it.
     */
    fun getPendingNote(c: Context): String = p(c).getString(KEY_PENDING_NOTE, "") ?: ""

    fun setPendingNote(c: Context, note: String) {
        p(c).edit().putString(KEY_PENDING_NOTE, note).apply()
    }

    private fun takePendingNote(c: Context): String {
        val v = getPendingNote(c)
        if (v.isNotEmpty()) p(c).edit().putString(KEY_PENDING_NOTE, "").apply()
        return v
    }

    private fun takePendingAdjustment(c: Context): Int {
        val v = p(c).getInt(KEY_PENDING_ADJ, 0)
        if (v != 0) p(c).edit().putInt(KEY_PENDING_ADJ, 0).apply()
        return v
    }

    /**
     * Adjusts [label] by [deltaMinutes]. Negatives may take a total below zero
     * so an overstated timer can be offset elsewhere.
     *
     * Adjusting the running timer accumulates into that run's log row; any
     * other timer gets its own row, since there's no run to attach to.
     */
    fun adjust(c: Context, label: String, deltaMinutes: Int) {
        if (deltaMinutes == 0) return
        val deltaMs = deltaMinutes * 60_000L

        addMapValue(c, KEY_DAY_MAP, label, deltaMs)
        addMapValue(c, KEY_SESSION_MAP, label, deltaMs)

        if (getActiveLabel(c) == label) {
            p(c).edit()
                .putInt(KEY_PENDING_ADJ, p(c).getInt(KEY_PENDING_ADJ, 0) + deltaMinutes)
                .apply()
        } else {
            LogStore.logAdjustment(c, getSessionStartMs(c), label, deltaMinutes)
        }
    }

    /**
     * Removes [ms] from today's counters without writing a log row.
     *
     * Used when a logged run is deleted: the row is already going away, so
     * recording an adjustment beside it would be noise. Distinct from
     * [adjust], which is a deliberate correction and should leave a trace.
     */
    fun subtractSilently(c: Context, label: String, ms: Long) {
        if (ms == 0L) return
        addMapValue(c, KEY_DAY_MAP, label, -ms)
        addMapValue(c, KEY_SESSION_MAP, label, -ms)
    }

    // ---- totals ------------------------------------------------------------

    fun getDayMs(c: Context, label: String): Long = totalFor(c, KEY_DAY_MAP, label)

    fun getSessionMs(c: Context, label: String): Long = totalFor(c, KEY_SESSION_MAP, label)

    private fun totalFor(c: Context, mapKey: String, label: String): Long {
        if (mapKey == KEY_DAY_MAP) rolloverDayCountersIfNeeded(c)
        var total = getMapValue(c, mapKey, label)
        if (getActiveLabel(c) == label) {
            val runStart = p(c).getLong(KEY_RUN_START_TS, System.currentTimeMillis())
            total += (System.currentTimeMillis() - runStart).coerceAtLeast(0L)
        }
        return total   // may be negative after adjustments
    }

    /** ms still to go against [goalMinutes]; negative once the goal is passed. */
    fun getRemainingMs(c: Context, label: String, goalMinutes: Int): Long =
        goalMinutes * 60_000L - getDayMs(c, label)

    fun formatDuration(ms: Long): String {
        val neg = ms < 0
        val totalSec = Math.abs(ms) / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        val body = if (h > 0) String.format("%d:%02d:%02d", h, m, s)
                   else String.format("%02d:%02d", m, s)
        return if (neg) "-$body" else body
    }
}
