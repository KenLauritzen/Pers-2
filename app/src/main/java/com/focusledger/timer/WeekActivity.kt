package com.focusledger.timer

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
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
 * and stop at zero — a zeroed block greys out and sinks to the foot of the
 * column rather than being deleted.
 *
 * Start times are computed, never stored, so moving a block moves every start
 * time below it.
 */
class WeekActivity : AppCompatActivity() {

    companion object {
        /** Hold before a drag arms. Roughly half the system long press. */
        private const val HOLD_MS = 250L
    }

    private lateinit var b: ActivityWeekBinding

    /** The leftmost of the three days on screen, at midnight. */
    private var anchorMs = midnight(System.currentTimeMillis())

    /** Set when viewing the seven weekday shapes rather than dated days. */
    private var editingDefault = false

    /** In the default week, the leftmost of the three weekday slots shown. */
    private var defaultSlot = 0

    private val dayFmt = SimpleDateFormat("EEE", Locale.getDefault())
    private val dayNumFmt = SimpleDateFormat("EEE d", Locale.getDefault())
    private val monthFmt = SimpleDateFormat("MMM", Locale.getDefault())
    private val defaultDayNames =
        arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

    /**
     * How far each day was scrolled, by key. Every change redraws the whole
     * week, and a rebuilt column starts at the top \u2014 so without this, adjusting
     * a block near the bottom of a day threw it out of view on release.
     */
    private val scrollPositions = mutableMapOf<String, Int>()

    /** Days on screen at once. Three gives each about 260dp in landscape. */
    private val visibleDays = 3

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityWeekBinding.inflate(layoutInflater)
        setContentView(b.root)

        // One day per tap, carrying on across week boundaries. Arrows rather
        // than sideways scrolling, so a sideways drag only ever means duration.
        b.weekPrev.setOnClickListener { shiftDays(-1) }
        b.weekNext.setOnClickListener { shiftDays(1) }
        b.weekTitle.setOnClickListener { pickDate() }
        b.weekAction.setOnClickListener { weekMenu() }
        b.weekDone.setOnClickListener { finish() }

        render()
    }

    override fun onResume() {
        super.onResume()
        // A label added on the main screen since the week was filled joins
        // every planned day as a parked block, ready to be dragged up.
        if (WeekStore.addMissingLabelsAsParked(this)) render()
    }

    private fun shiftDays(n: Int) {
        if (editingDefault) {
            defaultSlot = (defaultSlot + n).coerceIn(0, 7 - visibleDays)
        } else {
            anchorMs = Calendar.getInstance().apply {
                timeInMillis = anchorMs
                add(Calendar.DAY_OF_YEAR, n)
            }.timeInMillis
        }
        render()
    }

    private fun pickDate() {
        if (editingDefault) return
        val c = Calendar.getInstance().apply { timeInMillis = anchorMs }
        DatePickerDialog(
            this,
            { _, y, m, d ->
                anchorMs = midnight(Calendar.getInstance().apply { set(y, m, d) }.timeInMillis)
                render()
            },
            c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    /** The three dated days on screen. */
    private fun visibleDates(): List<Long> = (0 until visibleDays).map { i ->
        Calendar.getInstance().apply {
            timeInMillis = anchorMs
            add(Calendar.DAY_OF_YEAR, i)
        }.timeInMillis
    }

    /** The three keys on screen: dates, or weekday slots in the default week. */
    private fun visibleKeys(): List<String> =
        if (editingDefault) (defaultSlot until defaultSlot + visibleDays).map { "D$it" }
        else visibleDates().map { WeekStore.dateKey(it) }

    /**
     * The whole week containing the leftmost day on screen. Week-wide actions
     * \u2014 pull in the default, fill from a layout, delete \u2014 act on this.
     */
    private fun weekDates(): List<Long> =
        WeekStore.weekOf(anchorMs, SettingsStore.getWeekStartDow(this))

    private fun weekKeys(): List<String> =
        if (editingDefault) (0..6).map { "D$it" }
        else weekDates().map { WeekStore.dateKey(it) }

    // ---- drawing ------------------------------------------------------------

    private fun render() {
        b.weekTitle.text = if (editingDefault) {
            "Default \u2014 ${defaultDayNames[defaultSlot]} to " +
                defaultDayNames[defaultSlot + visibleDays - 1]
        } else {
            val dates = visibleDates()
            "${dayNumFmt.format(Date(dates.first()))} \u2013 " +
                "${dayNumFmt.format(Date(dates.last()))} ${monthFmt.format(Date(dates.last()))}"
        }
        b.weekPrev.alpha = if (editingDefault && defaultSlot == 0) 0.3f else 1f
        b.weekNext.alpha =
            if (editingDefault && defaultSlot == 7 - visibleDays) 0.3f else 1f

        // Note where each visible day was scrolled before tearing it down.
        for (i in 0 until b.weekColumns.childCount) {
            val old = b.weekColumns.getChildAt(i)
            val oldKey = old.tag as? String ?: continue
            old.findViewById<ScrollView>(R.id.colScroll)?.let {
                scrollPositions[oldKey] = it.scrollY
            }
        }

        b.weekColumns.removeAllViews()
        val inflater = LayoutInflater.from(this)

        // Fixed widths rather than weights, so three columns share the screen
        // and each gets room for "10:45 120m Social Building" at 14sp.
        val gutter = (2 * resources.displayMetrics.density).toInt()
        val colWidth = (b.weekColumns.width.takeIf { it > 0 }
            ?: (resources.displayMetrics.widthPixels -
                (12 * resources.displayMetrics.density).toInt())) / visibleDays - gutter

        visibleKeys().forEachIndexed { i, key ->
            val col = inflater.inflate(R.layout.column_week_day, b.weekColumns, false)
            col.layoutParams = LinearLayout.LayoutParams(
                colWidth, LinearLayout.LayoutParams.MATCH_PARENT
            ).apply { marginEnd = gutter }

            val header = col.findViewById<TextView>(R.id.colHeader)
            val list = col.findViewById<LinearLayout>(R.id.colBlocks)
            val add = col.findViewById<TextView>(R.id.colAdd)

            val dateMs = if (editingDefault) null else visibleDates()[i]
            val slot = if (editingDefault) defaultSlot + i else 0
            drawHeader(header, key, dateMs, slot)

            // A day that differs from its default is worth seeing at a glance
            // while planning.
            if (dateMs != null && WeekStore.differsFromDefault(this, dateMs)) {
                col.setBackgroundResource(R.drawable.bg_row_idle)
            }

            drawBlocks(list, key, dateMs)

            header.setOnClickListener { dayMenu(key, dateMs) }
            add.setOnClickListener { addBlock(key) }
            col.tag = key
            b.weekColumns.addView(col)

            // Put the day back where it was. Posted, because a ScrollView can't
            // scroll until its content has been laid out. Keyed by day rather
            // than column, so a day keeps its place as the arrows shift it.
            val y = scrollPositions[key] ?: 0
            if (y > 0) {
                val sv = col.findViewById<ScrollView>(R.id.colScroll)
                sv.post { sv.scrollTo(0, y) }
            }
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
                attachGestures(row, block, key)
            } else {
                row.alpha = 0.7f
            }
            list.addView(row)
        }
    }

    // ---- the two drag axes --------------------------------------------------

    /**
     * Every gesture on a block, in one listener:
     *
     *   tap                      "Move above\u2026" list
     *   hold, then drag \u2195       move within the day
     *   hold, then drag \u2194       change the duration, 5 minutes a step
     *   hold, and let go         menu: exact duration, move day, delete
     *   quick swipe              scrolls the day
     *
     * The hold is a real timer, not an accident of which view wins the finger.
     * Before, a drag and the column's scroll raced for every movement and
     * holding still first merely tended to let the drag win \u2014 which is why the
     * hold felt long and a little unpredictable. When the timer fires the
     * block lights up and buzzes, so it's clear the drag is ready.
     */
    private fun attachGestures(row: TextView, block: Block, key: String) {
        val d = resources.displayMetrics.density
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        val stepPx = 13 * d              // one 5-minute step: 13dp, down from 20
        val rowPx = 28 * d               // matches the block height

        var startX = 0f
        var startY = 0f
        var armed = false
        var axis = 0                     // 0 undecided, 1 sideways, 2 up/down
        var pendingMinutes = block.minutes
        var pendingIndex = -1
        var movedBeforeArming = false

        val arm = Runnable {
            armed = true
            // Hold the column still: from here the finger belongs to the block.
            row.parent?.requestDisallowInterceptTouchEvent(true)
            row.setBackgroundColor(0x40E8A33D)
            row.setTextColor(0xFFE8A33D.toInt())
            row.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }

        row.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = ev.rawX; startY = ev.rawY
                    armed = false; axis = 0
                    movedBeforeArming = false
                    pendingMinutes = block.minutes
                    pendingIndex = -1
                    // About half the phone's usual long press.
                    row.postDelayed(arm, HOLD_MS)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - startX
                    val dy = ev.rawY - startY
                    if (!armed) {
                        // Moving before the hold completes is a swipe, not a
                        // drag \u2014 let the column have it.
                        if (abs(dx) > slop || abs(dy) > slop) {
                            movedBeforeArming = true
                            row.removeCallbacks(arm)
                        }
                        return@setOnTouchListener false
                    }
                    if (axis == 0) {
                        if (abs(dx) < slop && abs(dy) < slop) return@setOnTouchListener true
                        axis = if (abs(dx) > abs(dy)) 1 else 2
                    }
                    if (axis == 1) showDurationPreview(row, block, dx, stepPx) { pendingMinutes = it }
                    else showMovePreview(row, block, key, dy, rowPx) { pendingIndex = it }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    row.removeCallbacks(arm)
                    row.parent?.requestDisallowInterceptTouchEvent(false)
                    val wasArmed = armed
                    val whichAxis = axis
                    val minutes = pendingMinutes
                    val index = pendingIndex
                    val moved = movedBeforeArming

                    // Cleared before anything else. Redrawing removes this view
                    // while it's still handling the gesture, and Android answers
                    // with ACTION_CANCEL \u2014 which, with state still set, used to
                    // redraw again from inside the first redraw and crash.
                    armed = false; axis = 0

                    // Everything posted, so it runs after this touch event has
                    // finished dispatching rather than inside it.
                    when {
                        wasArmed && whichAxis != 0 ->
                            row.post { commitDrag(block, key, whichAxis, minutes, index) }
                        wasArmed ->
                            row.post { render(); blockMenu(block, key) }
                        !moved ->
                            row.post {
                                if (block.isParked) blockMenu(block, key)
                                else moveAbovePicker(block, key)
                            }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    // Abandoned: the column took the gesture, or something
                    // interrupted it. Nothing is saved.
                    row.removeCallbacks(arm)
                    row.parent?.requestDisallowInterceptTouchEvent(false)
                    val wasArmed = armed
                    armed = false; axis = 0
                    if (wasArmed) row.post { render() }
                    true
                }
                else -> false
            }
        }
    }

    /**
     * "+15   45m Fitness" while dragging sideways: the change on the left, the
     * new total on the right. Stops at zero, where the block parks.
     */
    private fun showDurationPreview(
        row: TextView, block: Block, dx: Float, stepPx: Float, onValue: (Int) -> Unit
    ) {
        val minutes = (block.minutes + (dx / stepPx).toInt() * 5).coerceAtLeast(0)
        onValue(minutes)
        val delta = minutes - block.minutes
        val sign = when {
            delta > 0 -> "+$delta"
            delta < 0 -> "\u2212${-delta}"
            else -> "  0"
        }
        val total = if (minutes <= 0) "  \u2014 " else "${minutes}m"
        row.text = "${sign.padStart(4)}  ${total.padStart(5)} ${block.label}"
    }

    /** The block's new start time, shown as it moves up or down. */
    private fun showMovePreview(
        row: TextView, block: Block, key: String, dy: Float, rowPx: Float, onIndex: (Int) -> Unit
    ) {
        if (block.isParked) return                 // parked blocks stay at the foot
        val live = WeekStore.blocksFor(this, key).filterNot { it.isParked }
        val from = live.indexOfFirst { it.id == block.id }
        if (from < 0) return
        val to = (from + (dy / rowPx).toInt()).coerceIn(0, live.size - 1)
        onIndex(to)

        val reordered = live.toMutableList().apply { add(to, removeAt(from)) }
        var t = WeekStore.day(this, key).startMinutes
        for (b2 in reordered) {
            if (b2.id == block.id) break
            t += b2.minutes
        }
        val arrow = when {
            to < from -> "\u2191${from - to}"
            to > from -> "\u2193${to - from}"
            else -> "  "
        }
        row.text = "$arrow ${clock(t)} ${block.minutes}m ${block.label}"
    }

    /**
     * Tap a block to put it above another, as on the main screen. Parked
     * blocks are in the list too, so choosing the first of them puts a block
     * last among the live ones \u2014 no separate "move to the end" needed.
     */
    private fun moveAbovePicker(block: Block, key: String) {
        val all = WeekStore.blocksFor(this, key)
        val others = all.filter { it.id != block.id }
        if (others.isEmpty()) return
        val day = WeekStore.day(this, key)
        val starts = WeekStore.startTimes(day, all)

        val items = others.map { o ->
            if (o.isParked) "        \u2014   ${o.label}"
            else "${clock(starts[o.id] ?: day.startMinutes).padStart(5)}  ${o.minutes}m  ${o.label}"
        }
        pickFromList("Move \u201c${block.label}\u201d above\u2026", items) { which ->
            val target = others[which]
            val ids = all.map { it.id }.toMutableList()
            ids.remove(block.id)
            ids.add(ids.indexOf(target.id).coerceAtLeast(0), block.id)
            WeekStore.reorder(this, key, ids)
            render()
        }
    }

    /** The compact chooser the main screen uses, for the same reasons. */
    private fun pickFromList(title: String, items: List<String>, onPick: (Int) -> Unit) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_pick_list, null)
        view.findViewById<TextView>(R.id.pickTitle).text = title
        val list = view.findViewById<LinearLayout>(R.id.pickList)
        val dialog = AlertDialog.Builder(this).setView(view).create()
        val inflater = LayoutInflater.from(this)
        items.forEachIndexed { i, text ->
            val r = inflater.inflate(R.layout.row_pick_item, list, false) as TextView
            r.text = text
            r.typeface = android.graphics.Typeface.MONOSPACE
            r.setOnClickListener { dialog.dismiss(); onPick(i) }
            list.addView(r)
        }
        view.findViewById<android.widget.Button>(R.id.pickCancel)
            .setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    /**
     * Applies a finished drag. Runs posted, after the touch event has fully
     * dispatched, so the redraw can't re-enter the gesture that caused it.
     */
    private fun commitDrag(block: Block, key: String, axis: Int, minutes: Int, index: Int) {
        when {
            axis == 1 && minutes != block.minutes ->
                WeekStore.setMinutes(this, block.id, minutes)
            axis == 2 && index >= 0 -> {
                val live = WeekStore.blocksFor(this, key)
                    .filterNot { it.isParked }.map { it.id }.toMutableList()
                val from = live.indexOf(block.id)
                if (from >= 0 && from != index) {
                    live.add(index.coerceIn(0, live.size - 1), live.removeAt(from))
                    val parked = WeekStore.blocksFor(this, key)
                        .filter { it.isParked }.map { it.id }
                    WeekStore.reorder(this, key, live + parked)
                }
            }
        }
        render()
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
            "Planning with someone\u2026",
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
                    "Planning with someone\u2026" ->
                        startActivity(android.content.Intent(this, ComparisonActivity::class.java))
                    "Delete this week" -> confirmDeleteWeek()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmPullWeek() {
        val dates = weekDates()
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
                WeekStore.deleteWeek(this, weekKeys()); render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun dayMenu(key: String, dateMs: Long?) {
        val items = mutableListOf("Set the day's start time", "Pull in a layout\u2026")
        // Today only: re-apply on demand, since the morning prompt asks once.
        if (dateMs != null && WeekStore.dateKey(dateMs) ==
            WeekStore.dateKey(System.currentTimeMillis())) {
            items.add(0, "Apply to today's timers")
        }
        if (!editingDefault) items.add("Copy from another day\u2026")
        items.add("Clear the day")

        AlertDialog.Builder(this)
            .setTitle(if (editingDefault) "Day" else dayFmt.format(Date(dateMs!!)))
            .setItems(items.toTypedArray()) { _, which ->
                when (items[which]) {
                    "Apply to today's timers" -> {
                        if (WeekStore.applyToMainScreen(this, key))
                            Toast.makeText(this, "Applied to today", Toast.LENGTH_SHORT).show()
                    }
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
                val keys = weekKeys()
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
        val dates = weekDates()
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
        val dates = weekDates()
        AlertDialog.Builder(this)
            .setTitle("Move to")
            .setItems(dates.map { dayFmt.format(Date(it)) }.toTypedArray()) { _, which ->
                WeekStore.moveToDay(this, block.id, WeekStore.dateKey(dates[which])); render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- helpers ------------------------------------------------------------

    private fun midnight(ms: Long): Long = Calendar.getInstance().apply {
        timeInMillis = ms
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

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
