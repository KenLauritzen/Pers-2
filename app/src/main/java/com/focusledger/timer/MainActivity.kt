package com.focusledger.timer

import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.focusledger.timer.databinding.ActivityMainBinding
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val handler = Handler(Looper.getMainLooper())

    /**
     * The row order currently on screen. Recomputed on resume and on any
     * start/stop — never on the per-second tick, so rows can't shuffle out
     * from under a finger (which matters most in "Remaining" mode).
     */
    private var displayed = listOf<LabelEntry>()

    private lateinit var adapter: TimerAdapter
    private var dragHelper: ItemTouchHelper? = null

    /**
     * Goal-slide in progress. While set, the row's right column shows the
     * pending value rather than the stored one, and the per-second refresh
     * leaves it alone.
     */
    private var slideLabel: String? = null
    private var slidePendingGoal = 0
    private var slideStartGoal = 0
    private var slideStartY = 0f

    /** When task time was last written to file. See refreshValues. */
    private var lastTaskSettle = 0L

    /** The same, for the elapsed-time slider on the timer column. */
    private var timeSlideLabel: String? = null
    private var timeSlidePendingMinutes = 0
    private var timeSlideStartMs = 0L
    private var timeSlideStartY = 0f
    private var rowHeightPx = 0

    // Running totals down [displayed], recomputed whenever values change and
    // cached so a freshly scrolled row gets the same figure as one already on
    // screen. Inclusive and exclusive of each row, on both bases: the
    // exclusive ones are what the clock modes need, since a row's projected
    // time is everything above it, not including itself.
    // The exclusive ones are what the clock modes need: a row's projected
    // time is everything above it, not including itself.
    private var goalInc = listOf<Long>()
    private var goalBefore = listOf<Long>()
    private var remInc = listOf<Long>()
    private var remBefore = listOf<Long>()


    private val amber = 0xFFE8A33D.toInt()
    private val blueGrey = 0xFF6FAFC4.toInt()
    private val muted = 0xFF8FA39E.toInt()
    private val goalGrey = 0xFFA9BDB8.toInt()
    // Third column: green when showing a goal, blue when showing time still to
    // go, red once the goal has been passed.
    private val overRed = 0xFFC97064.toInt()
    // Two signals doing separate jobs: colour says what kind of figure this is
    // (a goal or time remaining), the sigma prefix says whether it's per-row or
    // a running total. Four colours would make the prefix redundant.
    //
    // Mint rather than the bar's own green, so a goal figure doesn't blend into
    // a filled row.
    private val colGoal = 0xFF9FD8A8.toInt()
    private val colRemain = 0xFF6FAFC4.toInt()
    // Both clock modes share a colour; @ and ~ tell them apart.
    private val colClock = 0xFFB8C4C2.toInt()
    /** Overage in Rem mode: bright peach, legible on the burnt-red bar. */
    private val colOver = 0xFFFFAE73.toInt()
    /**
     * Wash over the timer column when nothing at all is running. Translucent,
     * so the progress bar still reads through it, and applied to every row so
     * it's visible wherever the list happens to be scrolled.
     */
    private val idleWash = 0x38C97064

    private val tickRunnable = object : Runnable {
        override fun run() {
            refreshValues()
            handler.postDelayed(this, 1000)
        }
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private fun sessionView() = SettingsStore.isSessionView(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (!AppState.processTouched || !TimerStore.hasOpenSession(this)) {
            AppState.processTouched = true
            TimerStore.beginNewSession(this)
        }
        TimerStore.rolloverDayCountersIfNeeded(this)

        requestNotificationPermissionIfNeeded()
        setupList()

        binding.btnStop.setOnClickListener {
            if (!TimerStore.isRunning(this)) return@setOnClickListener
            TimerStore.stop(this)
            offerNote()
            rebuild(); syncService()
        }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnDone.setOnClickListener { confirmDone() }
        binding.diffDismiss.setOnClickListener { binding.diffBanner.visibility = View.GONE }
        binding.btnSort.setOnClickListener { showSortPicker() }

        binding.btnViewDay.setOnClickListener {
            SettingsStore.setSessionView(this, false); refreshValues(); updateChrome()
        }
        binding.btnViewSession.setOnClickListener {
            SettingsStore.setSessionView(this, true); refreshValues(); updateChrome()
        }
        // All three totals sit at 26sp. Auto-sizing only bites on the one
        // case that doesn't fit — a clock total with a double-digit hour,
        // "@11:00a", in a 30% column — so they stay uniform in normal use
        // rather than every figure shrinking to suit the widest.
        listOf(binding.tvTotalSub, binding.tvTotalElapsed, binding.tvTotalGoal)
            .forEach {
                TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                    it, 19, 26, 1, TypedValue.COMPLEX_UNIT_SP
                )
            }

        binding.btnCum.setOnClickListener { showColumnPicker() }
        binding.btnCum2.setOnClickListener { showSecondaryPicker() }
        binding.tvHeader.setOnClickListener { showStartTimePicker() }

        binding.btnSaveNote.setOnClickListener {
            val text = binding.editNote.text.toString()
            if (text.isNotBlank()) {
                LogStore.attachNoteToLastRow(this, text)
                Toast.makeText(this, "Note added", Toast.LENGTH_SHORT).show()
            }
            binding.editNote.setText("")
            binding.noteBar.visibility = View.GONE
        }
    }

    // ---- row construction --------------------------------------------------

    /**
     * Sizes rows so ten fit the visible list, then rebuilds. Fixed for a given
     * screen: it doesn't change with how many labels are showing, so rows
     * never resize as you add or hide one. Anything past ten scrolls.
     */
    private fun measureAndRebuild() {
        val d = resources.displayMetrics.density
        val available = binding.rowsList.height

        // Two lines of label need about 43dp, so the old 44dp floor would
        // clip on a short screen. With a second figure showing, rows get a
        // taller minimum and the list scrolls a little sooner — which is the
        // trade you accept by turning it on.
        val minDp = if (SettingsStore.getSecondaryMode(this) == SettingsStore.COL_NONE) 44 else 52

        rowHeightPx = if (available > 0)
            ((available / 10) - (3 * d)).toInt().coerceIn((minDp * d).toInt(), (64 * d).toInt())
        else (56 * d).toInt()
        rebuild()
    }

    /** Labels that have at least one note logged, refreshed on rebuild. */
    private var labelsWithNotes = setOf<String>()

    /** Recomputes the order and rebuilds the rows. */
    private fun rebuild() {
        labelsWithNotes = LogStore.labelsWithNotes(this)
        TimerStore.rolloverDayCountersIfNeeded(this)
        val library = LabelStore.readLibrary(this)
        // Every label shows: hiding was removed in v29.
        displayed = LabelStore.sortedBy(
            library,
            SettingsStore.getSortMode(this)
        ) { name ->
            val goal = library.firstOrNull { it.name == name }?.goalMinutes ?: 0
            TimerStore.getRemainingMs(this, name, goal)
        }
        // Before notifying: onBindViewHolder reads the running totals, so they
        // must match the new displayed list rather than the previous one.
        computeCumulative()
        adapter.notifyDataSetChanged()
        refreshValues()
        updateChrome()
    }

    /** Wires up the list once; the adapter owns the rows from then on. */
    private fun setupList() {
        adapter = TimerAdapter()
        binding.rowsList.layoutManager = LinearLayoutManager(this)
        binding.rowsList.adapter = adapter
        binding.rowsList.itemAnimator = null   // per-second updates shouldn't animate

        // Long-press to drag, Manual mode only. In the sorted modes the
        // position is computed, so a drag would have nowhere to persist to.
        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            // Started explicitly from a long-press on the note icon, so that
            // long-pressing the row itself can open the label popup instead.
            override fun isLongPressDragEnabled(): Boolean = false

            override fun onMove(
                rv: RecyclerView,
                vh: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = vh.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                // The trailing "+ New label" row isn't draggable and nothing
                // can be dropped past it.
                if (from >= displayed.size || to >= displayed.size) return false
                val list = displayed.toMutableList()
                list.add(to, list.removeAt(from))
                displayed = list
                adapter.notifyItemMoved(from, to)
                return true
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}

            override fun onSelectedChanged(vh: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(vh, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    vh?.itemView?.alpha = 0.85f
                    vh?.itemView?.elevation = 12f
                }
            }

            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                vh.itemView.alpha = 1f
                vh.itemView.elevation = 0f
                persistDraggedOrder()
            }
        })
        touchHelper.attachToRecyclerView(binding.rowsList)
        dragHelper = touchHelper
    }

    /**
     * Writes the on-screen order into manualOrder once a drag settles. Labels
     * not on screen keep their existing relative order after them.
     */
    private fun persistDraggedOrder() {
        val library = LabelStore.readLibrary(this)
        val shown = displayed.map { it.name }
        val rest = library
            .filter { !shown.contains(it.name) }
            .sortedWith(compareBy<LabelEntry> { it.manualOrder }.thenBy { it.name.lowercase() })
            .map { it.name }
        val updated = LabelStore.applyManualOrder(library, shown + rest)
        if (!LabelStore.writeLibrary(this, updated)) {
            Toast.makeText(this, "Couldn't save the new order", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * The task block, shown under the running row only.
     *
     * Every other row stays a single fixed-height line. The block appears when
     * a label starts and goes when it stops, so the list only grows where
     * you're actually working.
     */
    private fun renderTasks(h: TimerAdapter.Holder, entry: LabelEntry) {
        val box = h.taskBox ?: return
        val view = h.tasks ?: return

        val isRunning = TimerStore.getActiveLabel(this) == entry.name
        val limit = TaskStore.getShowCount(this, entry.name)

        // Fixed height for an ordinary row; the running row wraps so it can
        // grow by however many task lines it holds.
        fun setHeight(fixed: Boolean) {
            val lp = h.itemView.layoutParams as? ViewGroup.MarginLayoutParams ?: return
            val want = if (fixed && rowHeightPx > 0) rowHeightPx
                       else ViewGroup.LayoutParams.WRAP_CONTENT
            if (lp.height != want) { lp.height = want; h.itemView.layoutParams = lp }
        }

        // Zero opts a label out of tasks entirely.
        if (!isRunning || limit == 0) {
            box.visibility = View.GONE
            setHeight(true)
            return
        }
        box.visibility = View.VISIBLE
        setHeight(false)

        // The columns keep their normal height inside the taller row.
        (h.columns?.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
            if (lp.height != rowHeightPx && rowHeightPx > 0) {
                lp.height = rowHeightPx
                lp.weight = 0f
                h.columns?.layoutParams = lp
            }
        }

        val shown = TaskStore.visibleForRow(this, entry.name, limit)
        view.text = if (shown.isEmpty()) "No tasks" else shown.joinToString("\n") { t ->
            val mark = when (t.status) {
                TaskStatus.DOING -> "\u25b8"
                TaskStatus.DONE -> "\u2713"
                else -> "-"
            }
            val est = if (t.estimateMinutes > 0) "  ${t.estimateMinutes}m" else ""
            "$mark ${t.text}$est"
        }
        view.setTextColor(if (shown.isEmpty()) 0xFF5C736E.toInt() else 0xFFA9BDB8.toInt())

        h.taskEdit?.setOnClickListener { showTaskEditor(entry.name) }
    }

    /**
     * Everything about one label's tasks: add, reorder, set estimates, cycle
     * status, and choose how many the row shows.
     *
     * Tapping a task's marker advances it — open, doing, done, archived — and
     * marking one "doing" is what starts time accruing against it.
     */
    private fun showTaskEditor(label: String) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_tasks, null)
        val list = view.findViewById<LinearLayout>(R.id.tasksList)
        val countView = view.findViewById<TextView>(R.id.tasksShowCount)
        view.findViewById<TextView>(R.id.tasksTitle).text = "$label \u2014 tasks"

        val dialog = AlertDialog.Builder(this).setView(view).create()

        fun render() {
            countView.text = TaskStore.getShowCount(this, label).toString()
            list.removeAllViews()
            val tasks = TaskStore.forLabel(this, label)
            if (tasks.isEmpty()) {
                list.addView(TextView(this).apply {
                    text = "No tasks yet."
                    setTextColor(0xFF5C736E.toInt())
                    textSize = 15f
                    setPadding(4, 12, 4, 12)
                })
                return
            }
            tasks.forEachIndexed { i, t ->
                val row = LayoutInflater.from(this)
                    .inflate(R.layout.row_task_edit, list, false)

                val mark = row.findViewById<TextView>(R.id.taskMark)
                mark.text = when (t.status) {
                    TaskStatus.OPEN -> ""
                    TaskStatus.DOING -> "\u25b8"
                    TaskStatus.DONE -> "\u2713"
                    TaskStatus.ARCHIVED -> "\u00d7"
                }
                mark.setTextColor(
                    when (t.status) {
                        TaskStatus.DOING -> amber
                        TaskStatus.DONE -> colGoal
                        else -> muted
                    }
                )
                // It's the control you tap to advance a task, so it shouldn't
                // be the smallest thing in the row.
                mark.textSize = 22f

                val textView = row.findViewById<TextView>(R.id.taskText)
                textView.text = t.text
                textView.setTextColor(
                    when (t.status) {
                        TaskStatus.DONE, TaskStatus.ARCHIVED -> 0xFF5C736E.toInt()
                        else -> 0xFFF1EDE3.toInt()
                    }
                )

                // What it has taken, against what was estimated, and how much
                // of the estimate that uses. Past 100% keeps counting rather
                // than capping: how far over is the useful part.
                val actual = TaskStore.liveMs(this, t)
                val actualMin = (actual / 60_000L).toInt()
                val times = row.findViewById<TextView>(R.id.taskTimes)
                if (t.estimateMinutes > 0) {
                    val pct = actualMin * 100 / t.estimateMinutes
                    times.text = "${actualMin}m / ${t.estimateMinutes}m  $pct%"
                    times.setTextColor(if (pct > 100) colOver else muted)
                } else {
                    // Nothing to be a percentage of.
                    times.text = if (actualMin > 0) "${actualMin}m" else ""
                    times.setTextColor(muted)
                }

                mark.setOnClickListener { TaskStore.cycleStatus(this, t); render(); rebuild() }
                textView.setOnClickListener { editTask(t) { render(); rebuild() } }

                val ids = tasks.map { it.id }.toMutableList()
                row.findViewById<ImageView>(R.id.taskUp).setOnClickListener {
                    if (i > 0) {
                        ids.add(i - 1, ids.removeAt(i))
                        TaskStore.reorder(this, label, ids); render(); rebuild()
                    }
                }
                row.findViewById<ImageView>(R.id.taskDown).setOnClickListener {
                    if (i < ids.size - 1) {
                        ids.add(i + 1, ids.removeAt(i))
                        TaskStore.reorder(this, label, ids); render(); rebuild()
                    }
                }
                list.addView(row)
            }
        }
        render()

        view.findViewById<Button>(R.id.tasksShowMinus).setOnClickListener {
            TaskStore.setShowCount(this, label, TaskStore.getShowCount(this, label) - 1)
            render(); measureAndRebuild()
        }
        view.findViewById<Button>(R.id.tasksShowPlus).setOnClickListener {
            TaskStore.setShowCount(this, label, TaskStore.getShowCount(this, label) + 1)
            render(); measureAndRebuild()
        }

        val newText = view.findViewById<EditText>(R.id.taskNewText)
        val newEst = view.findViewById<EditText>(R.id.taskNewEstimate)
        view.findViewById<Button>(R.id.taskAdd).setOnClickListener {
            val entered = newText.text.toString().trim()
            if (entered.isEmpty()) return@setOnClickListener
            TaskStore.add(this, label, entered, newEst.text.toString().toIntOrNull() ?: 0)
            newText.setText(""); newEst.setText("")
            render(); rebuild()
        }
        view.findViewById<Button>(R.id.tasksDone).setOnClickListener {
            // Anything typed but not added is saved rather than lost — tapping
            // Done with a task half-entered clearly means to keep it.
            val pending = newText.text.toString().trim()
            if (pending.isNotEmpty()) {
                TaskStore.add(this, label, pending, newEst.text.toString().toIntOrNull() ?: 0)
                rebuild()
            }
            dialog.dismiss()
        }
        dialog.show()
    }

    /** Rename a task, change its estimate, or remove it. */
    private fun editTask(task: Task, onChange: () -> Unit) {
        textInputDialog(
            title = "Edit task",
            initial = task.text,
            hint = "Task",
            secondInitial = if (task.estimateMinutes > 0) task.estimateMinutes.toString() else "",
            secondHint = "Estimate in minutes",
            onDelete = { TaskStore.delete(this, task.id); onChange() }
        ) { text, estimate ->
            TaskStore.update(
                this,
                task.copy(
                    text = text.ifEmpty { task.text },
                    estimateMinutes = estimate.toIntOrNull() ?: 0
                )
            )
            onChange()
        }
    }

    /**
     * One dark text-entry dialog, shared by everything small enough to need
     * only a field or two.
     *
     * These were EditTexts handed to a plain AlertDialog, which draws white
     * while the fields carried cream text. Unreadable, and in four places.
     */
    private fun textInputDialog(
        title: String,
        initial: String = "",
        hint: String = "",
        secondInitial: String? = null,
        secondHint: String = "",
        onDelete: (() -> Unit)? = null,
        onSave: (String, String) -> Unit
    ) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_text_input, null)
        view.findViewById<TextView>(R.id.inputTitle).text = title

        val primary = view.findViewById<EditText>(R.id.inputPrimary)
        primary.setText(initial)
        primary.hint = hint
        primary.setSelection(primary.text.length)

        val secondary = view.findViewById<EditText>(R.id.inputSecondary)
        if (secondInitial != null) {
            secondary.visibility = View.VISIBLE
            secondary.setText(secondInitial)
            secondary.hint = secondHint
        }

        val dialog = AlertDialog.Builder(this).setView(view).create()

        view.findViewById<Button>(R.id.inputSave).setOnClickListener {
            onSave(primary.text.toString().trim(), secondary.text.toString().trim())
            dialog.dismiss()
        }
        view.findViewById<Button>(R.id.inputCancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<Button>(R.id.inputDelete).apply {
            if (onDelete != null) {
                visibility = View.VISIBLE
                setOnClickListener { dialog.dismiss(); onDelete() }
            }
        }
        dialog.show()
    }

    // ---- row gestures ------------------------------------------------------

    /** Zone 3: tapping the timer starts an idle label or stops a running one. */
    private fun toggleTimer(label: String) {
        if (TimerStore.getActiveLabel(this) == label) TimerStore.stop(this)
        else TimerStore.start(this, label)
        refreshValues(); updateChrome(); syncService()
    }

    /** Zones 1 and 2 both start a drag, when the order is manual. */
    private fun beginDrag(holder: TimerAdapter.Holder) {
        if (SettingsStore.getSortMode(this) == SettingsStore.SORT_MANUAL) {
            dragHelper?.startDrag(holder)
        } else {
            Toast.makeText(this, "Switch to Manual order to rearrange", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Zone 3: long-press then slide to correct the recorded time.
     *
     * Applied through [TimerStore.adjust], which already does the right thing
     * either way: on the running label it folds into that run's row, and on an
     * idle one it writes an adjustment of its own. Nothing is written until
     * the finger lifts.
     */
    private fun attachTimeSlider(holder: TimerAdapter.Holder, entry: LabelEntry) {
        val timeView = holder.time ?: return
        val stepPx = 24 * resources.displayMetrics.density

        timeView.setOnLongClickListener {
            timeSlideLabel = entry.name
            timeSlideStartMs = TimerStore.getDayMs(this, entry.name)
            timeSlidePendingMinutes = 0
            timeSlideStartY = Float.NaN
            binding.rowsList.requestDisallowInterceptTouchEvent(true)
            refreshValues()
            true
        }

        timeView.setOnTouchListener { _, ev ->
            if (timeSlideLabel != entry.name) return@setOnTouchListener false
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    if (timeSlideStartY.isNaN()) timeSlideStartY = ev.rawY
                    val steps = ((timeSlideStartY - ev.rawY) / stepPx).toInt()
                    // Can't take a label below zero for the day.
                    val floor = (-timeSlideStartMs / 60_000L).toInt()
                    timeSlidePendingMinutes = stepDelta(steps).coerceAtLeast(floor)
                    refreshValues()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val label = entry.name
                    val minutes = timeSlidePendingMinutes
                    timeSlideLabel = null
                    binding.rowsList.requestDisallowInterceptTouchEvent(false)
                    if (minutes != 0) {
                        TimerStore.adjust(this, label, minutes)
                        rebuild(); syncService()
                    } else refreshValues()
                    true
                }
                else -> false
            }
        }
    }

    /**
     * Zone 4: long-press then slide to change the goal without typing.
     *
     * The first step is 5 minutes, every step after that 15 — so a short
     * nudge is fine-grained and a longer drag covers ground quickly. Up adds,
     * down subtracts, floored at zero.
     *
     * Nothing is written until the finger lifts. The column shows the pending
     * value in amber meanwhile, which puts the preview exactly where the
     * finger already is.
     */
    private fun attachGoalSlider(holder: TimerAdapter.Holder, entry: LabelEntry) {
        val goalView = holder.goal ?: return
        val stepPx = 24 * resources.displayMetrics.density

        goalView.setOnLongClickListener {
            val current = LabelStore.readLibrary(this)
                .firstOrNull { it.name == entry.name }?.goalMinutes ?: 0
            slideLabel = entry.name
            slideStartGoal = current
            slidePendingGoal = current
            slideStartY = Float.NaN          // set on the first move
            // Stop the list scrolling underneath the gesture.
            binding.rowsList.requestDisallowInterceptTouchEvent(true)
            refreshValues()
            true
        }

        goalView.setOnTouchListener { _, ev ->
            if (slideLabel != entry.name) return@setOnTouchListener false
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    if (slideStartY.isNaN()) slideStartY = ev.rawY
                    // Up is positive: screen Y grows downward.
                    val steps = ((slideStartY - ev.rawY) / stepPx).toInt()
                    slidePendingGoal = (slideStartGoal + stepDelta(steps)).coerceAtLeast(0)
                    refreshValues()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val label = entry.name
                    val newGoal = slidePendingGoal
                    val changed = newGoal != slideStartGoal
                    slideLabel = null
                    binding.rowsList.requestDisallowInterceptTouchEvent(false)
                    // No performClick here: only a long-press reaches this
                    // path, and a release after one shouldn't also open the
                    // popup that a plain tap opens.
                    if (changed) commitGoal(label, newGoal) else refreshValues()
                    true
                }
                else -> false
            }
        }
    }

    /**
     * Five minutes per step of travel, in both sliders.
     *
     * An accelerating ladder made the value hard to predict mid-drag: you had
     * to remember how far you'd come to know what the next step would add.
     * A flat rate is slower over long distances and much easier to aim.
     */
    private fun stepDelta(steps: Int): Int = steps * 5

    private fun commitGoal(label: String, minutes: Int) {
        val library = LabelStore.readLibrary(this).map { e ->
            if (e.name == label) e.copy(goalMinutes = minutes.coerceAtLeast(0)) else e
        }
        if (!LabelStore.writeLibrary(this, library)) {
            Toast.makeText(this, "Couldn't save the goal", Toast.LENGTH_LONG).show()
            return
        }
        // Always rebuild, not just for goal-dependent orders. `displayed` is
        // a cached copy of the library, so refreshValues alone would keep
        // binding the old goal — the value was saved but the row didn't show
        // it. rebuild re-reads the library, and re-sorts when the order
        // depends on goals.
        rebuild()
        syncService()
    }

    // ---- per-label popup ---------------------------------------------------

    /**
     * Everything about one label's numbers, in one place: its daily goal and
     * the time recorded against it today. Opened by long-pressing a row.
     *
     * Goal changes are saved to the library; time changes apply immediately
     * and are written to the log, since a correction to recorded time isn't a
     * setting.
     */
    private fun showLabelPopup(label: String) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_label_edit, null)
        val nameView = view.findViewById<TextView>(R.id.editName)
        val goalView = view.findViewById<TextView>(R.id.editGoal)
        val todayView = view.findViewById<TextView>(R.id.editToday)
        val dialog = AlertDialog.Builder(this).setView(view).create()

        fun refresh() {
            val entry = LabelStore.readLibrary(this).firstOrNull { it.name == label }
            val goal = entry?.goalMinutes ?: 0
            nameView.text = label
            // Both in h:mm. The goal was minutes and today's time carried
            // seconds, so two figures a line apart read in different units.
            goalView.text = "goal  ${if (goal > 0) fmtGoal(goal) else "0:00"}"
            todayView.text = "today ${fmtMeasuredMs(TimerStore.getDayMs(this, label))}"
        }
        refresh()

        fun changeGoal(delta: Int) {
            val library = LabelStore.readLibrary(this).map { e ->
                if (e.name == label) e.copy(goalMinutes = (e.goalMinutes + delta).coerceAtLeast(0))
                else e
            }
            if (!LabelStore.writeLibrary(this, library)) {
                Toast.makeText(this, "Couldn't save the goal", Toast.LENGTH_LONG).show()
                return
            }
            refresh(); rebuild()
        }

        fun changeTime(delta: Int) {
            TimerStore.adjust(this, label, delta)
            refresh(); refreshValues()
        }

        view.findViewById<Button>(R.id.eG1).setOnClickListener { changeGoal(1) }
        view.findViewById<Button>(R.id.eG5).setOnClickListener { changeGoal(5) }
        view.findViewById<Button>(R.id.eG15).setOnClickListener { changeGoal(15) }
        view.findViewById<Button>(R.id.eG60).setOnClickListener { changeGoal(60) }
        view.findViewById<Button>(R.id.eGm1).setOnClickListener { changeGoal(-1) }
        view.findViewById<Button>(R.id.eGm5).setOnClickListener { changeGoal(-5) }
        view.findViewById<Button>(R.id.eGm15).setOnClickListener { changeGoal(-15) }
        view.findViewById<Button>(R.id.eGm60).setOnClickListener { changeGoal(-60) }

        view.findViewById<Button>(R.id.eTm15).setOnClickListener { changeTime(-15) }
        view.findViewById<Button>(R.id.eTm5).setOnClickListener { changeTime(-5) }
        view.findViewById<Button>(R.id.eT5).setOnClickListener { changeTime(5) }
        view.findViewById<Button>(R.id.eT15).setOnClickListener { changeTime(15) }

        view.findViewById<Button>(R.id.eDelete).setOnClickListener {
            dialog.dismiss(); confirmDeleteLabel(label)
        }
        view.findViewById<Button>(R.id.eDone).setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    private fun confirmDeleteLabel(label: String) {
        AlertDialog.Builder(this)
            .setTitle("Delete \u201c$label\u201d?")
            .setMessage("Removes it from the list. Time already logged against it stays in the CSV and can still be seen in exports.")
            .setPositiveButton("Delete") { _, _ ->
                if (TimerStore.getActiveLabel(this) == label) TimerStore.stop(this)
                val library = LabelStore.readLibrary(this).filter { it.name != label }
                LabelStore.writeLibrary(this, library)
                rebuild(); syncService()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** New label, straight from the bottom of the list. */
    private fun promptNewLabel() {
        textInputDialog(title = "New label", hint = "Label name") { name, _ ->
            if (name.isEmpty()) return@textInputDialog
            if (name.contains(',')) {
                Toast.makeText(this, "Labels can't contain a comma", Toast.LENGTH_SHORT).show()
                return@textInputDialog
            }
            val library = LabelStore.readLibrary(this)
            if (library.any { it.name.equals(name, ignoreCase = true) }) {
                Toast.makeText(this, "That label already exists", Toast.LENGTH_SHORT).show()
                return@textInputDialog
            }
            LabelStore.writeLibrary(this, library + LabelEntry(name, 0))
            rebuild()
            showLabelPopup(name)   // straight into setting its goal
        }
    }

    // ---- ordering ----------------------------------------------------------

    /**
     * One list rather than radio buttons plus dialog buttons: AlertDialog only
     * offers three buttons, and there are now three actions alongside the four
     * orderings. The header button already shows the current mode.
     */
    private fun showSortPicker() {
        val mode = SettingsStore.getSortMode(this)
        val items = SettingsStore.SORT_NAMES.mapIndexed { i, name ->
            if (i == mode) "\u2713  $name" else "     $name"
        }.toTypedArray() + arrayOf(
            "\u2500\u2500  Use current order as manual",
            "\u2500\u2500  Save current as layout\u2026",
            "\u2500\u2500  Open a layout\u2026"
        )

        AlertDialog.Builder(this)
            .setTitle("Order timers")
            .setItems(items) { _, which ->
                when (which) {
                    in 0..3 -> {
                        SettingsStore.setSortMode(this, which)
                        rebuild()
                    }
                    4 -> seedManualOrder()
                    5 -> promptSaveLayout()
                    6 -> showLayoutPicker()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- named layouts -----------------------------------------------------

    /** Saves the current order and visibility under a name. */
    private fun promptSaveLayout() {
        textInputDialog(
            title = "Save current arrangement",
            hint = "Layout name"
        ) { name, _ ->
            if (name.isEmpty()) return@textInputDialog
            val library = LabelStore.readLibrary(this)

            fun doSave() {
                if (LayoutStore.save(this, name, library))
                    Toast.makeText(this, "Saved \u201c$name\u201d", Toast.LENGTH_SHORT).show()
                else
                    Toast.makeText(this, "Couldn't save that layout", Toast.LENGTH_LONG).show()
            }

            if (LayoutStore.exists(this, name)) {
                AlertDialog.Builder(this)
                    .setTitle("Replace \u201c$name\u201d?")
                    .setMessage("A layout with that name already exists.")
                    .setPositiveButton("Replace") { _, _ -> doSave() }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else doSave()
        }
    }

    /** Searchable list of saved layouts; long-press to rename or delete. */
    private fun showLayoutPicker() {
        val all = LayoutStore.readAll(this)
        if (all.isEmpty()) {
            Toast.makeText(this, "No layouts saved yet", Toast.LENGTH_SHORT).show()
            return
        }

        val view = LayoutInflater.from(this).inflate(R.layout.dialog_layout_picker, null)
        val filter = view.findViewById<EditText>(R.id.layoutFilter)
        val list = view.findViewById<LinearLayout>(R.id.layoutList)
        val dialog = AlertDialog.Builder(this).setView(view).create()

        fun render(query: String) {
            list.removeAllViews()
            val inflater = LayoutInflater.from(this)
            val matches = all.filter { it.name.contains(query.trim(), ignoreCase = true) }

            if (matches.isEmpty()) {
                list.addView(TextView(this).apply {
                    text = "No layouts match \u201c$query\u201d"
                    setTextColor(0xFF5C736E.toInt())
                    textSize = 13f
                    setPadding(8, 16, 8, 16)
                })
                return
            }

            matches.forEach { layout ->
                val row = inflater.inflate(R.layout.row_layout_entry, list, false)
                row.findViewById<TextView>(R.id.layoutName).text = layout.name
                val goalMins = layout.entries.fold(0) { a, e -> a + e.goalMinutes }
                val goalText = if (goalMins > 0)
                    "  \u00b7  ${goalMins / 60}:${String.format("%02d", goalMins % 60)} of goals" else ""
                row.findViewById<TextView>(R.id.layoutSummary).text =
                    "${layout.entries.size} labels$goalText"
                row.setOnClickListener { dialog.dismiss(); applyLayout(layout) }
                row.setOnLongClickListener {
                    dialog.dismiss(); manageLayout(layout); true
                }
                list.addView(row)
            }
        }

        filter.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { render(s?.toString() ?: "") }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        render("")
        dialog.show()
    }

    private fun manageLayout(layout: SavedLayout) {
        AlertDialog.Builder(this)
            .setTitle(layout.name)
            .setItems(arrayOf("Rename", "Delete")) { _, which ->
                if (which == 0) promptRenameLayout(layout) else confirmDeleteLayout(layout)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptRenameLayout(layout: SavedLayout) {
        textInputDialog(
            title = "Rename layout",
            initial = layout.name,
            hint = "Layout name"
        ) { name, _ ->
            if (name.isNotEmpty()) LayoutStore.rename(this, layout.name, name)
        }
    }

    private fun confirmDeleteLayout(layout: SavedLayout) {
        AlertDialog.Builder(this)
            .setTitle("Delete \u201c${layout.name}\u201d?")
            .setMessage("Only the saved arrangement is removed. Your labels, goals and logged time are untouched.")
            .setPositiveButton("Delete") { _, _ -> LayoutStore.delete(this, layout.name) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Applies a layout, reconciling it against labels added or deleted since. */
    private fun applyLayout(layout: SavedLayout) {
        val library = LabelStore.readLibrary(this)
        val (updated, diff) = LayoutStore.apply(this, layout, library)

        if (!LabelStore.writeLibrary(this, updated)) {
            Toast.makeText(this, "Couldn't apply that layout", Toast.LENGTH_LONG).show()
            return
        }
        SettingsStore.setSortMode(this, SettingsStore.SORT_MANUAL)
        rebuild()
        showDiffBanner(layout.name, diff)
    }

    private fun showDiffBanner(layoutName: String, diff: LayoutDiff) {
        if (diff.isEmpty) {
            binding.diffBanner.visibility = View.GONE
            return
        }
        val parts = mutableListOf<String>()
        if (diff.added.isNotEmpty()) parts.add("Added: ${diff.added.joinToString(", ")}")
        if (diff.removed.isNotEmpty()) parts.add("Removed: ${diff.removed.joinToString(", ")}")
        binding.diffText.text = "\u201c$layoutName\u201d  \u00b7  ${parts.joinToString("  \u00b7  ")}"
        binding.diffBanner.visibility = View.VISIBLE
    }

    /**
     * Writes the order currently on screen into the manual positions and
     * switches to Manual. Confirms first if a manual arrangement already
     * exists, since this replaces it with no undo.
     */
    private fun seedManualOrder() {
        val library = LabelStore.readLibrary(this)
        val hasExisting = library.any { it.manualOrder != UNPLACED }

        fun apply() {
            // On-screen labels in their displayed order, then anything else
            // appended in its existing order.
            val shown = displayed.map { it.name }
            val rest = library.map { it.name }.filter { !shown.contains(it) }
            val updated = LabelStore.applyManualOrder(library, shown + rest)
            LabelStore.writeLibrary(this, updated)
            SettingsStore.setSortMode(this, SettingsStore.SORT_MANUAL)
            rebuild()
        }

        if (!hasExisting) { apply(); return }

        AlertDialog.Builder(this)
            .setTitle("Replace your manual order?")
            .setMessage("This overwrites the arrangement you built by hand. It can't be undone.")
            .setPositiveButton("Replace") { _, _ -> apply() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * In Manual mode, tapping a label lets you pick another one, which then
     * moves to sit just above the row you tapped. In the sorted modes the
     * position is computed, so rearranging by hand isn't meaningful.
     */
    private fun onLabelTapped(position: Int) {
        if (SettingsStore.getSortMode(this) != SettingsStore.SORT_MANUAL) {
            Toast.makeText(this, "Switch to Manual order to rearrange", Toast.LENGTH_SHORT).show()
            return
        }
        val moving = displayed.getOrNull(position) ?: return

        // The tapped label is the one that moves. Tapping something implies
        // acting on it, so "move this above that" is the natural reading.
        val choices = LabelStore.readLibrary(this)
            .filter { it.name != moving.name }
            .sortedWith(compareBy<LabelEntry> { it.manualOrder }.thenBy { it.name.lowercase() })
        if (choices.isEmpty()) return

        val display = choices.map { e ->
            if (e.goalMinutes > 0) "${e.name}   \u2014   ${fmtGoal(e.goalMinutes)}" else e.name
        }.toTypedArray() + arrayOf("\u2500\u2500  Move to the end")

        AlertDialog.Builder(this)
            .setTitle("Move \u201c${moving.name}\u201d above\u2026")
            .setItems(display) { _, which ->
                if (which == choices.size) moveToEnd(moving.name)
                else moveAbove(moving.name, choices[which].name)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Sends [moving] to the bottom, since there's no row to sit above. */
    private fun moveToEnd(moving: String) {
        val library = LabelStore.readLibrary(this)
        val ordered = library.sortedWith(
            compareBy<LabelEntry> { it.manualOrder }.thenBy { it.name.lowercase() }
        ).map { it.name }.toMutableList()
        ordered.remove(moving)
        ordered.add(moving)
        if (!LabelStore.writeLibrary(this, LabelStore.applyManualOrder(library, ordered))) {
            Toast.makeText(this, "Couldn't save the new order", Toast.LENGTH_LONG).show()
            return
        }
        rebuild(); syncService()
    }

    /** Moves [moving] to immediately above [target], shifting the rest down. */
    private fun moveAbove(moving: String, target: String) {
        val library = LabelStore.readLibrary(this).toMutableList()

        val ordered = library.sortedWith(
            compareBy<LabelEntry> { it.manualOrder }.thenBy { it.name.lowercase() }
        ).map { it.name }.toMutableList()

        ordered.remove(moving)
        val at = ordered.indexOf(target)
        if (at < 0) ordered.add(moving) else ordered.add(at, moving)

        if (!LabelStore.writeLibrary(this, LabelStore.applyManualOrder(library, ordered))) {
            Toast.makeText(this, "Couldn't save the new order", Toast.LENGTH_LONG).show()
            return
        }
        rebuild(); syncService()
    }

    // ---- adjustments -------------------------------------------------------


    // ---- notes / exit ------------------------------------------------------

    private fun offerNote() {
        if (!SettingsStore.isPromptNote(this)) return
        binding.editNote.setText("")
        binding.noteBar.visibility = View.VISIBLE
    }

    private fun confirmDone() {
        AlertDialog.Builder(this)
            .setTitle("Done for now?")
            .setMessage("Stops the running timer, saves it to the log, and closes this session. Day totals stay until midnight.")
            .setPositiveButton("Done") { _, _ ->
                TimerStore.stop(this)
                TimerStore.endSession(this)
                AppState.processTouched = false
                startService(Intent(this, TimerForegroundService::class.java).apply {
                    action = TimerForegroundService.ACTION_STOP
                })
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun syncService() {
        val intent = Intent(this, TimerForegroundService::class.java)
        if (TimerStore.isRunning(this)) {
            intent.action = TimerForegroundService.ACTION_REFRESH
            ContextCompat.startForegroundService(this, intent)
        } else {
            intent.action = TimerForegroundService.ACTION_STOP
            startService(intent)
        }
    }

    // ---- rendering ---------------------------------------------------------

    private fun fmtGoal(minutes: Int): String {
        if (minutes <= 0) return ""
        val h = minutes / 60
        val m = minutes % 60
        return if (h > 0) String.format("%d:%02d", h, m) else String.format("0:%02d", m)
    }

    /**
     * Fills the row background to show progress against the daily goal.
     *
     * Up to 100% the goal sits at the right edge (a fixed landmark), so the
     * green fraction is elapsed/goal. Past 100% the row represents the total
     * elapsed time, so the goal mark slides left and the overage shows in
     * burnt red, proportionate to how far over you are.
     */
    private fun drawBar(h: TimerAdapter.Holder, elapsedMs: Long, goalMinutes: Int) {
        val bp = h.barProgress ?: return
        val bo = h.barOver ?: return
        val br = h.barRest ?: return

        fun setWeights(progress: Float, over: Float, rest: Float) {
            (bp.layoutParams as LinearLayout.LayoutParams).weight = progress
            (bo.layoutParams as LinearLayout.LayoutParams).weight = over
            (br.layoutParams as LinearLayout.LayoutParams).weight = rest
            bp.requestLayout(); bo.requestLayout(); br.requestLayout()
        }

        if (goalMinutes <= 0 || elapsedMs <= 0L) { setWeights(0f, 0f, 1f); return }

        val goalMs = goalMinutes * 60_000L
        if (elapsedMs <= goalMs) {
            val frac = (elapsedMs.toDouble() / goalMs).toFloat().coerceIn(0f, 1f)
            setWeights(frac, 0f, 1f - frac)
        } else {
            val goalFrac = (goalMs.toDouble() / elapsedMs).toFloat().coerceIn(0f, 1f)
            setWeights(goalFrac, 1f - goalFrac, 0f)
        }
    }

    /** Header, pills and Stop state — everything except the per-row values. */
    private fun updateChrome() {
        val running = TimerStore.isRunning(this)
        val isSession = sessionView()
        // The header now carries the day's planned start time rather than the
        // date — it's what the Start column projects from, and tapping it
        // opens the picker. The date is on the phone's status bar anyway.
        // Just the time: the word "Start" cost the width the fifth pill needed.
        // A dot in front when nothing is running — the header is the only
        // thing on screen no matter where the list is scrolled.
        binding.tvHeader.text =
            if (running) fmtClock(dayStartMs()) else "\u25cf ${fmtClock(dayStartMs())}"
        binding.tvHeader.setTextColor(if (running) muted else overRed)

        binding.btnViewDay.setBackgroundResource(
            if (isSession) R.drawable.bg_pill_off else R.drawable.bg_pill_on
        )
        binding.btnViewSession.setBackgroundResource(
            if (isSession) R.drawable.bg_pill_on else R.drawable.bg_pill_off
        )
        binding.btnSort.text = SettingsStore.SORT_SHORT[SettingsStore.getSortMode(this)]
        binding.btnViewDay.setTextColor(if (isSession) muted else amber)
        binding.btnViewSession.setTextColor(if (isSession) blueGrey else muted)

        val secondary = SettingsStore.getSecondaryMode(this)
        binding.btnCum2.text =
            if (secondary == SettingsStore.COL_NONE) "\u2014"
            else SettingsStore.COLUMN_SHORT[secondary]
        binding.btnCum2.setBackgroundResource(
            if (secondary == SettingsStore.COL_NONE) R.drawable.bg_pill_off
            else R.drawable.bg_pill_on
        )
        binding.btnCum2.setTextColor(
            when (secondary) {
                SettingsStore.COL_NONE -> muted
                SettingsStore.COL_REMAIN, SettingsStore.COL_SUM_REMAIN -> colRemain
                SettingsStore.COL_START, SettingsStore.COL_ETA -> colClock
                else -> colGoal
            }
        )

        val colMode = SettingsStore.getColumnMode(this)
        binding.btnCum.text = SettingsStore.COLUMN_SHORT[colMode]
        binding.btnCum.setBackgroundResource(R.drawable.bg_pill_on)
        binding.btnCum.setTextColor(
            when (colMode) {
                SettingsStore.COL_REMAIN, SettingsStore.COL_SUM_REMAIN -> colRemain
                SettingsStore.COL_START, SettingsStore.COL_ETA -> colClock
                else -> colGoal
            }
        )

        binding.btnStop.isEnabled = running
        binding.btnStop.alpha = if (running) 1f else 0.4f
    }

    /**
     * The figure for one column mode on one row: its text and its colour.
     *
     * Shared by the right-hand column and the smaller line under the label, so
     * the two can't drift apart in how they format or colour a value.
     */
    private fun columnFigure(
        mode: Int,
        entry: LabelEntry,
        position: Int,
        remainingMs: Long
    ): Pair<String, Int> {
        val isSum = mode == SettingsStore.COL_SUM_GOAL || mode == SettingsStore.COL_SUM_REMAIN
        val isClock = mode == SettingsStore.COL_START || mode == SettingsStore.COL_ETA

        if (isClock) {
            // No goal means no place in the plan, so no projected time.
            if (entry.goalMinutes <= 0) return "" to goalGrey

            val before = if (mode == SettingsStore.COL_ETA) remBefore else goalBefore
            val base = if (mode == SettingsStore.COL_START) dayStartMs()
                       else System.currentTimeMillis()
            val projected = base + (before.getOrNull(position) ?: 0L)
            val mark = if (mode == SettingsStore.COL_START) "@" else "~"

            // A Start time already passed says the plan has slipped.
            val colour = if (mode == SettingsStore.COL_START &&
                             projected < System.currentTimeMillis()) goalGrey else colClock
            return "$mark${fmtClock(projected)}" to colour
        }

        if (isSum) {
            val inc = if (mode == SettingsStore.COL_SUM_REMAIN) remInc else goalInc
            val running = inc.getOrNull(position) ?: 0L
            val colour = if (mode == SettingsStore.COL_SUM_REMAIN) colRemain else colGoal
            // Measured formatter here too: a running total under a minute is
            // still a real total, and the blanking one would leave a lone
            // sigma with nothing after it.
            return (if (running > 0L) "\u03a3${fmtMeasuredMs(running)}" else "") to colour
        }

        if (entry.goalMinutes <= 0) return "" to goalGrey

        return if (mode == SettingsStore.COL_REMAIN) {
            when {
                // Past the goal by a minute or more: how far past.
                remainingMs <= -60_000L -> "-${fmtMeasuredMs(-remainingMs)}" to colOver
                // Past it by less than a minute. No sign — "-0:00" reads as a
                // mistake, and the colour already says you're over.
                remainingMs <= 0L -> "0:00" to colOver
                else -> fmtMeasuredMs(remainingMs) to colRemain
            }
        } else {
            fmtGoal(entry.goalMinutes) to colGoal
        }
    }

    /** Picks what the right-hand column shows, independent of the sort order. */
    private fun showColumnPicker() {
        AlertDialog.Builder(this)
            .setTitle("Right-hand column")
            .setSingleChoiceItems(
                SettingsStore.COLUMN_NAMES, SettingsStore.getColumnMode(this)
            ) { dialog, which ->
                SettingsStore.setColumnMode(this, which)
                dialog.dismiss()
                refreshValues(); updateChrome()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Picks the smaller second figure under the label, or turns it off. */
    private fun showSecondaryPicker() {
        val current = SettingsStore.getSecondaryMode(this)
        val checked = if (current == SettingsStore.COL_NONE) 0 else current + 1
        AlertDialog.Builder(this)
            .setTitle("Second figure under the label")
            .setSingleChoiceItems(SettingsStore.SECONDARY_NAMES, checked) { dialog, which ->
                SettingsStore.setSecondaryMode(
                    this, if (which == 0) SettingsStore.COL_NONE else which - 1
                )
                dialog.dismiss()
                // Re-measure, not just rebuild: the row's minimum height
                // depends on whether a second line is showing.
                measureAndRebuild()
                updateChrome()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Running total down the rows, in the order they're displayed.
     *
     * With Remain sorting it accumulates time still to do, each label floored
     * at zero so one past its goal doesn't cancel out a shortfall below it.
     * Otherwise it accumulates goals. Labels with no goal add nothing, so the
     * total simply repeats on those rows.
     */
    /**
     * Running totals on both bases, since the primary and secondary columns
     * can want different ones. Cheap: two passes over a list of ten.
     */
    private fun computeCumulative() {
        fun sums(useRemaining: Boolean): Pair<List<Long>, List<Long>> {
            val inc = mutableListOf<Long>()
            val before = mutableListOf<Long>()
            var running = 0L
            displayed.forEach { e ->
                before.add(running)      // everything above this row
                running += when {
                    e.goalMinutes <= 0 -> 0L
                    useRemaining ->
                        TimerStore.getRemainingMs(this, e.name, e.goalMinutes).coerceAtLeast(0L)
                    else -> e.goalMinutes * 60_000L
                }
                inc.add(running)         // including this row
            }
            return inc to before
        }
        val g = sums(false)
        val r = sums(true)
        goalInc = g.first;   goalBefore = g.second
        remInc = r.first;    remBefore = r.second
    }

    /** Today's planned start, as a timestamp. */
    private fun dayStartMs(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, SettingsStore.getStartHour(this@MainActivity))
        set(Calendar.MINUTE, SettingsStore.getStartMinute(this@MainActivity))
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /**
     * Clock time in a compact form: "6:00a", "2:30p". The full "6:00 AM" is
     * too wide for the column, and 24-hour would read oddly beside the
     * durations.
     */
    private fun fmtClock(ms: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = ms }
        val h24 = c.get(Calendar.HOUR_OF_DAY)
        val h = if (h24 % 12 == 0) 12 else h24 % 12
        val suffix = if (h24 < 12) "a" else "p"
        return String.format("%d:%02d%s", h, c.get(Calendar.MINUTE), suffix)
    }

    /** Tapping the header sets the time the Start column plans from. */
    private fun showStartTimePicker() {
        TimePickerDialog(
            this,
            { _, hour, minute ->
                SettingsStore.setStartTime(this, hour, minute)
                refreshValues(); updateChrome()
            },
            SettingsStore.getStartHour(this),
            SettingsStore.getStartMinute(this),
            DateFormat.is24HourFormat(this)
        ).show()
    }

    /**
     * The two totals beneath the columns they sum.
     *
     * Middle — total time recorded across every label, following the
     * Day/Session toggle. Shown as-is, negatives included: it's real recorded
     * time and an adjustment that took a label below zero should be visible.
     *
     * Right — total goal, or total remaining in Remain mode, matching whatever
     * the column above is showing. Each label floors at zero there, so one
     * past its goal never masks a shortfall elsewhere. Unaffected by the Cum
     * pill: with a running total above, the last row already equals this
     * figure, and blanking it would look like a fault.
     */
    /**
     * The three totals beneath the columns they sum, each matching what the
     * column above it shows.
     */
    private fun updateTotals(isSession: Boolean) {
        val library = LabelStore.readLibrary(this)

        // Middle \u2014 total time recorded, following the Day/Session toggle.
        // Shown as-is, negatives included: it's real recorded time and an
        // adjustment that took a label below zero should be visible.
        val elapsedMs = library.fold(0L) { acc, e ->
            acc + if (isSession) TimerStore.getSessionMs(this, e.name)
                  else TimerStore.getDayMs(this, e.name)
        }
        binding.tvTotalElapsed.text = if (elapsedMs != 0L) fmtHm(elapsedMs) else ""

        val (goalText, goalColour) = totalFor(SettingsStore.getColumnMode(this), library)
        binding.tvTotalGoal.text = goalText
        binding.tvTotalGoal.setTextColor(goalColour)

        // Left \u2014 the same figure for whatever the second line under each
        // label is showing. Blank when there is no second line.
        val secondary = SettingsStore.getSecondaryMode(this)
        if (secondary == SettingsStore.COL_NONE) {
            binding.tvTotalSub.text = ""
        } else {
            val (subText, subColour) = totalFor(secondary, library)
            binding.tvTotalSub.text = subText
            binding.tvTotalSub.setTextColor(subColour)
        }
    }

    /**
     * The total for a column mode, as text and colour.
     *
     * Durations sum. The clock modes don't sum \u2014 adding two times of day is
     * meaningless \u2014 so they show **when you would finish**: the day's start
     * plus every goal, or now plus everything still to do. That's the figure
     * the column is building toward on its last row.
     */
    private fun totalFor(mode: Int, library: List<LabelEntry>): Pair<String, Int> {
        val totalGoalMs = library.fold(0L) { a, e -> a + e.goalMinutes.toLong() } * 60_000L
        val totalRemainMs = library.fold(0L) { a, e ->
            a + if (e.goalMinutes <= 0) 0L
                else TimerStore.getRemainingMs(this, e.name, e.goalMinutes).coerceAtLeast(0L)
        }
        return when (mode) {
            SettingsStore.COL_REMAIN, SettingsStore.COL_SUM_REMAIN -> {
                val prefix = if (mode == SettingsStore.COL_SUM_REMAIN) "\u03a3" else ""
                val text = if (totalRemainMs > 0L) "$prefix${fmtHm(totalRemainMs)}" else "${prefix}0:00"
                text to (if (totalRemainMs <= 0L) colOver else colRemain)
            }
            SettingsStore.COL_START ->
                "@${fmtClock(dayStartMs() + totalGoalMs)}" to colClock
            SettingsStore.COL_ETA ->
                "~${fmtClock(System.currentTimeMillis() + totalRemainMs)}" to colClock
            else -> {
                val prefix = if (mode == SettingsStore.COL_SUM_GOAL) "\u03a3" else ""
                (if (totalGoalMs > 0L) "$prefix${fmtHm(totalGoalMs)}" else "") to colGoal
            }
        }
    }

    /** h:mm, no seconds. Handles negatives with a leading minus. */
    private fun fmtHm(ms: Long): String {
        val neg = ms < 0
        val mins = (Math.abs(ms) / 60_000L).toInt()
        val body = String.format("%d:%02d", mins / 60, mins % 60)
        return if (neg) "-$body" else body
    }

    /** Formats a duration in ms as h:mm, for goal-style figures. */
    /**
     * A measured duration as h:mm, rendering zero as "0:00".
     *
     * [fmtGoal] blanks zero, which is right for "this label has no goal" and
     * wrong for a measured value. 53 seconds past a goal is real and happens
     * to round down; blanking it left the row showing a lone minus sign, and
     * a sub-minute running total showed a lone sigma.
     */
    private fun fmtMeasuredMs(ms: Long): String {
        val mins = (ms / 60_000L).toInt()
        return if (mins <= 0) "0:00" else fmtGoal(mins)
    }

    /** Per-second refresh: values only, never the order. */
    private fun refreshValues() {
        // Fold accrued task time roughly once a minute rather than every
        // tick: it survives the process being killed without writing the
        // file sixty times a minute.
        val now = System.currentTimeMillis()
        if (now - lastTaskSettle > 60_000L) {
            TaskStore.settleDoing(this)
            lastTaskSettle = now
        }
        computeCumulative()
        updateTotals(sessionView())
        for (i in displayed.indices) {
            val holder = binding.rowsList.findViewHolderForAdapterPosition(i) as? TimerAdapter.Holder
            holder?.let { bindValues(it, displayed[i], i) }
        }
    }

    /**
     * Fills in everything that changes moment to moment. Called both when a
     * row is first bound and on every tick, so the two can't drift apart.
     */
    private fun bindValues(h: TimerAdapter.Holder, entry: LabelEntry, position: Int) {
        // The "+ New label" row has none of these views.
        val hLabel = h.label ?: return
        val hTime = h.time ?: return
        val hGoal = h.goal ?: return
        val hBorder = h.border ?: return
        val hNote = h.note ?: return

        val label = entry.name
        val isSession = sessionView()
        val isActive = label == TimerStore.getActiveLabel(this)
        val accent = if (isSession) blueGrey else amber

        // Both slide flags up front: the label, the timer and the right
        // column all read them, in that order.
        val timeSliding = timeSlideLabel == entry.name
        val goalSliding = slideLabel == entry.name

        // While the recorded time is being slid, the label line carries the
        // delta. The timer itself shows the new total and sits under the
        // finger, and using the second line instead would make a hidden line
        // appear and clip the row.
        if (timeSliding) {
            val d = timeSlidePendingMinutes
            hLabel.text = if (d == 0) label else (if (d > 0) "+$d min" else "$d min")
            hLabel.setTextColor(if (d == 0) 0xFFF1EDE3.toInt() else amber)
        } else {
            hLabel.text = label
            hLabel.setTextColor(0xFFF1EDE3.toInt())
        }

        // While the goal is being slid, the timer column — immediately left of
        // the figure and clear of the finger — shows how much is being added
        // or taken off. It goes back to the time on release.
        if (goalSliding) {
            val delta = slidePendingGoal - slideStartGoal
            hTime.text = if (delta == 0) "" else (if (delta > 0) "+$delta" else "$delta")
            hTime.setTextColor(amber)
        } else if (timeSliding) {
            hTime.text = TimerStore.formatDuration(
                (timeSlideStartMs + timeSlidePendingMinutes * 60_000L).coerceAtLeast(0L)
            )
            hTime.setTextColor(amber)
        } else {
            hTime.text = TimerStore.formatDuration(
                if (isSession) TimerStore.getSessionMs(this, label)
                else TimerStore.getDayMs(this, label)
            )
            hTime.setTextColor(if (isActive) accent else muted)
        }

        // Nothing running anywhere: tint the timer column only.
        //
        // v51 washed the whole row at 35% and it was a mistake — the wash sits
        // on top of the progress bars, so every row flattened to the same
        // olive and the green/red distinction disappeared. The always-visible
        // job is done by the dot in the header instead, which costs nothing.
        h.content?.setBackgroundColor(0x00000000)
        hTime.setBackgroundColor(
            if (TimerStore.isRunning(this)) 0x00000000 else idleWash
        )

        val remainingMs =
            if (entry.goalMinutes > 0) TimerStore.getRemainingMs(this, label, entry.goalMinutes)
            else 0L
        if (slideLabel == entry.name) {
            // Mid-slide the column keeps showing its own kind of figure,
            // recomputed against the pending goal — Rem counts down as the
            // goal grows, Goal shows the goal itself. Amber marks it as not
            // yet saved.
            //
            // Clock and sum modes don't change for the row being adjusted
            // (their value comes from the rows above it), so there'd be no
            // feedback at all; those fall back to showing the goal.
            val pending = entry.copy(goalMinutes = slidePendingGoal)
            val pendingRemaining =
                if (slidePendingGoal > 0)
                    slidePendingGoal * 60_000L - TimerStore.getDayMs(this, entry.name)
                else 0L
            val mode = SettingsStore.getColumnMode(this)
            val (previewText, _) = columnFigure(mode, pending, position, pendingRemaining)
            val (currentText, _) = columnFigure(mode, entry, position, remainingMs)

            hGoal.text =
                if (previewText.isNotEmpty() && previewText != currentText) previewText
                else fmtGoal(slidePendingGoal).ifEmpty { "0:00" }
            hGoal.setTextColor(amber)
        } else {
            val (goalText, goalColour) = columnFigure(
                SettingsStore.getColumnMode(this), entry, position, remainingMs
            )
            hGoal.text = goalText
            hGoal.setTextColor(goalColour)
        }

        // Second, smaller figure under the label when one is chosen.
        h.sub?.let { hSub ->
            val secondary = SettingsStore.getSecondaryMode(this)
            if (secondary == SettingsStore.COL_NONE) {
                hSub.visibility = View.GONE     // label recentres on its own
            } else {
                val (subText, subColour) = columnFigure(secondary, entry, position, remainingMs)
                hSub.text = subText
                hSub.setTextColor(subColour)
                // Stays in the layout even when the value is empty, so the
                // label doesn't sit at a different height row to row.
                hSub.visibility = View.VISIBLE
            }
        }

        hBorder.setBackgroundResource(
            if (isActive) R.drawable.bg_row_active else R.drawable.bg_row_idle
        )
        hNote.setColorFilter(
            if (labelsWithNotes.contains(label)) amber else 0xFF5C736E.toInt()
        )

        renderTasks(h, entry)

        // Progress is always against the DAY total, since goals are daily.
        drawBar(h, TimerStore.getDayMs(this, label), entry.goalMinutes)
    }

    /** Adapter over [displayed]; the list itself stays owned by the activity. */
    inner class TimerAdapter : RecyclerView.Adapter<TimerAdapter.Holder>() {

        private val TYPE_TIMER = 0
        private val TYPE_ADD = 1

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            init {
                // Once an hour is reached the string grows from "09:07" to
                // "1:09:07" and overflowed a fixed size. Auto-sizing shrinks
                // only the rows that need it, so short timers stay large.
                view.findViewById<TextView>(R.id.rowTime)?.let {
                    TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                        it, 20, 32, 1, TypedValue.COMPLEX_UNIT_SP
                    )
                }
            }

            // Null on the "+ New label" row, which has none of these.
            val label: TextView? = view.findViewById(R.id.rowLabel)
            val sub: TextView? = view.findViewById(R.id.rowSub)
            val content: View? = view.findViewById(R.id.rowContent)
            val columns: View? = view.findViewById(R.id.rowColumns)
            val taskBox: View? = view.findViewById(R.id.rowTaskBox)
            val tasks: TextView? = view.findViewById(R.id.rowTasks)
            val taskEdit: ImageView? = view.findViewById(R.id.rowTaskEdit)
            val labelBox: View? = view.findViewById(R.id.rowLabelBox)
            val time: TextView? = view.findViewById(R.id.rowTime)
            val goal: TextView? = view.findViewById(R.id.rowGoal)
            val border: View? = view.findViewById(R.id.rowBorder)
            val note: ImageView? = view.findViewById(R.id.rowNote)
            val barProgress: View? = view.findViewById(R.id.barProgress)
            val barOver: View? = view.findViewById(R.id.barOver)
            val barRest: View? = view.findViewById(R.id.barRest)
        }

        override fun getItemViewType(position: Int): Int =
            if (position < displayed.size) TYPE_TIMER else TYPE_ADD

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            if (viewType == TYPE_ADD) {
                val add = LayoutInflater.from(parent.context)
                    .inflate(R.layout.row_add_label, parent, false)
                add.setOnClickListener { promptNewLabel() }
                return Holder(add)
            }
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.row_timer, parent, false)
            // Height is set per bind, not here: the running row grows to fit
            // its tasks while every other row stays fixed.
            return Holder(view)
        }

        override fun getItemCount(): Int = displayed.size + 1   // + the add row

        override fun onBindViewHolder(holder: Holder, position: Int) {
            if (getItemViewType(position) == TYPE_ADD) return
            val entry = displayed.getOrNull(position) ?: return

            // Zone 3 — the timer. Tap toggles: start if idle, stop if running.
            holder.time?.setOnClickListener { toggleTimer(entry.name) }
            attachTimeSlider(holder, entry)
            // Gaps between zones fall through to the row; same behaviour.
            holder.itemView.setOnClickListener { toggleTimer(entry.name) }

            // Zone 2 — the label. Tap moves it above another; long-press drags,
            // the same as the note icon.
            (holder.labelBox ?: holder.label)?.setOnClickListener {
                onLabelTapped(holder.bindingAdapterPosition)
            }
            (holder.labelBox ?: holder.label)?.setOnLongClickListener {
                beginDrag(holder); true
            }

            // Zone 1 — the note icon.
            holder.note?.setOnClickListener {
                startActivity(
                    Intent(this@MainActivity, NotesActivity::class.java)
                        .putExtra(NotesActivity.EXTRA_LABEL, entry.name)
                )
            }
            holder.note?.setOnLongClickListener { beginDrag(holder); true }

            // Zone 4 — the right column. Tap opens the popup; long-press then
            // slide adjusts the goal.
            holder.goal?.setOnClickListener { showLabelPopup(entry.name) }
            attachGoalSlider(holder, entry)

            bindValues(holder, entry, position)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!TimerStore.hasOpenSession(this)) TimerStore.beginNewSession(this)
        // Row height is derived from the measured list, so wait for layout.
        if (binding.rowsList.height > 0) measureAndRebuild()
        else binding.rowsList.post { measureAndRebuild() }
        handler.post(tickRunnable)
        syncService()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tickRunnable)
    }
}
