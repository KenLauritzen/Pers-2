package com.focusledger.timer

import android.content.Context
import java.io.File

/**
 * manualOrder sentinel for "never manually placed". Real positions start at 1,
 * so 0 is free to mean unplaced. Unplaced labels sort to the bottom in Manual
 * mode, in file order — which is creation order, so the newest label lands last.
 */
const val UNPLACED = 0

/**
 * A label in the library.
 *
 * @param manualOrder position used by Manual sort mode; kept separately from
 *        file order so switching sort modes never destroys a hand-built order.
 * @param visible retained only so older files parse; hiding was removed and
 *        nothing reads this to decide what to show.
 */
data class LabelEntry(
    val name: String,
    val goalMinutes: Int,
    val manualOrder: Int = UNPLACED,
    val visible: Boolean = true
)

/**
 * Label library, stored at
 *   Android/data/com.focusledger.timer/files/labels.txt
 *
 * One per line:  name,goalMinutes,manualOrder,visible
 * Shorter lines still parse — a bare "name" reads as goal 0, visible, and is
 * assigned a manual order by file position — so older files keep working.
 */
object LabelStore {

    private const val FILE_NAME = "labels.txt"

    private fun file(context: Context): File =
        File(context.getExternalFilesDir(null), FILE_NAME)

    fun readLibrary(context: Context): List<LabelEntry> {
        val out = mutableListOf<LabelEntry>()
        try {
            val f = file(context)
            if (f.exists()) {
                f.readLines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .forEachIndexed { i, line ->
                        val parts = line.split(",")
                        val name = parts[0].trim()
                        if (name.isEmpty()) return@forEachIndexed
                        val goal = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
                        val order = parts.getOrNull(2)?.trim()?.toIntOrNull() ?: UNPLACED
                        val vis = parts.getOrNull(3)?.trim()?.let { it == "1" || it.equals("true", true) } ?: true
                        out.add(LabelEntry(name, goal.coerceAtLeast(0), order, vis))
                    }
            }
        } catch (e: Exception) {
        }
        if (out.isEmpty()) {
            return listOf(
                LabelEntry("Work", 0, 1, true),
                LabelEntry("Other", 0, 2, true)
            )
        }
        return out
    }

    fun writeLibrary(context: Context, entries: List<LabelEntry>): Boolean {
        return try {
            context.getExternalFilesDir(null)?.mkdirs()
            file(context).writeText(
                entries.joinToString("\n") {
                    "${it.name},${it.goalMinutes},${it.manualOrder},${if (it.visible) 1 else 0}"
                } + "\n"
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    fun sortedBy(
        entries: List<LabelEntry>,
        mode: Int,
        remainingMs: (String) -> Long
    ): List<LabelEntry> = when (mode) {
        SettingsStore.SORT_ALPHA ->
            entries.sortedBy { it.name.lowercase() }
        SettingsStore.SORT_GOAL ->
            entries.sortedWith(
                compareByDescending<LabelEntry> { it.goalMinutes }.thenBy { it.name.lowercase() }
            )
        SettingsStore.SORT_REMAINING ->
            // No goal = nothing remaining to chase, so those sink to the bottom.
            entries.sortedWith(
                compareByDescending<LabelEntry> { if (it.goalMinutes <= 0) Long.MIN_VALUE else remainingMs(it.name) }
                    .thenBy { it.name.lowercase() }
            )
        else -> {
            // Manual: placed labels first in their arranged order, then any
            // never-placed ones at the bottom in creation (file) order.
            val placed = entries.filter { it.manualOrder != UNPLACED }
                .sortedBy { it.manualOrder }
            val unplaced = entries.filter { it.manualOrder == UNPLACED }
            placed + unplaced
        }
    }

    /** Renumbers manualOrder 1..n following the given name order. */
    fun applyManualOrder(entries: List<LabelEntry>, orderedNames: List<String>): List<LabelEntry> {
        val rank = orderedNames.withIndex().associate { (i, n) -> n to (i + 1) }
        return entries.map { e -> e.copy(manualOrder = rank[e.name] ?: UNPLACED) }
    }

    fun filePath(context: Context): String = file(context).absolutePath
}
