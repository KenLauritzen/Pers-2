package com.focusledger.timer

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.focusledger.timer.databinding.ActivityWeekBinding
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Seven days side by side, each a stack of blocks you drag into shape.
 *
 * **Vertical drag moves a block; horizontal drag changes its duration.** The
 * axis you start moving in picks the action, so there's no mode to remember
 * mid-gesture. Durations step 5 minutes, matching the main screen's sliders,
 * and stop at zero \u2014 a zeroed block greys out and sinks to the foot of the
 * column rather than being deleted.
 *
 * Start times are computed, never stored, so moving a block moves every start
 * time below it.
 */
class WeekActivity : AppCompatActivity() {

    private lateinit var b: ActivityWeekBinding

    /** Any day inside the week being shown. */
    private var anchorMs = System.currentTimeMillis()

    /** Set when viewing the seven weekday shapes rather than dated days. */
    private var editingDefault = false

    private val dayFmt = SimpleDateFormat("EEE", Locale.getDefault())
    private val weekFmt = SimpleDateFormat("d MMM", Locale.getDefault())
    private val defaultDayNames =
        arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityWeekBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.weekPrev.setOnClickListener { shiftWeek(-7) }
        b.weekNext.setOnClickListener { shiftWeek(7) }
        b.weekTitle.setOnClickListener { pickWeek() }
        b.weekAction.setOnClickListener { weekMenu() }
        b.weekDone.setOnClickListener { finish() }

        render()
    }

    private fun shiftWeek(days: Int) {
        if (editingDefault) return
        anchorMs = Calendar.getInstance().apply {
            timeInMillis = anchorMs
            add(Calendar.DAY_OF_YEAR, days)
        }.timeInMillis
        render()
    }

    private fun pickWeek() {
        if (editingDefault) return
        val c = Calendar.getInstance().apply { timeInMillis = anchorMs }
        DatePickerDialog(
            this,
            { _, y, m, d ->
                anchorMs = Calendar.getInstance().apply { set(y, m, d) }.timeInMillis
                render()
            },
            c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    /** The seven keys on screen: dates, or D0..D6 for the default week. */
    private fun currentKeys(): List<String> =
        if (editingDefault) (0..6).map { "D$it" }
        else WeekStore.weekOf(anchorMs, SettingsStore.getWeekStartDow(this))
            .map { WeekStore.dateKey(it) }

    private fun currentDates(): List<Long> =
        WeekStore.weekOf(anchorMs, SettingsStore.getWeekStartDow(this))

    // ---- drawing ------------------------------------------------------------

    private fun render() {
        b.weekTitle.text = if (editingDefault) "Default week" else {
            val dates = currentDates()
            "${weekFmt.format(Date(dates.first()))} \u2013 ${weekFmt.format(Date(dates.last()))}"
        }
        b.weekPrev.alpha = if (editingDefault) 0.3f else 1f
        b.weekNext.alpha = if (editingDefault) 0.3f else 1f

        b.weekColumns.removeAllViews()
        val inflater = LayoutInflater.from(this)

        currentKeys().forEachIndexed { i, key ->
            val col = inflater.inflate(R.layout.column_week_day, b.weekColumns, false)
            val header = col.findViewById<TextView>(R.id.colHeader)
            val list = col.findViewById<LinearLayout>(R.id.colBlocks)
            val add = col.findViewById<TextView>(R.id.colAdd)

            val dateMs = if (editingDefault) null else currentDates()[i]
            drawHeader(header, key, dateMs, i)

            // A day that differs from its default is worth seeing at a glance
            // while planning.
            if (dateMs != null && WeekStore.differsFromDefault(this, dateMs)) {
                col.setBackgroundResource(R.drawable.bg_row_idle)
            }

            drawBlocks(list, key, dateMs)

            header.setOnClickListener { dayMenu(key, dateMs) }
            add.setOnClickListener { addBlock(key) }
            b.weekColumns.addView(col)
        }
    }

    private fun drawHeader(view: TextView, key: String, dateMs: Long?, index: Int) {
        val day = WeekStore.day(this, key)
        val planned = WeekStore.plannedMinutes(this, key)
        val name =
            if (editingDefault) defaultDayNames[index] else dayFmt.format(Date(dateMs!!))

        // Once a day has passed the start time has done its job, and the
        // comparison takes its place: actual over planned, as the tasks read.
        val past = dateMs != null && dateMs < startOfToday()
        view.text = if (past) {
            val actual = actualMinutesFor(dateMs!!)
            "$name ${hm(actual)} / ${hm(planned)}"
        } else {
            "$name ${clock(day.startMinutes)} > ${hm(planned)}h"
        }
        view.setTextColor(
            when {
                past -> 0xFFA9BDB8.toInt()
                dateMs != null && WeekStore.dateKey(dateMs) == WeekStore.dateKey(
                    System.currentTimeMillis()
                ) -> 0xFFE8A33D.toInt()
                else -> 0xFFCBD9D5.toInt()
            }
        )
    }

    private fun drawBlocks(list: LinearLayout, key: String, dateMs: Long?) {
        list.removeAllViews()
        val day = WeekStore.day(this, key)
        val blocks = WeekStore.blocksFor(this, key)
        val starts = WeekStore.startTimes(day, blocks)
        val inflater = LayoutInflater.from(this)
        val locked = dateMs != null && dateMs < startOfToday()

        blocks.forEach { block ->
            val row = inflater.inflate(R.layout.row_week_block, list, false) as TextView
            if (block.isParked) {
                row.text = "  \u2014   ${block.label}"
                row.setTextColor(0xFF5C736E.toInt())
            } else {
                row.text =
                    "${clock(starts[block.id] ?: day.startMinutes)} ${block.minutes}m ${block.label}"
                row.setTextColor(0xFFDCE6E3.toInt())
            }

            if (!locked) {
                row.setOnClickListener { blockMenu(block, key) }
                attachDrag(row, block, key, list)
            } else {
                row.alpha = 0.7f
            }
            list.addView(row)
        }
    }

    // ---- the two drag axes --------------------------------------------------

    /**
     * One touch listener, two actions. Whichever axis the finger moves in
     * first locks the gesture, so nothing is decided by a mode you can't see
     * while dragging.
     */
    private fun attachDrag(row: TextView, block: Block, key: String, list: LinearLayout) {
        val d = resources.displayMetrics.density
        val slop = 8 * d
        val stepPx = 18 * d          // one 5-minute step of horizontal travel
        val rowPx = 20 * d

        var startX = 0f
        var startY = 0f
        var axis = 0                 // 0 undecided, 1 horizontal, 2 vertical
        var pendingMinutes = block.minutes
        var pendingIndex = -1

        row.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = ev.rawX; startY = ev.rawY
                    axis = 0
                    pendingMinutes = block.minutes
                    pendingIndex = -1
                    false                       // let a tap still reach the click listener
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - startX
                    val dy = ev.rawY - startY
                    if (axis == 0) {
                        if (abs(dx) < slop && abs(dy) < slop) return@setOnTouchListener false
                        axis = if (abs(dx) > abs(dy)) 1 else 2
                        // Hold the column still for the rest of the gesture.
                        row.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (axis == 1) {
                        // Five-minute steps, stopping at zero: a block reaching
                        // zero parks rather than going negative.
                        val steps = (dx / stepPx).toInt()
                        pendingMinutes = (block.minutes + steps * 5).coerceAtLeast(0)
                        row.text = if (pendingMinutes <= 0) "  \u2014   ${block.label}"
                                   else "  ${pendingMinutes}m ${block.label}"
                        row.setTextColor(0xFFE8A33D.toInt())
                    } else {
                        val live = WeekStore.blocksFor(this, key).filterNot { it.isParked }
                        val from = live.indexOfFirst { it.id == block.id }
                        if (from >= 0) {
                            pendingIndex = (from + (dy / rowPx).toInt())
                                .coerceIn(0, (live.size - 1).coerceAtLeast(0))
                            row.setTextColor(0xFFE8A33D.toInt())
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    row.parent?.requestDisallowInterceptTouchEvent(false)
                    when {
                        axis == 1 && pendingMinutes != block.minutes -> {
                            WeekStore.setMinutes(this, block.id, pendingMinutes)
                            render()
                        }
                        axis == 2 && pendingIndex >= 0 -> {
                            val live = WeekStore.blocksFor(this, key)
                                .filterNot { it.isParked }.map { it.id }.toMutableList()
                            val from = live.indexOf(block.id)
                            if (from >= 0 && from != pendingIndex) {
                                live.add(pendingIndex, live.removeAt(from))
                                val parked = WeekStore.blocksFor(this, key)
                                    .filter { it.isParked }.map { it.id }
                                WeekStore.reorder(this, key, live + parked)
                                render()
                            } else render()
                        }
                        axis != 0 -> render()
                    }
                    axis != 0                   // consume only if a drag happened
                }
                else -> false
            }
        }
    }

    // ---- menus --------------------------------------------------------------

    private fun weekMenu() {
        val items = if (editingDefault) listOf(
            "Fill all days from a layout\u2026",
            "Fill all days from current goals",
            "Back to the week"
        ) else listOf(
            "Pull in the default week",
            "Fill all days from a layout\u2026",
            "Edit the default week",
            "Delete this week"
        )

        AlertDialog.Builder(this)
            .setTitle(if (editingDefault) "Default week" else "This week")
            .setItems(items.toTypedArray()) { _, which ->
                when (items[which]) {
                    "Fill all days from a layout\u2026" -> fillAllFromLayout()
                    "Fill all days from current goals" -> {
                        if (WeekStore.fillDefaultFromGoals(this)) render()
                        else Toast.makeText(
                            this, "No labels with goals to fill from", Toast.LENGTH_SHORT
                        ).show()
                    }
                    "Back to the week" -> { editingDefault = false; render() }
                    "Pull in the default week" -> confirmPullWeek()
                    "Edit the default week" -> { editingDefault = true; render() }
                    "Delete this week" -> confirmDeleteWeek()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmPullWeek() {
        val dates = currentDates()
        val already = WeekStore.weekHasAnything(this, dates.map { WeekStore.dateKey(it) })
        if (!already) { WeekStore.pullDefaultWeek(this, dates); render(); return }

        AlertDialog.Builder(this)
            .setTitle("Replace this week?")
            .setMessage("It already has blocks. Pulling the default week in replaces them.")
            .setPositiveButton("Replace") { _, _ ->
                WeekStore.pullDefaultWeek(this, dates); render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteWeek() {
        AlertDialog.Builder(this)
            .setTitle("Delete this week's plan?")
            .setMessage(
                "The seven days are removed. When this week comes round it falls back " +
                    "to the default, as though it had never been planned."
            )
            .setPositiveButton("Delete") { _, _ ->
                WeekStore.deleteWeek(this, currentKeys()); render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun dayMenu(key: String, dateMs: Long?) {
        val items = mutableListOf("Set the day's start time", "Pull in a layout\u2026")
        if (!editingDefault) items.add("Copy from another day\u2026")
        items.add("Clear the day")

        AlertDialog.Builder(this)
            .setTitle(if (editingDefault) "Day" else dayFmt.format(Date(dateMs!!)))
            .setItems(items.toTypedArray()) { _, which ->
                when (items[which]) {
                    "Set the day's start time" -> pickDayStart(key)
                    "Pull in a layout\u2026" -> pickLayout(key)
                    "Copy from another day\u2026" -> pickDayToCopyFrom(key)
                    "Clear the day" -> { WeekStore.clearDay(this, key); render() }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickDayStart(key: String) {
        val day = WeekStore.day(this, key)
        TimePickerDialog(
            this,
            { _, h, m ->
                WeekStore.setDayStart(this, key, h * 60 + m); render()
            },
            day.startMinutes / 60, day.startMinutes % 60,
            DateFormat.is24HourFormat(this)
        ).show()
    }

    /**
     * One layout into all seven columns, in its saved manual order.
     *
     * Labels with no goal arrive parked, so every label is present at the foot
     * of each day ready to be dragged up where it applies.
     */
    private fun fillAllFromLayout() {
        val layouts = LayoutStore.readAll(this)
        if (layouts.isEmpty()) {
            Toast.makeText(this, "No saved layouts yet", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Fill all seven days from")
            .setItems(layouts.map { it.name }.toTypedArray()) { _, which ->
                val keys = currentKeys()
                val chosen = layouts[which]
                if (WeekStore.weekHasAnything(this, keys)) {
                    AlertDialog.Builder(this)
                        .setTitle("Replace all seven days?")
                        .setMessage("\u201c${chosen.name}\u201d goes into every day, replacing what's there.")
                        .setPositiveButton("Replace") { _, _ ->
                            WeekStore.fillAllDaysFromLayout(this, keys, chosen); render()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                } else {
                    WeekStore.fillAllDaysFromLayout(this, keys, chosen); render()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Layouts already hold order and goals, so they drop straight into a day. */
    private fun pickLayout(key: String) {
        val layouts = LayoutStore.readAll(this)
        if (layouts.isEmpty()) {
            Toast.makeText(this, "No saved layouts yet", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Pull in a layout")
            .setItems(layouts.map { it.name }.toTypedArray()) { _, which ->
                val chosen = layouts[which]
                WeekStore.clearDay(this, key)
                chosen.entries.filter { it.goalMinutes > 0 }
                    .forEach { WeekStore.addBlock(this, key, it.name, it.goalMinutes) }
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickDayToCopyFrom(toKey: String) {
        val dates = currentDates()
        val names = dates.map { dayFmt.format(Date(it)) }
        AlertDialog.Builder(this)
            .setTitle("Copy from")
            .setItems(names.toTypedArray()) { _, which ->
                WeekStore.copyDay(this, WeekStore.dateKey(dates[which]), toKey); render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addBlock(key: String) {
        val labels = LabelStore.readLibrary(this).sortedBy { it.manualOrder }
        if (labels.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Add a block")
            .setItems(labels.map { it.name }.toTypedArray()) { _, which ->
                WeekStore.addBlock(this, key, labels[which].name, 30); render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Everything about one block, since a tap has to open something. */
    private fun blockMenu(block: Block, key: String) {
        val items = mutableListOf("Set the duration exactly")
        if (!editingDefault) items.add("Move to another day\u2026")
        items.add("Delete this block")

        AlertDialog.Builder(this)
            .setTitle("${block.label}  ${if (block.isParked) "\u2014" else "${block.minutes}m"}")
            .setItems(items.toTypedArray()) { _, which ->
                when (items[which]) {
                    "Set the duration exactly" -> pickDuration(block)
                    "Move to another day\u2026" -> pickDayToMoveTo(block)
                    "Delete this block" -> { WeekStore.deleteBlock(this, block.id); render() }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickDuration(block: Block) {
        val choices = (0..16).map { it * 30 }        // 0 to 8 hours in half hours
        AlertDialog.Builder(this)
            .setTitle("Duration")
            .setItems(choices.map { if (it == 0) "\u2014  parked" else hm(it) }.toTypedArray()) { _, w ->
                WeekStore.setMinutes(this, block.id, choices[w]); render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickDayToMoveTo(block: Block) {
        val dates = currentDates()
        AlertDialog.Builder(this)
            .setTitle("Move to")
            .setItems(dates.map { dayFmt.format(Date(it)) }.toTypedArray()) { _, which ->
                WeekStore.moveToDay(this, block.id, WeekStore.dateKey(dates[which])); render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- helpers ------------------------------------------------------------

    private fun startOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Recorded time for a past day, summed from the log. */
    private fun actualMinutesFor(dateMs: Long): Int {
        val start = Calendar.getInstance().apply {
            timeInMillis = dateMs
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val end = start + 86_400_000L
        return (LogStore.sumByLabel(this, start, end).values.sum() / 60_000L).toInt()
    }

    /** "05:00" \u2014 fixed width, so the columns line up. */
    private fun clock(minutes: Int): String =
        String.format("%02d:%02d", minutes / 60, minutes % 60)

    private fun hm(minutes: Int): String =
        String.format("%d:%02d", minutes / 60, minutes % 60)
}
