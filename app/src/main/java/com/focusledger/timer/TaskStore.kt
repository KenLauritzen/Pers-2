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
    private const val KEY_LAST_SORT_DAY = "task_last_sort_day"
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

    /**
     * What a row shows: [limit] outstanding tasks, plus anything finished
     * today, in their stored positions.
     *
     * Today's completions are **extras beyond the count**, not part of it.
     * Counted against it, finishing two at T2 would leave the row showing two
     * ticks and nothing upcoming — the block would go blank exactly when you'd
     * been productive.
     *
     * **Uncapped.** A cap of two made the cross pointless: completions fell
     * off by themselves, so there was nothing for archiving to do. Row length
     * is yours to manage — with the T setting, with the cross, and by the day
     * turning over.
     *
     * Yesterday's completions never show; they sink below the outstanding work
     * at the first open of a new day.
     */
    fun visibleForRow(context: Context, label: String, limit: Int): List<Task> {
        if (limit <= 0) return emptyList()
        val all = forLabel(context, label)

        val outstanding = all
            .filter { it.status == TaskStatus.OPEN || it.status == TaskStatus.DOING }
            .take(limit)
            .map { it.id }
            .toSet()

        val doneToday = all
            .filter { it.status == TaskStatus.DONE && isToday(it.completedAt) }
            .map { it.id }
            .toSet()

        // Stored order, so a completion stays where it was rather than
        // jumping to the top the moment it's ticked.
        return all.filter { it.id in outstanding || it.id in doneToday }
    }

    private fun isToday(ms: Long): Boolean {
        if (ms <= 0L) return false
        val a = java.util.Calendar.getInstance().apply { timeInMillis = ms }
        val b = java.util.Calendar.getInstance()
        return a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR) &&
            a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR)
    }

    // ---- editing ------------------------------------------------------------

    /**
     * Adds a task at the top of the outstanding work.
     *
     * You add a task because it's on your mind now, so the bottom of a long
     * list is the wrong place. It goes **below anything marked doing** though
     * — a new task shouldn't displace the one you're actually on.
     */
    fun add(context: Context, label: String, text: String, estimate: Int): Boolean {
        val all = readAll(context)
        val mine = all.filter { it.label == label }.sortedBy { it.order }
        val others = all.filter { it.label != label }

        val task = Task(newId(), label, text.trim(), estimate.coerceAtLeast(0),
                        TaskStatus.OPEN, 0L, 0, 0L)

        val doing = mine.filter { it.status == TaskStatus.DOING }
        val rest = mine.filter { it.status != TaskStatus.DOING }
        val reordered = (doing + task + rest).mapIndexed { i, t -> t.copy(order = i) }

        return writeAll(context, others + reordered)
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

    /**
     * Once a day, sinks older completions below the outstanding work.
     *
     * Within each label:
     *   1. outstanding \u2014 open and doing \u2014 keeping the order you dragged them into
     *   2. completed before today, newest completion first
     *   3. archived, oldest last
     *
     * **This rewrites the stored order**, not just the display. That makes the
     * file easy to sweep later \u2014 old completions collect at the end of each
     * label's block \u2014 at the cost of one thing: un-ticking a task from a
     * previous day won't return it to where it used to sit.
     *
     * Runs on the first open of a new day rather than at midnight, so it still
     * happens when the app wasn't running.
     */
    fun sortIfNewDay(context: Context) {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date())
        if (p(context).getString(KEY_LAST_SORT_DAY, "") == today) return

        val all = readAll(context)
        if (all.isNotEmpty()) {
            val resorted = all
                .groupBy { it.label }
                .flatMap { (_, tasks) ->
                    val ordered = tasks.sortedBy { it.order }
                    val outstanding = ordered.filter {
                        it.status == TaskStatus.OPEN || it.status == TaskStatus.DOING
                    }
                    // Today's stay in place; only older ones sink.
                    val doneToday = ordered.filter {
                        it.status == TaskStatus.DONE && isToday(it.completedAt)
                    }
                    // Archived merges with the older completions rather than
                    // forming a tier of its own. The cross only ever meant
                    // "don't show this today"; once the day has turned it has
                    // served its purpose and the two are the same thing.
                    val finishedOlder = ordered
                        .filter {
                            (it.status == TaskStatus.DONE && !isToday(it.completedAt)) ||
                                it.status == TaskStatus.ARCHIVED
                        }
                        .sortedByDescending { it.completedAt }

                    (outstanding + doneToday + finishedOlder)
                        .mapIndexed { i, t -> t.copy(order = i) }
                }
            writeAll(context, resorted)
        }
        p(context).edit().putString(KEY_LAST_SORT_DAY, today).apply()
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

        // Every arrival at DONE is a completion, not just the first. Stamping
        // only once meant a task finished twice recorded the second one
        // nowhere, and kept showing the older date — which made recurring work
        // invisible in the history.
        //
        // Cycling round to OPEN clears the date: an open task has no
        // completion date, and leaving one attached showed a finished date on
        // something still to do. ARCHIVED keeps it, being still finished.
        val completion = next == TaskStatus.DONE
        val reopened = next == TaskStatus.OPEN

        val all = readAll(context).map { t ->
            when {
                t.id == task.id -> t.copy(
                    status = next,
                    completedAt = when {
                        completion -> now
                        reopened -> 0L
                        else -> t.completedAt
                    }
                )
                // one DOING per label
                next == TaskStatus.DOING && t.status == TaskStatus.DOING &&
                    t.label == task.label -> t.copy(status = TaskStatus.OPEN)
                else -> t
            }
        }
        val ok = writeAll(context, all)
        // The accrual clock follows the label that's running, not every task
        // marked doing — so it only starts when this task's label is the
        // active one, and stops when this task stops being doing.
        if (next == TaskStatus.DOING && TimerStore.getActiveLabel(context) == task.label)
            startDoing(context, task.id)
        else if (p(context).getString(KEY_DOING_ID, "") == task.id)
            clearDoing(context)

        // A row in the log, so the completion survives the task being edited,
        // archived or deleted — and so a date range can count it.
        if (completion) {
            val accrued = all.firstOrNull { it.id == task.id }?.actualMs ?: task.actualMs
            LogStore.logTaskDone(
                context, TimerStore.getSessionStartMs(context),
                task.label, task.text, accrued, task.estimateMinutes
            )
        }
        return ok
    }

    /**
     * Called when a label starts running: if it already has a task marked
     * doing, that task begins accruing now.
     *
     * Needed once a task can be marked doing while its label is stopped —
     * otherwise the marker would sit there and count nothing.
     */
    fun onLabelStarted(context: Context, label: String) {
        val doing = readAll(context)
            .firstOrNull { it.label == label && it.status == TaskStatus.DOING }
        if (doing != null) startDoing(context, doing.id) else clearDoing(context)
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
