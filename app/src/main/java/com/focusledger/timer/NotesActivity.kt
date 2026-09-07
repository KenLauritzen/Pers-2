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
        val runs = if (showEmpty) all else all.filter { it.note.isNotBlank() }

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

            val duration = fmtDuration(run.durationMinutes)
            val adj = if (run.adjustedMinutes != 0) "  (${signed(run.adjustedMinutes)}m)" else ""
            whenView.text = "${whenFmt.format(Date(run.runStartMs))}   \u00b7   $duration$adj"

            if (run.note.isBlank()) {
                noteView.text = "\u2014"
                noteView.setTextColor(0xFF5C736E.toInt())
            } else {
                noteView.text = run.note
                noteView.setTextColor(0xFFF1EDE3.toInt())
            }

            row.setOnClickListener { editPastNote(run) }
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
            .show()
    }

    private fun signed(v: Int) = if (v > 0) "+$v" else v.toString()

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
