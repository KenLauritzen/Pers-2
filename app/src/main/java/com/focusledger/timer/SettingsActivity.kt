package com.focusledger.timer

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.focusledger.timer.databinding.ActivitySettingsBinding
import java.util.ArrayList

/**
 * App preferences only: reminders, the note prompt, and export.
 *
 * Labels, goals and recorded time all moved to the main screen's per-label
 * popup, so a normal working day never needs this page.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivitySettingsBinding

    // Snapshot as loaded, so "dirty" is a real comparison rather than a flag:
    // setting a value and putting it back counts as unchanged.
    private var originalInterval = 0
    private var originalTimeout = 0
    private var originalHeadsUp = false
    private var originalSound = false
    private var originalNote = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)

        originalInterval = SettingsStore.getReminderMinutes(this)
        originalTimeout = SettingsStore.getReminderTimeoutSec(this)
        originalHeadsUp = SettingsStore.isHeadsUp(this)
        originalSound = SettingsStore.isSoundOn(this)
        originalNote = SettingsStore.isPromptNote(this)

        b.editInterval.setText(originalInterval.toString())
        b.editTimeout.setText(originalTimeout.toString())
        b.switchHeadsUp.isChecked = originalHeadsUp
        b.switchSound.isChecked = originalSound
        b.switchNote.isChecked = originalNote

        b.btnRange.setOnClickListener {
            startActivity(Intent(this, RangeActivity::class.java))
        }
        b.btnExport.setOnClickListener { exportFiles() }
        b.btnCancel.setOnClickListener { attemptExit() }
        b.btnSave.setOnClickListener { save() }

        onBackPressedDispatcher.addCallback(this) { attemptExit() }
    }

    private fun hasUnsavedChanges(): Boolean {
        val interval = b.editInterval.text.toString().toIntOrNull() ?: 0
        val timeout = b.editTimeout.text.toString().toIntOrNull() ?: 0
        return interval != originalInterval ||
            timeout != originalTimeout ||
            b.switchHeadsUp.isChecked != originalHeadsUp ||
            b.switchSound.isChecked != originalSound ||
            b.switchNote.isChecked != originalNote
    }

    private fun attemptExit() {
        if (!hasUnsavedChanges()) { finish(); return }
        AlertDialog.Builder(this)
            .setTitle("Save changes?")
            .setMessage("You have unsaved changes to your settings.")
            .setPositiveButton("Save") { _, _ -> save() }
            .setNegativeButton("Discard") { _, _ -> finish() }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun save() {
        SettingsStore.setReminderMinutes(this, b.editInterval.text.toString().toIntOrNull() ?: 0)
        SettingsStore.setReminderTimeoutSec(this, b.editTimeout.text.toString().toIntOrNull() ?: 0)
        SettingsStore.setHeadsUp(this, b.switchHeadsUp.isChecked)
        SettingsStore.setSoundOn(this, b.switchSound.isChecked)
        SettingsStore.setPromptNote(this, b.switchNote.isChecked)
        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
        finish()
    }

    /** Shown after an export, so file locations don't take up page space. */
    private fun showPathsDialog() {
        AlertDialog.Builder(this)
            .setTitle("File locations")
            .setMessage("Labels:\n${LabelStore.filePath(this)}\n\nLog:\n${LogStore.path(this)}")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun exportFiles() {
        try {
            val uris = ArrayList<android.net.Uri>()
            val authority = "$packageName.fileprovider"

            val labels = java.io.File(getExternalFilesDir(null), "labels.txt")
            if (labels.exists()) uris.add(FileProvider.getUriForFile(this, authority, labels))

            val layouts = java.io.File(getExternalFilesDir(null), "layouts.json")
            if (layouts.exists()) uris.add(FileProvider.getUriForFile(this, authority, layouts))

            val tasks = TaskStore.file(this)
            if (tasks.exists()) uris.add(FileProvider.getUriForFile(this, authority, tasks))

            val log = LogStore.file(this)
            if (log.exists()) uris.add(FileProvider.getUriForFile(this, authority, log))

            if (uris.isEmpty()) {
                Toast.makeText(this, "Nothing to export yet", Toast.LENGTH_SHORT).show()
                return
            }

            val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "text/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Export Focus data"))
            showPathsDialog()
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
