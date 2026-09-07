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
import android.view.LayoutInflater
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
    private var rowHeightPx = 0

    /**
     * Running totals down [displayed], one per row, recomputed whenever the
     * values change. Cached rather than summed per row so a freshly scrolled
     * row gets the same figure as one that was already on screen.
     */
    private var cumulative = listOf<Long>()

    /** Same, but excluding the row itself — what the clock modes need. */
    private var cumulativeBefore = listOf<Long>()


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
        binding.btnPlus5.setOnClickListener { adjustActive(5) }
        binding.btnMinus5.setOnClickListener { adjustActive(-5) }
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
        binding.btnCum.setOnClickListener { showColumnPicker() }
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
        rowHeightPx = if (available > 0)
            ((available / 10) - (3 * d)).toInt().coerceIn((44 * d).toInt(), (64 * d).toInt())
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
        // Before notifying: onBindViewHolder reads the cumulative list, so it
        // has to match the new displayed list rather than the previous one.
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
            goalView.text = "goal  ${if (goal > 0) fmtGoal(goal) else "0:00"}"
            todayView.text = "today ${TimerStore.formatDuration(TimerStore.getDayMs(this, label))}"
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
        view.findViewById<Button>(R.id.eG30).setOnClickListener { changeGoal(30) }
        view.findViewById<Button>(R.id.eG60).setOnClickListener { changeGoal(60) }
        view.findViewById<Button>(R.id.eGm1).setOnClickListener { changeGoal(-1) }
        view.findViewById<Button>(R.id.eGm5).setOnClickListener { changeGoal(-5) }
        view.findViewById<Button>(R.id.eGm15).setOnClickListener { changeGoal(-15) }
        view.findViewById<Button>(R.id.eGm30).setOnClickListener { changeGoal(-30) }
        view.findViewById<Button>(R.id.eGm60).setOnClickListener { changeGoal(-60) }

        view.findViewById<Button>(R.id.eTm15).setOnClickListener { changeTime(-15) }
        view.findViewById<Button>(R.id.eTm5).setOnClickListener { changeTime(-5) }
        view.findViewById<Button>(R.id.eT5).setOnClickListener { changeTime(5) }
        view.findViewById<Button>(R.id.eT15).setOnClickListener { changeTime(15) }

        view.findViewById<Button>(R.id.eDelete).setOnClickListener {
            dialog.dismiss(); confirmDeleteLabel(label)
        }

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
        val input = EditText(this).apply {
            hint = "Label name"
            setTextColor(0xFFF1EDE3.toInt())
            setHintTextColor(0xFF5C736E.toInt())
            setBackgroundResource(R.drawable.bg_row_track)
            setPadding(28, 24, 28, 24)
            textSize = 15f
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("New label")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                if (name.contains(',')) {
                    Toast.makeText(this, "Labels can't contain a comma", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val library = LabelStore.readLibrary(this)
                if (library.any { it.name.equals(name, ignoreCase = true) }) {
                    Toast.makeText(this, "That label already exists", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                LabelStore.writeLibrary(this, library + LabelEntry(name, 0))
                rebuild()
                showLabelPopup(name)   // straight into setting its goal
            }
            .setNegativeButton("Cancel", null)
            .show()
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
        val input = EditText(this).apply {
            hint = "Layout name"
            setTextColor(0xFFF1EDE3.toInt())
            setHintTextColor(0xFF5C736E.toInt())
            setBackgroundResource(R.drawable.bg_row_track)
            setPadding(28, 24, 28, 24)
            textSize = 15f
            setSingleLine(true)
        }

        AlertDialog.Builder(this)
            .setTitle("Save current arrangement")
            .setMessage("Stores which labels are shown, in what order, and their daily goals.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton

                val library = LabelStore.readLibrary(this)
                val existing = LayoutStore.exists(this, name)

                fun doSave() {
                    if (LayoutStore.save(this, name, library))
                        Toast.makeText(this, "Saved \u201c$name\u201d", Toast.LENGTH_SHORT).show()
                    else
                        Toast.makeText(this, "Couldn't save that layout", Toast.LENGTH_LONG).show()
                }

                if (existing) {
                    AlertDialog.Builder(this)
                        .setTitle("Replace \u201c$name\u201d?")
                        .setMessage("A layout with that name already exists.")
                        .setPositiveButton("Replace") { _, _ -> doSave() }
                        .setNegativeButton("Cancel", null)
                        .show()
                } else doSave()
            }
            .setNegativeButton("Cancel", null)
            .show()
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
        val input = EditText(this).apply {
            setText(layout.name)
            setTextColor(0xFFF1EDE3.toInt())
            setBackgroundResource(R.drawable.bg_row_track)
            setPadding(28, 24, 28, 24)
            textSize = 15f
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Rename layout")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                LayoutStore.rename(this, layout.name, input.text.toString())
            }
            .setNegativeButton("Cancel", null)
            .show()
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

    private fun adjustActive(minutes: Int) {
        val active = TimerStore.getActiveLabel(this)
        if (active == TimerStore.NONE) {
            Toast.makeText(this, "Start a timer, or adjust it in Settings", Toast.LENGTH_SHORT).show()
            return
        }
        TimerStore.adjust(this, active, minutes)
        refreshValues(); syncService()
    }

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
        val isSession = sessionView()
        // The header now carries the day's planned start time rather than the
        // date — it's what the Start column projects from, and tapping it
        // opens the picker. The date is on the phone's status bar anyway.
        binding.tvHeader.text = "Start  ${fmtClock(dayStartMs())}"

        binding.btnViewDay.setBackgroundResource(
            if (isSession) R.drawable.bg_pill_off else R.drawable.bg_pill_on
        )
        binding.btnViewSession.setBackgroundResource(
            if (isSession) R.drawable.bg_pill_on else R.drawable.bg_pill_off
        )
        binding.btnSort.text = SettingsStore.SORT_SHORT[SettingsStore.getSortMode(this)]
        binding.btnViewDay.setTextColor(if (isSession) muted else amber)
        binding.btnViewSession.setTextColor(if (isSession) blueGrey else muted)

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

        val running = TimerStore.isRunning(this)
        binding.btnStop.isEnabled = running
        binding.btnStop.alpha = if (running) 1f else 0.4f
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

    /**
     * Running total down the rows, in the order they're displayed.
     *
     * With Remain sorting it accumulates time still to do, each label floored
     * at zero so one past its goal doesn't cancel out a shortfall below it.
     * Otherwise it accumulates goals. Labels with no goal add nothing, so the
     * total simply repeats on those rows.
     */
    private fun computeCumulative() {
        val mode = SettingsStore.getColumnMode(this)

        // Which quantity is being accumulated. Remaining for SRem and ETA,
        // goals for SGoal and Start.
        val useRemaining = mode == SettingsStore.COL_SUM_REMAIN || mode == SettingsStore.COL_ETA

        val inc = mutableListOf<Long>()
        val before = mutableListOf<Long>()
        var running = 0L
        displayed.forEach { e ->
            before.add(running)          // total of everything above this row
            running += when {
                e.goalMinutes <= 0 -> 0L
                useRemaining ->
                    TimerStore.getRemainingMs(this, e.name, e.goalMinutes).coerceAtLeast(0L)
                else -> e.goalMinutes * 60_000L
            }
            inc.add(running)             // total including this row
        }
        cumulative = inc
        cumulativeBefore = before
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
    private fun updateTotals(remainMode: Boolean, isSession: Boolean) {
        val library = LabelStore.readLibrary(this)

        val elapsedMs = library.fold(0L) { acc, e ->
            acc + if (isSession) TimerStore.getSessionMs(this, e.name)
                  else TimerStore.getDayMs(this, e.name)
        }
        binding.tvTotalElapsed.text = if (elapsedMs != 0L) fmtHm(elapsedMs) else ""

        val goalMs: Long = if (remainMode) {
            library.fold(0L) { acc, e ->
                acc + if (e.goalMinutes <= 0) 0L
                else TimerStore.getRemainingMs(this, e.name, e.goalMinutes).coerceAtLeast(0L)
            }
        } else {
            library.fold(0L) { acc, e -> acc + e.goalMinutes.toLong() } * 60_000L
        }
        binding.tvTotalGoal.text = if (goalMs > 0L) fmtHm(goalMs) else ""
        // Match the colour of the column it sits under.
        binding.tvTotalGoal.setTextColor(
            when (SettingsStore.getColumnMode(this)) {
                SettingsStore.COL_REMAIN -> if (goalMs <= 0L) overRed else colRemain
                SettingsStore.COL_SUM_REMAIN -> colRemain
                else -> colGoal
            }
        )
    }

    /** h:mm, no seconds. Handles negatives with a leading minus. */
    private fun fmtHm(ms: Long): String {
        val neg = ms < 0
        val mins = (Math.abs(ms) / 60_000L).toInt()
        val body = String.format("%d:%02d", mins / 60, mins % 60)
        return if (neg) "-$body" else body
    }

    /** Formats a duration in ms as h:mm, for goal-style figures. */
    private fun fmtGoalMs(ms: Long): String = fmtGoal((ms / 60_000L).toInt())

    /** Per-second refresh: values only, never the order. */
    private fun refreshValues() {
        computeCumulative()
        updateTotals(
            SettingsStore.getColumnMode(this) == SettingsStore.COL_REMAIN ||
                SettingsStore.getColumnMode(this) == SettingsStore.COL_SUM_REMAIN,
            sessionView()
        )
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

        hLabel.text = label
        hTime.text = TimerStore.formatDuration(
            if (isSession) TimerStore.getSessionMs(this, label)
            else TimerStore.getDayMs(this, label)
        )
        hTime.setTextColor(if (isActive) accent else muted)

        val remainingMs =
            if (entry.goalMinutes > 0) TimerStore.getRemainingMs(this, label, entry.goalMinutes)
            else 0L
        val colMode = SettingsStore.getColumnMode(this)
        val isSum = colMode == SettingsStore.COL_SUM_GOAL || colMode == SettingsStore.COL_SUM_REMAIN
        val isClock = colMode == SettingsStore.COL_START || colMode == SettingsStore.COL_ETA

        // A sigma prefix marks the running totals. Colour alone can't carry
        // the distinction reliably — it fails in sunlight and for colour-blind
        // readers — and the two sums would otherwise look identical.
        // Clock modes project forward from a base: Start from the planned
        // start of the day, ETA from right now. Both add the total of
        // everything above this row, not including it.
        val projectedMs = if (isClock) {
            val base = if (colMode == SettingsStore.COL_START) dayStartMs()
                       else System.currentTimeMillis()
            base + (cumulativeBefore.getOrNull(position) ?: 0L)
        } else 0L

        hGoal.text = when {
            isClock -> {
                val mark = if (colMode == SettingsStore.COL_START) "@" else "~"
                "$mark${fmtClock(projectedMs)}"
            }
            isSum -> {
                val running = cumulative.getOrNull(position) ?: 0L
                if (running > 0L) "\u03a3${fmtGoalMs(running)}" else ""
            }
            entry.goalMinutes <= 0 -> ""
            colMode == SettingsStore.COL_REMAIN -> fmtGoalMs(remainingMs.coerceAtLeast(0L))
            else -> fmtGoal(entry.goalMinutes)
        }
        val isRemainKind = colMode == SettingsStore.COL_REMAIN ||
            colMode == SettingsStore.COL_SUM_REMAIN

        hGoal.setTextColor(
            when {
                // A Start time that has already passed says the plan has
                // slipped, so it dims rather than reading as still valid.
                isClock && colMode == SettingsStore.COL_START &&
                    projectedMs < System.currentTimeMillis() -> goalGrey
                isClock -> colClock
                entry.goalMinutes <= 0 && !isSum -> goalGrey
                colMode == SettingsStore.COL_REMAIN && remainingMs <= 0L -> overRed
                isRemainKind -> colRemain
                else -> colGoal
            }
        )

        hBorder.setBackgroundResource(
            if (isActive) R.drawable.bg_row_active else R.drawable.bg_row_idle
        )
        hNote.setColorFilter(
            if (labelsWithNotes.contains(label)) amber else 0xFF5C736E.toInt()
        )

        // Progress is always against the DAY total, since goals are daily.
        drawBar(h, TimerStore.getDayMs(this, label), entry.goalMinutes)
    }

    /** Adapter over [displayed]; the list itself stays owned by the activity. */
    inner class TimerAdapter : RecyclerView.Adapter<TimerAdapter.Holder>() {

        private val TYPE_TIMER = 0
        private val TYPE_ADD = 1

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            // Null on the "+ New label" row, which has none of these.
            val label: TextView? = view.findViewById(R.id.rowLabel)
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
            if (rowHeightPx > 0) {
                view.layoutParams = (view.layoutParams as ViewGroup.MarginLayoutParams).apply {
                    height = rowHeightPx
                }
            }
            return Holder(view)
        }

        override fun getItemCount(): Int = displayed.size + 1   // + the add row

        override fun onBindViewHolder(holder: Holder, position: Int) {
            if (getItemViewType(position) == TYPE_ADD) return
            val entry = displayed.getOrNull(position) ?: return

            holder.itemView.setOnClickListener {
                if (TimerStore.getActiveLabel(this@MainActivity) == entry.name) return@setOnClickListener
                val wasRunning = TimerStore.isRunning(this@MainActivity)
                TimerStore.start(this@MainActivity, entry.name)
                if (wasRunning) offerNote()
                rebuild(); syncService()
            }
            holder.label?.setOnClickListener {
                onLabelTapped(holder.bindingAdapterPosition)
            }
            holder.note?.setOnClickListener {
                startActivity(
                    Intent(this@MainActivity, NotesActivity::class.java)
                        .putExtra(NotesActivity.EXTRA_LABEL, entry.name)
                )
            }
            // Each gesture has exactly one meaning: the icon drags, the row
            // long-press opens the label popup.
            holder.note?.setOnLongClickListener {
                if (SettingsStore.getSortMode(this@MainActivity) == SettingsStore.SORT_MANUAL) {
                    dragHelper?.startDrag(holder); true
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        "Switch to Manual order to rearrange",
                        Toast.LENGTH_SHORT
                    ).show(); true
                }
            }
            holder.itemView.setOnLongClickListener {
                showLabelPopup(entry.name); true
            }

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
