package com.focusledger.timer

import android.content.Context

/** All user-configurable settings, backed by SharedPreferences. */
object SettingsStore {

    private const val PREFS = "focus_ledger_settings"

    private const val KEY_REMINDER_MINUTES = "reminder_minutes"
    private const val KEY_REMINDER_HEADS_UP = "reminder_heads_up"
    private const val KEY_REMINDER_SOUND = "reminder_sound"
    private const val KEY_REMINDER_TIMEOUT_SEC = "reminder_timeout_sec"
    private const val KEY_PROMPT_NOTE = "prompt_note"
    private const val KEY_VIEW_SESSION = "view_session"
    private const val KEY_SORT_MODE = "sort_mode"
    private const val KEY_LAST_LABEL = "last_label"
    private const val KEY_SHOW_EMPTY_RUNS = "show_empty_runs"
    private const val KEY_CUMULATIVE = "column_mode"
    private const val KEY_SECONDARY = "column_mode_secondary"
    private const val KEY_TASK_PILL = "task_pill_rows"
    private const val KEY_START_HOUR = "day_start_hour"
    private const val KEY_START_MINUTE = "day_start_minute"

    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 0 = reminders off. */
    fun getReminderMinutes(c: Context) = p(c).getInt(KEY_REMINDER_MINUTES, 120)
    fun setReminderMinutes(c: Context, v: Int) =
        p(c).edit().putInt(KEY_REMINDER_MINUTES, v.coerceAtLeast(0)).apply()

    fun isHeadsUp(c: Context) = p(c).getBoolean(KEY_REMINDER_HEADS_UP, true)
    fun setHeadsUp(c: Context, v: Boolean) =
        p(c).edit().putBoolean(KEY_REMINDER_HEADS_UP, v).apply()

    fun isSoundOn(c: Context) = p(c).getBoolean(KEY_REMINDER_SOUND, false)
    fun setSoundOn(c: Context, v: Boolean) =
        p(c).edit().putBoolean(KEY_REMINDER_SOUND, v).apply()

    /** 0 = never auto-hide. */
    fun getReminderTimeoutSec(c: Context) = p(c).getInt(KEY_REMINDER_TIMEOUT_SEC, 300)
    fun setReminderTimeoutSec(c: Context, v: Int) =
        p(c).edit().putInt(KEY_REMINDER_TIMEOUT_SEC, v.coerceAtLeast(0)).apply()

    fun isPromptNote(c: Context) = p(c).getBoolean(KEY_PROMPT_NOTE, false)
    fun setPromptNote(c: Context, v: Boolean) =
        p(c).edit().putBoolean(KEY_PROMPT_NOTE, v).apply()

    /**
     * What the right-hand column shows. Chosen explicitly and independent of
     * the sort mode — sorting by Remain no longer forces the column to show
     * remaining, so the two can be set to whatever combination is useful.
     */
    const val COL_GOAL = 0
    const val COL_REMAIN = 1
    const val COL_SUM_GOAL = 2
    const val COL_SUM_REMAIN = 3
    const val COL_START = 4
    const val COL_ETA = 5

    /** Full names for the picker. */
    val COLUMN_NAMES = arrayOf(
        "Goal \u2014 each label's goal",
        "Rem \u2014 each label's time remaining",
        "SGoal \u2014 goals summed down the rows",
        "SRem \u2014 remaining summed down the rows",
        "Start \u2014 when each label would start, from your start time",
        "ETA \u2014 when you'd reach each label, counting from now"
    )

    /** Short names for the header button. */
    val COLUMN_SHORT = arrayOf("Goal", "Rem", "SGoal", "SRem", "Start", "ETA")

    fun getColumnMode(c: Context) = p(c).getInt(KEY_CUMULATIVE, COL_GOAL)
    fun setColumnMode(c: Context, v: Int) =
        p(c).edit().putInt(KEY_CUMULATIVE, v.coerceIn(0, 5)).apply()

    /** No second figure. */
    const val COL_NONE = -1

    /**
     * A second, smaller figure under the label. Off by default — the row is
     * dense enough that a second number should be asked for.
     */
    fun getSecondaryMode(c: Context) = p(c).getInt(KEY_SECONDARY, COL_NONE)
    fun setSecondaryMode(c: Context, v: Int) =
        p(c).edit().putInt(KEY_SECONDARY, if (v < 0) COL_NONE else v.coerceIn(0, 5)).apply()

    /** Picker entries for the secondary, with "None" first. */
    val SECONDARY_NAMES = arrayOf("None \u2014 no second figure") + COLUMN_NAMES

    /**
     * How many tasks every row shows — 0, 1 or 2.
     *
     * The running row ignores this and uses its own per-label count, which
     * goes to 5: you want detail where you're working and a uniform view for
     * planning, and those aren't the same thing.
     */
    fun getTaskPill(c: Context) = p(c).getInt(KEY_TASK_PILL, 0).coerceIn(0, 2)
    fun setTaskPill(c: Context, v: Int) =
        p(c).edit().putInt(KEY_TASK_PILL, v.coerceIn(0, 2)).apply()

    /**
     * The hour the day is planned from, used by the Start column. Persists
     * across days — most days begin at roughly the same time — and defaults
     * to 6:00.
     */
    fun getStartHour(c: Context) = p(c).getInt(KEY_START_HOUR, 6)
    fun getStartMinute(c: Context) = p(c).getInt(KEY_START_MINUTE, 0)
    fun setStartTime(c: Context, hour: Int, minute: Int) =
        p(c).edit()
            .putInt(KEY_START_HOUR, hour.coerceIn(0, 23))
            .putInt(KEY_START_MINUTE, minute.coerceIn(0, 59))
            .apply()

    /** Notes screen: whether runs with no note are listed. Off by default. */
    fun isShowEmptyRuns(c: Context) = p(c).getBoolean(KEY_SHOW_EMPTY_RUNS, false)
    fun setShowEmptyRuns(c: Context, v: Boolean) =
        p(c).edit().putBoolean(KEY_SHOW_EMPTY_RUNS, v).apply()

    /**
     * Most recently started label. No longer used to preselect anything now
     * that Settings has no label list, but cheap to keep and useful if a
     * "resume last" action ever appears.
     */
    fun getLastLabel(c: Context): String = p(c).getString(KEY_LAST_LABEL, "") ?: ""
    fun setLastLabel(c: Context, v: String) =
        p(c).edit().putString(KEY_LAST_LABEL, v).apply()

    const val SORT_MANUAL = 0
    const val SORT_ALPHA = 1
    const val SORT_GOAL = 2
    const val SORT_REMAINING = 3

    val SORT_NAMES = arrayOf("Manual", "A\u2013Z", "Goal (high to low)", "Remaining (high to low)")

    /** Compact forms for the main screen's header button. */
    val SORT_SHORT = arrayOf("Manual", "A\u2013Z", "Goals", "Remain")

    /** Shared by the main screen and Settings. */
    fun getSortMode(c: Context) = p(c).getInt(KEY_SORT_MODE, SORT_MANUAL)
    fun setSortMode(c: Context, v: Int) =
        p(c).edit().putInt(KEY_SORT_MODE, v.coerceIn(0, 3)).apply()

    /** false = Day view (default), true = Session view. Remembered across launches. */
    fun isSessionView(c: Context) = p(c).getBoolean(KEY_VIEW_SESSION, false)
    fun setSessionView(c: Context, v: Boolean) =
        p(c).edit().putBoolean(KEY_VIEW_SESSION, v).apply()
}
