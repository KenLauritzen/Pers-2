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

    fun rolloverDayCountersIfNeeded(c: Context) {
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
    }

    /** Stops the running timer, banking its time and writing a log row. */
    fun stop(c: Context) {
        if (!isRunning(c)) return
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
     * Splits an in-flight run at midnight: logs the finished portion, zeroes
     * the day counters, and restarts the run and reminder from midnight.
     */
    fun splitAtMidnight(c: Context, boundaryMs: Long) {
        val label = getActiveLabel(c)
        if (label != NONE) {
            val runStart = p(c).getLong(KEY_RUN_START_TS, boundaryMs)
            if (runStart < boundaryMs) {
                bank(c, label, (boundaryMs - runStart).coerceAtLeast(0L))
                LogStore.logRun(
                    c, getSessionStartMs(c), runStart, boundaryMs, label,
                    takePendingAdjustment(c), takePendingNote(c)
                )
                p(c).edit()
                    .putLong(KEY_RUN_START_TS, boundaryMs)
                    .putLong(KEY_REMINDER_BASE_TS, boundaryMs)
                    .apply()
            }
        }
        rolloverDayCountersIfNeeded(c)
    }

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

    // ---- totals ------------------------------------------------------------

    fun getDayMs(c: Context, label: String): Long = totalFor(c, KEY_DAY_MAP, label)

    fun getSessionMs(c: Context, label: String): Long = totalFor(c, KEY_SESSION_MAP, label)

    private fun totalFor(c: Context, mapKey: String, label: String): Long {
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
