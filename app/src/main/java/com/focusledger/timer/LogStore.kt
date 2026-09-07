package com.focusledger.timer

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Append-only CSV log of timer runs.
 *
 * Schema:
 *   session_start,run_start,label,duration_minutes,adjusted,note
 *
 * - One row per completed run (stop, or switching to another timer).
 * - Runs crossing midnight are split into one row per date.
 * - Manual +/- adjustments write their own row: duration=0, adjusted=+/-N.
 * - Notes are attached to the most recently written row, rewritten atomically.
 */
object LogStore {

    private const val FILE_NAME = "timer_log.csv"
    private const val HEADER =
        "session_start,run_start,label,duration_minutes,adjusted,note"

    private val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US)

    fun file(context: Context): File = File(context.getExternalFilesDir(null), FILE_NAME)

    private fun ensureHeader(context: Context) {
        val f = file(context)
        if (!f.exists() || f.length() == 0L) {
            f.parentFile?.mkdirs()
            f.writeText(HEADER + "\n")
        }
    }

    /**
     * Escapes a field for CSV. Line breaks are encoded as a literal backslash-n
     * rather than dropped, so note formatting survives a round trip. A real
     * newline would split the row across two physical lines, which would break
     * the line-indexed reads and rewrites this file relies on.
     */
    private fun csvEscape(s: String): String {
        val encoded = s
            .replace("\\", "\\\\")      // escape existing backslashes first
            .replace("\r\n", "\\n")
            .replace("\n", "\\n")
            .replace("\r", "\\n")
        val needsQuote = encoded.contains(',') || encoded.contains('"')
        val cleaned = encoded.replace("\"", "\"\"")
        return if (needsQuote) "\"$cleaned\"" else cleaned
    }

    /** Reverses csvEscape's line-break encoding for display. */
    private fun decodeNewlines(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            if (s[i] == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'n' -> { out.append('\n'); i += 2; continue }
                    '\\' -> { out.append('\\'); i += 2; continue }
                }
            }
            out.append(s[i]); i++
        }
        return out.toString()
    }

    private fun startOfNextDay(timeMs: Long): Long {
        val c = Calendar.getInstance().apply {
            timeInMillis = timeMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, 1)
        }
        return c.timeInMillis
    }

    private fun sameDay(a: Long, b: Long): Boolean {
        val ca = Calendar.getInstance().apply { timeInMillis = a }
        val cb = Calendar.getInstance().apply { timeInMillis = b }
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) &&
            ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
    }

    /**
     * Records a completed run from [runStartMs] to [runEndMs], splitting at
     * midnight so each row belongs to a single date.
     */
    fun logRun(
        context: Context,
        sessionStartMs: Long,
        runStartMs: Long,
        runEndMs: Long,
        label: String,
        adjustedMinutes: Int = 0,
        note: String = ""
    ) {
        if (runEndMs <= runStartMs && adjustedMinutes == 0) return
        try {
            ensureHeader(context)
            val rows = StringBuilder()
            var segStart = runStartMs
            while (!sameDay(segStart, runEndMs)) {
                val boundary = startOfNextDay(segStart)
                if (boundary >= runEndMs) break
                rows.append(row(sessionStartMs, segStart, boundary, label, 0))
                segStart = boundary
            }
            rows.append(row(sessionStartMs, segStart, runEndMs, label, adjustedMinutes, note))
            file(context).appendText(rows.toString())
        } catch (e: Exception) {
            // logging must never crash the app
        }
    }

    /** Records a manual +/- adjustment as its own row (duration 0). */
    fun logAdjustment(
        context: Context,
        sessionStartMs: Long,
        label: String,
        deltaMinutes: Int
    ) {
        if (deltaMinutes == 0) return
        try {
            ensureHeader(context)
            val now = System.currentTimeMillis()
            val line = listOf(
                stamp.format(Date(sessionStartMs)),
                stamp.format(Date(now)),
                csvEscape(label),
                "0",
                deltaMinutes.toString(),
                ""
            ).joinToString(",") + "\n"
            file(context).appendText(line)
        } catch (e: Exception) {
        }
    }

    private fun row(
        sessionStartMs: Long,
        startMs: Long,
        endMs: Long,
        label: String,
        adjusted: Int,
        note: String = ""
    ): String {
        val minutes = Math.round((endMs - startMs) / 60000.0).toInt()
        return listOf(
            stamp.format(Date(sessionStartMs)),
            stamp.format(Date(startMs)),
            csvEscape(label),
            minutes.toString(),
            adjusted.toString(),
            csvEscape(note)
        ).joinToString(",") + "\n"
    }

    /**
     * Attaches [note] to the most recently written row. Rewrites the file
     * atomically via a temp file so a failure can't corrupt the log.
     */
    fun attachNoteToLastRow(context: Context, note: String): Boolean {
        if (note.isBlank()) return true
        return try {
            val f = file(context)
            if (!f.exists()) return false
            val lines = f.readLines().toMutableList()
            if (lines.size <= 1) return false

            val lastIdx = lines.indexOfLast { it.isNotBlank() }
            if (lastIdx <= 0) return false

            val parts = parseCsvLine(lines[lastIdx])
            if (parts.size < 6) return false

            // The run may already carry a note written from the in-progress
            // buffer, so append rather than replace — otherwise whichever
            // wrote last would silently discard the other.
            val existing = parts[5].trim()
            val merged = if (existing.isEmpty()) note.trim() else "$existing\n${note.trim()}"

            lines[lastIdx] = (parts.subList(0, 5).map { csvEscape(it) } + csvEscape(merged))
                .joinToString(",")

            val tmp = File(f.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(lines.joinToString("\n") + "\n")
            tmp.renameTo(f)
            true
        } catch (e: Exception) {
            false
        }
    }

    // ---- reading back, for the notes screen -------------------------------

    /** One logged run, parsed back out of the CSV. */
    data class RunEntry(
        val lineIndex: Int,
        val runStartMs: Long,
        val label: String,
        val durationMinutes: Int,
        val adjustedMinutes: Int,
        val note: String
    )

    /** Splits a CSV line, honouring quoted fields and doubled quotes. */
    private fun parseCsvLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                inQuotes && ch == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                    sb.append('"'); i++
                }
                ch == '"' -> inQuotes = !inQuotes
                ch == ',' && !inQuotes -> { out.add(sb.toString()); sb.clear() }
                else -> sb.append(ch)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }

    /**
     * Every run logged for [label], oldest first — the order the notes screen
     * reads in, so the newest sits next to the input box.
     *
     * Adjustment-only rows (duration 0 with an adjustment) are left out: they
     * aren't work sessions and would clutter the history.
     */
    fun readRunsForLabel(context: Context, label: String): List<RunEntry> {
        val out = mutableListOf<RunEntry>()
        try {
            val f = file(context)
            if (!f.exists()) return out
            f.readLines().forEachIndexed { idx, line ->
                if (idx == 0 || line.isBlank()) return@forEachIndexed
                val p = parseCsvLine(line)
                if (p.size < 6) return@forEachIndexed
                if (p[2] != label) return@forEachIndexed

                val duration = p[3].toIntOrNull() ?: 0
                val adjusted = p[4].toIntOrNull() ?: 0
                if (duration == 0 && adjusted != 0) return@forEachIndexed

                val startMs = try {
                    stamp.parse(p[1])?.time ?: 0L
                } catch (e: Exception) { 0L }

                out.add(RunEntry(idx, startMs, p[2], duration, adjusted, decodeNewlines(p[5])))
            }
        } catch (e: Exception) {
        }
        return out
    }

    /** Labels that have at least one non-empty note, for the row icon tint. */
    fun labelsWithNotes(context: Context): Set<String> {
        val out = mutableSetOf<String>()
        try {
            val f = file(context)
            if (!f.exists()) return out
            f.readLines().forEachIndexed { idx, line ->
                if (idx == 0 || line.isBlank()) return@forEachIndexed
                val p = parseCsvLine(line)
                if (p.size >= 6 && p[5].isNotBlank()) out.add(p[2])
            }
        } catch (e: Exception) {
        }
        return out
    }

    /**
     * Replaces the note on one specific row. Written via a temp file and
     * renamed, so a failure part-way can't corrupt the log.
     */
    fun updateNoteAt(context: Context, lineIndex: Int, note: String): Boolean {
        return try {
            val f = file(context)
            if (!f.exists()) return false
            val lines = f.readLines().toMutableList()
            if (lineIndex !in lines.indices) return false

            val p = parseCsvLine(lines[lineIndex])
            if (p.size < 6) return false
            lines[lineIndex] = (p.subList(0, 5).map { csvEscape(it) } + csvEscape(note))
                .joinToString(",")

            val tmp = File(f.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(lines.joinToString("\n") + "\n")
            tmp.renameTo(f)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun exists(context: Context) = file(context).exists()
    fun path(context: Context): String = file(context).absolutePath
}
