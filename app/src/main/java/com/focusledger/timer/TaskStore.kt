package com.focusledger.timer

import android.content.Context
import java.io.File

/**
 * Status cycles on each tap: open, doing, done, archived, back to open.
 *
 * Four rather than a tick box because "finished" and "get it off my screen"
 * are different moments. DONE still shows, greyed, so you can see what you got
 * through; ARCHIVED drops out of the row but stays in the editor.
 */
enum class TaskStatus { OPEN, DOING, DONE, ARCHIVED;
    fun next(): TaskStatus = entries[(ordinal + 1) % entries.size]
}

data class Task(
    val id: String,
    val label: String,
    val text: String,
    val estimateMinutes: Int,
    val status: TaskStatus,
    /** Time accrued against this task, in ms. Never exceeds its label's. */
    val actualMs: Long,
    val order: Int,
    /** When it was first marked done. 0 while it never has been. */
    val completedAt: Long = 0L
)

/**
 * Tasks under a label, in
 *   Android/data/com.focusledger.timer/files/tasks.csv
 *
 * CSV rather than JSON deliberately: the intention is to merge a spreadsheet
 * of tasks-by-category into this file one day, so it's shaped for that now.
 * One row per task, the label as plain text, the estimate in whole minutes.
 *
 * **Task time only accrues while the label's timer is running and the task is
 * DOING.** The label's own total stays authoritative; task time is a softer
 * second record that will usually sum to less. That gap is honest \u2014 thinking
 * time, interruptions and forgetting to advance a task all live in it.
 */
object TaskStore {

    private const val FILE_NAME = "tasks.csv"
    private const val HEADER =
        "label,text,estimate_minutes,status,actual_ms,sort_order,id,completed_at"

    /** Which task is accruing, and since when. Only ever one. */
    private const val KEY_DOING_ID = "task_doing_id"
    private const val KEY_DOING_SINCE = "task_doing_since"

    private fun p(c: Context) = c.getSharedPreferences("focus_ledger_prefs", Context.MODE_PRIVATE)

    fun file(context: Context): File = File(context.getExternalFilesDir(null), FILE_NAME)

    // ---- reading and writing ------------------------------------------------

    fun readAll(context: Context): List<Task> {
        val out = mutableListOf<Task>()
        try {
            val f = file(context)
            if (!f.exists()) return out
            f.readLines().drop(1).forEach { line ->
                if (line.isBlank()) return@forEach
                val p = parseCsvLine(line)
                if (p.size < 6) return@forEach
                val status = runCatching { TaskStatus.valueOf(p[3]) }.getOrDefault(TaskStatus.OPEN)
                out.add(
                    Task(
                        id = if (p.size >= 7 && p[6].isNotBlank()) p[6] else newId(),
                        label = p[0],
                        text = decodeNewlines(p[1]),
                        estimateMinutes = p[2].toIntOrNull() ?: 0,
                        status = status,
                        actualMs = p[4].toLongOrNull() ?: 0L,
                        order = p[5].toIntOrNull() ?: 0,
                        completedAt = if (p.size >= 8) p[7].toLongOrNull() ?: 0L else 0L
                    )
                )
            }
        } catch (e: Exception) {
        }
        return out
    }

    fun writeAll(context: Context, tasks: List<Task>): Boolean {
        return try {
            context.getExternalFilesDir(null)?.mkdirs()
            val body = tasks.joinToString("\n") { t ->
                listOf(
                    csvEscape(t.label),
                    csvEscape(encodeNewlines(t.text)),
                    t.estimateMinutes.toString(),
                    t.status.name,
                    t.actualMs.toString(),
                    t.order.toString(),
                    t.id,
                    t.completedAt.toString()
                ).joinToString(",")
            }
            file(context).writeText(HEADER + "\n" + body + if (body.isEmpty()) "" else "\n")
            true
        } catch (e: Exception) {
            false
        }
    }

    fun forLabel(context: Context, label: String): List<Task> =
        readAll(context).filter { it.label == label }.sortedBy { it.order }

    /** What the row shows: open and in-progress work, plus finished-but-visible. */
    fun visibleForRow(context: Context, label: String, limit: Int): List<Task> {
        if (limit <= 0) return emptyList()
        return forLabel(context, label)
            .filter { it.status != TaskStatus.ARCHIVED }
            .take(limit)
    }

    // ---- editing ------------------------------------------------------------

    fun add(context: Context, label: String, text: String, estimate: Int): Boolean {
        val all = readAll(context)
        val nextOrder = (all.filter { it.label == label }.maxOfOrNull { it.order } ?: -1) + 1
        return writeAll(
            context,
            all + Task(newId(), label, text.trim(), estimate.coerceAtLeast(0),
                       TaskStatus.OPEN, 0L, nextOrder, 0L)
        )
    }

    fun update(context: Context, task: Task): Boolean =
        writeAll(context, readAll(context).map { if (it.id == task.id) task else it })

    fun delete(context: Context, id: String): Boolean {
        if (p(context).getString(KEY_DOING_ID, "") == id) clearDoing(context)
        return writeAll(context, readAll(context).filter { it.id != id })
    }

    /** Applies a new order to one label's tasks, leaving other labels alone. */
    fun reorder(context: Context, label: String, idsInOrder: List<String>): Boolean {
        val all = readAll(context)
        val ranks = idsInOrder.withIndex().associate { (i, id) -> id to i }
        return writeAll(context, all.map {
            if (it.label == label && ranks.containsKey(it.id)) it.copy(order = ranks[it.id]!!)
            else it
        })
    }

    // ---- accrual ------------------------------------------------------------

    /**
     * Moves a task to its next status, folding any accrued time first.
     *
     * Marking one DOING clears whatever was DOING before \u2014 across every label,
     * not just this one. Two tasks accruing at once would double-count against
     * a single timer.
     */
    fun cycleStatus(context: Context, task: Task): Boolean {
        settleDoing(context)
        val next = task.status.next()
        val now = System.currentTimeMillis()

        // Stamped the first time it is completed and kept thereafter, so
        // cycling past DONE and back doesn't rewrite the date it was finished.
        val firstCompletion = next == TaskStatus.DONE && task.completedAt == 0L

        val all = readAll(context).map { t ->
            when {
                t.id == task.id ->
                    t.copy(status = next, completedAt = if (firstCompletion) now else t.completedAt)
                // only one DOING anywhere
                next == TaskStatus.DOING && t.status == TaskStatus.DOING ->
                    t.copy(status = TaskStatus.OPEN)
                else -> t
            }
        }
        val ok = writeAll(context, all)
        if (next == TaskStatus.DOING) startDoing(context, task.id) else clearDoing(context)

        // A row in the log, so the completion survives the task being edited,
        // archived or deleted — and so a date range can count it.
        if (firstCompletion) {
            val accrued = all.firstOrNull { it.id == task.id }?.actualMs ?: task.actualMs
            LogStore.logTaskDone(
                context, TimerStore.getSessionStartMs(context),
                task.label, task.text, accrued, task.estimateMinutes
            )
        }
        return ok
    }

    private fun startDoing(context: Context, id: String) {
        p(context).edit()
            .putString(KEY_DOING_ID, id)
            .putLong(KEY_DOING_SINCE, System.currentTimeMillis())
            .apply()
    }

    private fun clearDoing(context: Context) {
        p(context).edit().remove(KEY_DOING_ID).remove(KEY_DOING_SINCE).apply()
    }

    /**
     * Folds time accrued so far into the DOING task and restarts the clock.
     *
     * Called before any change that would otherwise lose it, and when a timer
     * stops. Accrues nothing if the task's own label isn't the one running \u2014
     * that's what keeps task time from exceeding label time.
     */
    fun settleDoing(context: Context) {
        val id = p(context).getString(KEY_DOING_ID, "") ?: ""
        if (id.isEmpty()) return
        val since = p(context).getLong(KEY_DOING_SINCE, 0L)
        if (since <= 0L) return

        val all = readAll(context)
        val task = all.firstOrNull { it.id == id } ?: run { clearDoing(context); return }

        val elapsed =
            if (TimerStore.getActiveLabel(context) == task.label)
                (System.currentTimeMillis() - since).coerceAtLeast(0L)
            else 0L

        if (elapsed > 0L) {
            writeAll(context, all.map {
                if (it.id == id) it.copy(actualMs = it.actualMs + elapsed) else it
            })
        }
        p(context).edit().putLong(KEY_DOING_SINCE, System.currentTimeMillis()).apply()
    }

    /** Accrued time including the part not yet written, for display. */
    fun liveMs(context: Context, task: Task): Long {
        if (task.status != TaskStatus.DOING) return task.actualMs
        if (p(context).getString(KEY_DOING_ID, "") != task.id) return task.actualMs
        if (TimerStore.getActiveLabel(context) != task.label) return task.actualMs
        val since = p(context).getLong(KEY_DOING_SINCE, 0L)
        if (since <= 0L) return task.actualMs
        return task.actualMs + (System.currentTimeMillis() - since).coerceAtLeast(0L)
    }

    // ---- how many to show, per label ---------------------------------------

    fun getShowCount(context: Context, label: String): Int =
        p(context).getInt("task_show_$label", 3).coerceIn(0, 5)

    fun setShowCount(context: Context, label: String, n: Int) =
        p(context).edit().putInt("task_show_$label", n.coerceIn(0, 5)).apply()

    // ---- helpers ------------------------------------------------------------

    private fun newId(): String =
        System.currentTimeMillis().toString(36) + (0..9999).random().toString(36)

    private fun encodeNewlines(s: String) = s.replace("\n", "\\n")
    private fun decodeNewlines(s: String) = s.replace("\\n", "\n")

    private fun csvEscape(v: String): String =
        if (v.contains(',') || v.contains('"'))
            "\"" + v.replace("\"", "\"\"") + "\""
        else v

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
}
