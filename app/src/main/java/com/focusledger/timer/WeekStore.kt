package com.focusledger.timer

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** One planned block: a label and a duration, in a day's running order. */
data class Block(
    val id: String,
    val key: String,          // "2026-09-21" for a dated day, "D0".."D6" for the default week
    val label: String,
    val minutes: Int,
    val order: Int,
    /** Tasks tagged to this block. Empty for now \u2014 see idea 91. */
    val taskIds: List<String> = emptyList()
) {
    /** Zeroed blocks are parked, not deleted: greyed at the foot of the column. */
    val isParked get() = minutes <= 0
}

/** A day's own settings, separate from its blocks. */
data class PlanDay(
    val key: String,
    val startMinutes: Int,
    val applied: Boolean
)

/**
 * The weekly plan.
 *
 * Two files, both keyed the same way so the default week and a dated week
 * share every code path:
 *
 *   week_days.csv     key,day_start_minutes,applied
 *   week_blocks.csv   key,label,minutes,sort_order,id,task_ids
 *
 * A key is either a date (`2026-09-21`) or a weekday slot in the default week
 * (`D0` for Sunday through `D6`). Dates sort chronologically as text, which is
 * why they're stored that way rather than as `Sep21Mon`.
 *
 * **Start times are never stored.** A block's start is the day's start plus
 * every block above it, so the order *is* the schedule and moving a block
 * moves everything below it.
 */
object WeekStore {

    private const val DAYS_FILE = "week_days.csv"
    private const val BLOCKS_FILE = "week_blocks.csv"

    private const val DAYS_HEADER = "key,day_start_minutes,applied"
    private const val BLOCKS_HEADER = "key,label,minutes,sort_order,id,task_ids"

    /** Day start when nothing has been set, matching the main screen's default. */
    const val DEFAULT_START_MINUTES = 6 * 60

    private val dateKeyFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private fun daysFile(c: Context) = File(c.getExternalFilesDir(null), DAYS_FILE)
    private fun blocksFile(c: Context) = File(c.getExternalFilesDir(null), BLOCKS_FILE)

    // ---- keys ---------------------------------------------------------------

    fun dateKey(ms: Long): String = dateKeyFmt.format(Date(ms))

    /** `D0` Sunday through `D6` Saturday. */
    fun defaultKeyFor(ms: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = ms }
        return "D${c.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY}"
    }

    fun isDefaultKey(key: String) = key.length == 2 && key[0] == 'D'

    /**
     * The seven dates of the week containing [ms], starting on [startDow]
     * where 0 is Sunday.
     */
    fun weekOf(ms: Long, startDow: Int): List<Long> {
        val c = Calendar.getInstance().apply {
            timeInMillis = ms
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val dow = c.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY
        c.add(Calendar.DAY_OF_YEAR, -((dow - startDow + 7) % 7))
        return (0..6).map { i ->
            (c.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, i) }.timeInMillis
        }
    }

    // ---- days ---------------------------------------------------------------

    fun readDays(c: Context): Map<String, PlanDay> {
        val out = mutableMapOf<String, PlanDay>()
        try {
            val f = daysFile(c)
            if (!f.exists()) return out
            f.readLines().drop(1).forEach { line ->
                if (line.isBlank()) return@forEach
                val p = line.split(",")
                if (p.size < 3) return@forEach
                out[p[0]] = PlanDay(
                    key = p[0],
                    startMinutes = p[1].toIntOrNull() ?: DEFAULT_START_MINUTES,
                    applied = p[2] == "1"
                )
            }
        } catch (e: Exception) {
        }
        return out
    }

    fun writeDays(c: Context, days: Collection<PlanDay>): Boolean = try {
        c.getExternalFilesDir(null)?.mkdirs()
        daysFile(c).writeText(
            DAYS_HEADER + "\n" +
                days.joinToString("\n") { "${it.key},${it.startMinutes},${if (it.applied) 1 else 0}" } +
                if (days.isEmpty()) "" else "\n"
        )
        true
    } catch (e: Exception) {
        false
    }

    fun day(c: Context, key: String): PlanDay =
        readDays(c)[key] ?: PlanDay(key, DEFAULT_START_MINUTES, false)

    fun setDayStart(c: Context, key: String, startMinutes: Int) {
        val days = readDays(c).toMutableMap()
        val existing = days[key] ?: PlanDay(key, DEFAULT_START_MINUTES, false)
        days[key] = existing.copy(startMinutes = startMinutes.coerceIn(0, 23 * 60 + 59))
        writeDays(c, days.values)
    }

    fun setApplied(c: Context, key: String, applied: Boolean) {
        val days = readDays(c).toMutableMap()
        val existing = days[key] ?: PlanDay(key, DEFAULT_START_MINUTES, false)
        days[key] = existing.copy(applied = applied)
        writeDays(c, days.values)
    }

    // ---- blocks -------------------------------------------------------------

    fun readBlocks(c: Context): List<Block> {
        val out = mutableListOf<Block>()
        try {
            val f = blocksFile(c)
            if (!f.exists()) return out
            f.readLines().drop(1).forEach { line ->
                if (line.isBlank()) return@forEach
                val p = parseCsvLine(line)
                if (p.size < 5) return@forEach
                out.add(
                    Block(
                        id = if (p.size >= 5 && p[4].isNotBlank()) p[4] else newId(),
                        key = p[0],
                        label = p[1],
                        minutes = p[2].toIntOrNull() ?: 0,
                        order = p[3].toIntOrNull() ?: 0,
                        taskIds = if (p.size >= 6 && p[5].isNotBlank())
                            p[5].split("|").filter { it.isNotBlank() } else emptyList()
                    )
                )
            }
        } catch (e: Exception) {
        }
        return out
    }

    fun writeBlocks(c: Context, blocks: List<Block>): Boolean = try {
        c.getExternalFilesDir(null)?.mkdirs()
        blocksFile(c).writeText(
            BLOCKS_HEADER + "\n" +
                blocks.joinToString("\n") { b ->
                    listOf(
                        b.key, csvEscape(b.label), b.minutes.toString(),
                        b.order.toString(), b.id, b.taskIds.joinToString("|")
                    ).joinToString(",")
                } + if (blocks.isEmpty()) "" else "\n"
        )
        true
    } catch (e: Exception) {
        false
    }

    /**
     * A day's blocks: live ones in order, then parked ones at the foot.
     *
     * Parked blocks cost nothing \u2014 no hours, no effect on the start times
     * below them. They're a record that something was considered and set
     * aside, which is different from its never having been there.
     */
    fun blocksFor(c: Context, key: String): List<Block> {
        val mine = readBlocks(c).filter { it.key == key }.sortedBy { it.order }
        return mine.filterNot { it.isParked } + mine.filter { it.isParked }
    }

    fun plannedMinutes(c: Context, key: String): Int =
        blocksFor(c, key).filterNot { it.isParked }.sumOf { it.minutes }

    /** Start time of each live block, as minutes past midnight. */
    fun startTimes(day: PlanDay, blocks: List<Block>): Map<String, Int> {
        var t = day.startMinutes
        val out = mutableMapOf<String, Int>()
        blocks.filterNot { it.isParked }.forEach { b ->
            out[b.id] = t
            t += b.minutes
        }
        return out
    }

    // ---- editing ------------------------------------------------------------

    fun addBlock(c: Context, key: String, label: String, minutes: Int): Boolean {
        val all = readBlocks(c)
        val next = (all.filter { it.key == key }.maxOfOrNull { it.order } ?: -1) + 1
        return writeBlocks(c, all + Block(newId(), key, label, minutes, next))
    }

    fun setMinutes(c: Context, id: String, minutes: Int): Boolean =
        writeBlocks(c, readBlocks(c).map {
            if (it.id == id) it.copy(minutes = minutes.coerceAtLeast(0)) else it
        })

    fun deleteBlock(c: Context, id: String): Boolean =
        writeBlocks(c, readBlocks(c).filter { it.id != id })

    fun reorder(c: Context, key: String, idsInOrder: List<String>): Boolean {
        val all = readBlocks(c)
        val rank = idsInOrder.withIndex().associate { (i, id) -> id to i }
        return writeBlocks(c, all.map {
            if (it.key == key && rank.containsKey(it.id)) it.copy(order = rank[it.id]!!) else it
        })
    }

    fun moveToDay(c: Context, id: String, newKey: String): Boolean {
        val all = readBlocks(c)
        val next = (all.filter { it.key == newKey }.maxOfOrNull { it.order } ?: -1) + 1
        return writeBlocks(c, all.map {
            if (it.id == id) it.copy(key = newKey, order = next) else it
        })
    }

    /** Replaces [key]'s blocks with copies of [fromKey]'s. */
    fun copyDay(c: Context, fromKey: String, toKey: String): Boolean {
        if (fromKey == toKey) return true
        val all = readBlocks(c)
        val source = all.filter { it.key == fromKey }.sortedBy { it.order }
        val kept = all.filter { it.key != toKey }
        val copies = source.mapIndexed { i, b ->
            b.copy(id = newId(), key = toKey, order = i)
        }
        val ok = writeBlocks(c, kept + copies)
        if (ok) setDayStart(c, toKey, day(c, fromKey).startMinutes)
        return ok
    }

    fun clearDay(c: Context, key: String): Boolean =
        writeBlocks(c, readBlocks(c).filter { it.key != key })

    /** Removes a whole week: its days and their blocks. */
    fun deleteWeek(c: Context, keys: List<String>): Boolean {
        val ok = writeBlocks(c, readBlocks(c).filter { it.key !in keys })
        writeDays(c, readDays(c).values.filter { it.key !in keys })
        return ok
    }

    fun weekHasAnything(c: Context, keys: List<String>): Boolean =
        readBlocks(c).any { it.key in keys }

    /**
     * Adds any label a planned day is missing, as a parked block.
     *
     * A day's blocks are fixed when it's filled, so a label created afterwards
     * never appeared in it \u2014 there was no way to drag it into the schedule.
     * This puts it at the foot of every planned day from today onwards, greyed
     * and costing nothing, ready to be dragged up. The default week gets it too,
     * so weeks pulled in later include it.
     *
     * Only days that already have blocks: an unplanned day stays empty.
     * Past days are left alone \u2014 they're a record of what was planned then.
     * Returns true if anything was added.
     */
    fun addMissingLabelsAsParked(c: Context): Boolean {
        val library = LabelStore.readLibrary(c).sortedBy { it.manualOrder }
        if (library.isEmpty()) return false
        val today = dateKey(System.currentTimeMillis())

        val all = readBlocks(c)
        val byKey = all.groupBy { it.key }
        val added = mutableListOf<Block>()

        byKey.forEach { (key, blocks) ->
            // Dated keys sort as text, so a plain comparison finds today onwards.
            val eligible = isDefaultKey(key) || key >= today
            if (!eligible || blocks.isEmpty()) return@forEach

            val present = blocks.map { it.label }.toSet()
            var next = (blocks.maxOfOrNull { it.order } ?: -1) + 1
            library.filter { it.name !in present }.forEach { e ->
                added.add(Block(newId(), key, e.name, 0, next++))
            }
        }
        if (added.isEmpty()) return false
        return writeBlocks(c, all + added)
    }

    // ---- the default week ---------------------------------------------------

    /**
     * Fills all seven default days from the labels and goals on the main
     * screen \u2014 one tap to a plausible starting point, then thin out the
     * weekend.
     */
    fun fillDefaultFromGoals(c: Context): Boolean {
        val labels = LabelStore.readLibrary(c)
            .filter { it.goalMinutes > 0 }
            .sortedBy { it.manualOrder }
        if (labels.isEmpty()) return false

        val others = readBlocks(c).filter { !isDefaultKey(it.key) }
        val made = mutableListOf<Block>()
        for (d in 0..6) {
            labels.forEachIndexed { i, e ->
                made.add(Block(newId(), "D$d", e.name, e.goalMinutes, i))
            }
        }
        val ok = writeBlocks(c, others + made)
        if (ok) {
            val days = readDays(c).toMutableMap()
            for (d in 0..6) {
                val k = "D$d"
                days[k] = (days[k] ?: PlanDay(k, DEFAULT_START_MINUTES, false))
            }
            writeDays(c, days.values)
        }
        return ok
    }

    /**
     * Puts a saved layout into all seven days at once.
     *
     * The layout's manual order becomes the block order, and **labels with no
     * goal come through as parked blocks** — every label present at the foot
     * of each column, ready to be dragged up on the days it applies to. That's
     * quicker than adding them one at a time on the days that need them.
     */
    fun fillAllDaysFromLayout(c: Context, keys: List<String>, layout: SavedLayout): Boolean {
        val kept = readBlocks(c).filter { it.key !in keys }
        val made = mutableListOf<Block>()
        keys.forEach { key ->
            layout.entries.forEachIndexed { i, e ->
                made.add(Block(newId(), key, e.name, e.goalMinutes, i))
            }
        }
        val ok = writeBlocks(c, kept + made)
        if (ok) {
            val days = readDays(c).toMutableMap()
            keys.forEach { k ->
                days[k] = days[k] ?: PlanDay(k, DEFAULT_START_MINUTES, false)
            }
            writeDays(c, days.values)
        }
        return ok
    }

    /**
     * Creates the seven dated days of a week from the default week.
     *
     * Explicit rather than automatic: a week is blank until you ask for it.
     * Replaces whatever those dates held.
     */
    fun pullDefaultWeek(c: Context, dates: List<Long>): Boolean {
        val defaults = readBlocks(c).filter { isDefaultKey(it.key) }
        val keys = dates.map { dateKey(it) }
        val kept = readBlocks(c).filter { it.key !in keys }

        val made = mutableListOf<Block>()
        dates.forEach { ms ->
            val dk = dateKey(ms)
            defaults.filter { it.key == defaultKeyFor(ms) }
                .sortedBy { it.order }
                .forEachIndexed { i, b -> made.add(b.copy(id = newId(), key = dk, order = i)) }
        }
        val ok = writeBlocks(c, kept + made)
        if (ok) {
            val days = readDays(c).toMutableMap()
            dates.forEach { ms ->
                val dk = dateKey(ms)
                val src = days[defaultKeyFor(ms)]
                days[dk] = PlanDay(
                    dk, src?.startMinutes ?: DEFAULT_START_MINUTES, false
                )
            }
            writeDays(c, days.values)
        }
        return ok
    }

    /** True when a day differs from what its default weekday would give. */
    fun differsFromDefault(c: Context, dateMs: Long): Boolean {
        val mine = blocksFor(c, dateKey(dateMs)).map { it.label to it.minutes }
        val def = blocksFor(c, defaultKeyFor(dateMs)).map { it.label to it.minutes }
        return mine != def
    }

    // ---- applying to the main screen ----------------------------------------

    /**
     * The goals a day's plan implies: blocks summed by label, so two Work
     * blocks of 5h and 3h give one goal of 8h.
     */
    fun goalsFor(c: Context, key: String): Map<String, Int> =
        blocksFor(c, key)
            .filterNot { it.isParked }
            .groupBy { it.label }
            .mapValues { (_, list) -> list.sumOf { it.minutes } }

    /**
     * Puts a day's plan onto the main screen: its hours as goals, its block
     * order as the manual order, and the sort switched to Manual so that order
     * is what you see.
     *
     * **A parked block sets its label to zero.** Parking in the plan means "not
     * today", and applying used to skip it \u2014 so a parked label kept
     * yesterday's goal. A label the plan doesn't mention at all is different:
     * that isn't a decision, so it keeps its goal and follows in its existing
     * order.
     */
    fun applyToMainScreen(c: Context, key: String): Boolean {
        val live = goalsFor(c, key)
        val parked = blocksFor(c, key)
            .filter { it.isParked }
            .map { it.label }
            .filter { it !in live }              // a label split live-and-parked keeps its live hours
        val goals = live + parked.associateWith { 0 }
        val library = LabelStore.readLibrary(c)
        val names = library.map { it.name }.toSet()

        val planOrder = labelOrderFor(c, key).filter { it in names }
        val rest = library.sortedBy { it.manualOrder }.map { it.name }.filter { it !in planOrder }
        val withGoals = library.map { e -> goals[e.name]?.let { e.copy(goalMinutes = it) } ?: e }

        val ok = LabelStore.writeLibrary(c, LabelStore.applyManualOrder(withGoals, planOrder + rest))
        if (ok) {
            SettingsStore.setSortMode(c, SettingsStore.SORT_MANUAL)
            setApplied(c, key, true)
        }
        return ok
    }

    /**
     * Labels in the order the day's plan has them: live blocks top to bottom,
     * then parked ones. A label split across several blocks takes the position
     * of its first.
     */
    fun labelOrderFor(c: Context, key: String): List<String> =
        blocksFor(c, key).map { it.label }.distinct()

    // ---- helpers ------------------------------------------------------------

    private fun newId(): String =
        System.currentTimeMillis().toString(36) + (0..99999).random().toString(36)

    private fun csvEscape(v: String): String =
        if (v.contains(',') || v.contains('"')) "\"" + v.replace("\"", "\"\"") + "\"" else v

    private fun parseCsvLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var q = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                q && ch == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                ch == '"' -> q = !q
                ch == ',' && !q -> { out.add(sb.toString()); sb.clear() }
                else -> sb.append(ch)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }
}
