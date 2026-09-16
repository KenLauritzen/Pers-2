package com.focusledger.timer

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
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

    // Compact on purpose: at 19sp monospace the long form runs close to the
    // screen edge. Matches the main screen's "6:41a" style.
    private val whenFmt = SimpleDateFormat("EEE d MMM  h:mma", Locale.getDefault())

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

        b.btnNotesExport.setOnClickListener { exportThisLabel() }
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
        // Adjustments obey the filter like anything else. They never carry a
        // note, so with the filter on they're hidden — turn "Show runs with no
        // note" on to find or delete one.
        val runs = if (showEmpty) all else all.filter { it.note.isNotBlank() }

        val inflater = LayoutInflater.from(this)
        b.notesList.removeAllViews()

        if (runs.isEmpty()) {
            val empty = TextView(this).apply {
                text = if (all.isEmpty()) "No runs logged yet."
                       else "Nothing with a note yet \u2014 turn on \u201cShow runs with no note\u201d " +
                            "to see every run and adjustment."
                setTextColor(0xFF5C736E.toInt())
                textSize = 17f
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
                "${fmtWhen(run.runStartMs)} \u00b7 adj ${signed(run.adjustedMinutes)}m"
            } else {
                val duration = fmtDuration(run.durationMinutes)
                val adj = if (run.adjustedMinutes != 0) " ${signed(run.adjustedMinutes)}m" else ""
                "${fmtWhen(run.runStartMs)} \u00b7 $duration$adj"
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
    /**
     * Editing a past run's note.
     *
     * Four actions — Delete, Cancel, Clear note, Save — so this uses its own
     * layout rather than AlertDialog's buttons, which stop at three.
     *
     * "Clear note" empties the text and saves immediately, which is different
     * from Delete: the run stays and keeps its time, it just loses the words.
     */
    private fun editPastNote(run: LogStore.RunEntry) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_note_edit, null)
        val input = view.findViewById<EditText>(R.id.noteInput)
        view.findViewById<TextView>(R.id.noteWhen).text = fmtWhen(run.runStartMs)
        input.setText(run.note)
        input.setSelection(input.text.length)

        val dialog = AlertDialog.Builder(this).setView(view).create()

        fun save(text: String) {
            if (!LogStore.updateNoteAt(this, run.lineIndex, text)) {
                Toast.makeText(this, "Couldn't save that note", Toast.LENGTH_SHORT).show()
            }
            dialog.dismiss()
            renderHistory()
        }

        view.findViewById<Button>(R.id.noteSave).setOnClickListener {
            save(input.text.toString())
        }
        view.findViewById<Button>(R.id.noteClear).setOnClickListener {
            confirmClearNote(run) { dialog.dismiss() }
        }
        view.findViewById<Button>(R.id.noteCancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<Button>(R.id.noteDelete).setOnClickListener {
            dialog.dismiss(); confirmDeleteRun(run)
        }
        dialog.show()
    }

    /**
     * Clearing a note keeps the run and its time; only the text goes. Asked
     * about because typed notes aren't recoverable.
     */
    private fun confirmClearNote(run: LogStore.RunEntry, onDone: () -> Unit) {
        if (run.note.isBlank()) { onDone(); return }
        // With the filter on, a run with no note isn't listed — so clearing
        // one makes the row disappear. Said here rather than left to surprise.
        val willVanish = !SettingsStore.isShowEmptyRuns(this)
        AlertDialog.Builder(this)
            .setTitle("Clear this note?")
            .setMessage(
                "The run and its time stay; only the note is removed." +
                if (willVanish)
                    "\n\nThe run will drop out of this list until you turn on " +
                    "\u201cShow runs with no note\u201d."
                else ""
            )
            .setPositiveButton("Clear") { _, _ ->
                if (!LogStore.updateNoteAt(this, run.lineIndex, "")) {
                    Toast.makeText(this, "Couldn't clear that note", Toast.LENGTH_SHORT).show()
                }
                onDone()
                renderHistory()
            }
            .setNegativeButton("Cancel", null)
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
            "adj ${signed(run.adjustedMinutes)}m"
        else
            fmtDuration(run.durationMinutes) +
                if (run.adjustedMinutes != 0) " ${signed(run.adjustedMinutes)}m" else ""

        val effect = if (isToday)
            "\n\nFrom today, so ${fmtSigned(run.totalMs)} will also come off today's total."
        else
            "\n\nFrom an earlier day, so today's totals are unaffected."

        AlertDialog.Builder(this)
            .setTitle(if (run.isAdjustment) "Delete this adjustment?" else "Delete this entry?")
            .setMessage(
                "${fmtWhen(run.runStartMs)} \u00b7 $what$notePart$effect"
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

    /** "Sun 7 Sep  6:41a" — lowercase suffix, no space, to save width. */
    private fun fmtWhen(ms: Long): String =
        whenFmt.format(Date(ms)).replace("AM", "a").replace("PM", "p")

    /**
     * This label's rows only, filtered exactly as the list above is — so what
     * you send matches what you were looking at.
     */
    private fun exportThisLabel() {
        try {
            val showEmpty = SettingsStore.isShowEmptyRuns(this)
            val out = LogStore.exportForLabel(this, label, showEmpty)
            if (out == null) {
                Toast.makeText(this, "Nothing to export for this label", Toast.LENGTH_SHORT).show()
                return
            }
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", out)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Focus \u2014 $label")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Export $label"))

            AlertDialog.Builder(this)
                .setTitle("File location")
                .setMessage(
                    "${out.absolutePath}\n\n" +
                    if (showEmpty) "All runs for this label."
                    else "Only runs with a note, matching the filter above."
                )
                .setPositiveButton("OK", null)
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
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
