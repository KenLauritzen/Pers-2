package com.focusledger.timer

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.focusledger.timer.databinding.ActivityNotesBinding
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Notes for one label: every logged run in order, oldest first, with the
 * current run's note in an input at the bottom so the newest entry sits right
 * beside where you're typing.
 *
 * A run is one start→stop. Working on something at noon and again at 2pm gives
 * two entries, each with its own note.
 */
class NotesActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_LABEL = "label"
    }

    private lateinit var b: ActivityNotesBinding
    private lateinit var label: String
    private val handler = Handler(Looper.getMainLooper())

    private val whenFmt = SimpleDateFormat("EEE d MMM  h:mm a", Locale.getDefault())

    private val tick = object : Runnable {
        override fun run() {
            updateHeader()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityNotesBinding.inflate(layoutInflater)
        setContentView(b.root)

        label = intent.getStringExtra(EXTRA_LABEL) ?: ""
        b.notesLabel.text = label

        b.switchShowEmpty.isChecked = SettingsStore.isShowEmptyRuns(this)
        b.switchShowEmpty.setOnCheckedChangeListener { _, checked ->
            SettingsStore.setShowEmptyRuns(this, checked)
            renderHistory()
        }

        val isRunning = TimerStore.getActiveLabel(this) == label
        if (isRunning) {
            b.editCurrentNote.setText(TimerStore.getPendingNote(this))
            b.notesInputCaption.text = "Note for the run in progress"
        } else {
            b.editCurrentNote.setText("")
            b.editCurrentNote.hint = "Start this timer to add a note to a new run"
            b.editCurrentNote.isEnabled = false
            b.notesInputCaption.text = "Not running \u2014 tap any entry above to edit its note"
        }

        // Saved on every edit: a note half-typed when the phone is grabbed
        // away shouldn't be lost.
        b.editCurrentNote.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (TimerStore.getActiveLabel(this@NotesActivity) == label) {
                    TimerStore.setPendingNote(this@NotesActivity, s?.toString() ?: "")
                }
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, bb: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, bb: Int, c: Int) {}
        })

        b.btnNotesDone.setOnClickListener { finish() }

        renderHistory()
    }

    private fun updateHeader() {
        b.notesTimer.text = if (TimerStore.getActiveLabel(this) == label)
            TimerStore.formatDuration(TimerStore.getDayMs(this, label))
        else ""
    }

    private fun renderHistory() {
        val showEmpty = SettingsStore.isShowEmptyRuns(this)
        val all = LogStore.readRunsForLabel(this, label)
        // Adjustments never carry a note, so the note filter would hide them
        // entirely — which is the situation that made a wrong total
        // impossible to explain. They always show.
        val runs = if (showEmpty) all
                   else all.filter { it.note.isNotBlank() || it.isAdjustment }

        val inflater = LayoutInflater.from(this)
        b.notesList.removeAllViews()

        if (runs.isEmpty()) {
            val empty = TextView(this).apply {
                text = if (all.isEmpty()) "No runs logged yet."
                       else "No notes yet \u2014 turn on \u201cShow runs with no note\u201d to see all runs."
                setTextColor(0xFF5C736E.toInt())
                textSize = 13f
                setPadding(8, 16, 8, 16)
            }
            b.notesList.addView(empty)
            return
        }

        runs.forEach { run ->
            val row = inflater.inflate(R.layout.row_note_entry, b.notesList, false)
            val whenView = row.findViewById<TextView>(R.id.entryWhen)
            val noteView = row.findViewById<TextView>(R.id.entryNote)

            whenView.text = if (run.isAdjustment) {
                "${whenFmt.format(Date(run.runStartMs))}   \u00b7   adjustment ${signed(run.adjustedMinutes)}m"
            } else {
                val duration = fmtDuration(run.durationMinutes)
                val adj = if (run.adjustedMinutes != 0) "  (${signed(run.adjustedMinutes)}m)" else ""
                "${whenFmt.format(Date(run.runStartMs))}   \u00b7   $duration$adj"
            }
            whenView.setTextColor(
                if (run.isAdjustment) 0xFFB8A06E.toInt() else 0xFF8FA39E.toInt()
            )

            if (run.note.isBlank()) {
                noteView.text = "\u2014"
                noteView.setTextColor(0xFF5C736E.toInt())
            } else {
                noteView.text = run.note
                noteView.setTextColor(0xFFF1EDE3.toInt())
            }

            row.setOnClickListener { editPastNote(run) }
            row.setOnLongClickListener { confirmDeleteRun(run); true }
            b.notesList.addView(row)
        }

        // Oldest first, so scroll to the bottom where the newest sits.
        b.notesScroll.post { b.notesScroll.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    /** Tapping a past run opens its note for editing. */
    private fun editPastNote(run: LogStore.RunEntry) {
        val input = EditText(this).apply {
            setText(run.note)
            setTextColor(0xFFF1EDE3.toInt())
            setBackgroundResource(R.drawable.bg_row_track)
            setPadding(28, 24, 28, 24)
            textSize = 15f
            setSingleLine(false)
            minLines = 3
        }

        AlertDialog.Builder(this)
            .setTitle(whenFmt.format(Date(run.runStartMs)))
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val ok = LogStore.updateNoteAt(this, run.lineIndex, input.text.toString())
                if (!ok) Toast.makeText(this, "Couldn't save that note", Toast.LENGTH_SHORT).show()
                renderHistory()
            }
            .setNegativeButton("Cancel", null)
            // Long-press deletes too, but a gesture nobody knows about isn't
            // a feature. This is the discoverable route.
            .setNeutralButton("Delete") { _, _ -> confirmDeleteRun(run) }
            .show()
    }

    /**
     * Deleting a logged run. The confirmation spells out exactly which entry
     * is going, since one run looks much like another in a list.
     *
     * A run from today also comes off today's counters, so the main screen and
     * the log agree. Older runs have no counter left to adjust — the day they
     * belonged to has long since rolled over — so only the line goes.
     */
    private fun confirmDeleteRun(run: LogStore.RunEntry) {
        val isToday = isSameDay(run.runStartMs, System.currentTimeMillis())
        val notePart = if (run.note.isBlank()) "" else "\n\n\u201c${run.note}\u201d"

        val what = if (run.isAdjustment)
            "adjustment ${signed(run.adjustedMinutes)}m"
        else
            fmtDuration(run.durationMinutes) +
                if (run.adjustedMinutes != 0) "  (${signed(run.adjustedMinutes)}m)" else ""

        val effect = if (isToday)
            "\n\nFrom today, so ${fmtSigned(run.totalMs)} will also come off today's total."
        else
            "\n\nFrom an earlier day, so today's totals are unaffected."

        AlertDialog.Builder(this)
            .setTitle(if (run.isAdjustment) "Delete this adjustment?" else "Delete this entry?")
            .setMessage(
                "${whenFmt.format(Date(run.runStartMs))}   \u00b7   $what$notePart$effect"
            )
            .setPositiveButton("Delete") { _, _ -> deleteRun(run, isToday) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteRun(run: LogStore.RunEntry, isToday: Boolean) {
        if (!LogStore.deleteRunAt(this, run.lineIndex)) {
            Toast.makeText(this, "Couldn't delete that entry", Toast.LENGTH_LONG).show()
            return
        }
        if (isToday) {
            // Exact elapsed ms plus any adjustment on the row. Using the
            // rounded minutes here was the bug: a 17-second run rounds to
            // zero and subtracting zero left the time in the counter.
            TimerStore.subtractSilently(this, label, run.totalMs)
        }
        renderHistory()
        updateHeader()
    }

    private fun isSameDay(a: Long, b: Long): Boolean {
        val ca = Calendar.getInstance().apply { timeInMillis = a }
        val cb = Calendar.getInstance().apply { timeInMillis = b }
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) &&
            ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
    }

    private fun signed(v: Int) = if (v > 0) "+$v" else v.toString()

    /** A duration in ms as a readable signed figure, e.g. "-5m" or "17s". */
    private fun fmtSigned(ms: Long): String {
        val neg = ms < 0
        val abs = Math.abs(ms)
        val body = when {
            abs < 60_000L -> "${abs / 1000}s"
            abs % 3_600_000L == 0L -> "${abs / 3_600_000L}h"
            abs < 3_600_000L -> "${abs / 60_000L}m"
            else -> "${abs / 3_600_000L}h ${(abs % 3_600_000L) / 60_000L}m"
        }
        return if (neg) "-$body" else body
    }

    private fun fmtDuration(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    override fun onResume() {
        super.onResume()
        updateHeader()
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }
}
