package com.focusledger.timer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One label as captured in a layout. */
data class LayoutEntry(
    val name: String,
    val visible: Boolean,
    val goalMinutes: Int
)

/**
 * A saved arrangement: which labels are shown, in what order, and what their
 * daily goals were. Recalling a layout restores all three, so "First Saturday"
 * can carry different targets from a weekday.
 */
data class SavedLayout(
    val name: String,
    val entries: List<LayoutEntry>
) {
    val order get() = entries.map { it.name }
}

/** What changed between a layout and the library when it was applied. */
data class LayoutDiff(val added: List<String>, val removed: List<String>) {
    val isEmpty get() = added.isEmpty() && removed.isEmpty()
}

/**
 * Named layouts, stored as JSON beside labels.txt:
 *   Android/data/com.focusledger.timer/files/layouts.json
 *
 * A nested list doesn't fit labels.txt's flat one-per-line format, so this
 * gets its own file.
 */
object LayoutStore {

    private const val FILE_NAME = "layouts.json"

    private fun file(context: Context): File =
        File(context.getExternalFilesDir(null), FILE_NAME)

    fun readAll(context: Context): List<SavedLayout> {
        val out = mutableListOf<SavedLayout>()
        try {
            val f = file(context)
            if (!f.exists()) return out
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val name = o.optString("name", "").trim()
                if (name.isEmpty()) continue

                val entries = mutableListOf<LayoutEntry>()
                val entriesArr = o.optJSONArray("entries")
                if (entriesArr != null) {
                    for (j in 0 until entriesArr.length()) {
                        val e = entriesArr.getJSONObject(j)
                        val n = e.optString("name", "").trim()
                        if (n.isEmpty()) continue
                        entries.add(
                            LayoutEntry(
                                n,
                                e.optBoolean("visible", true),
                                e.optInt("goal", 0).coerceAtLeast(0)
                            )
                        )
                    }
                }
                if (entries.isNotEmpty()) out.add(SavedLayout(name, entries))
            }
        } catch (e: Exception) {
        }
        return out.sortedBy { it.name.lowercase() }
    }

    private fun writeAll(context: Context, layouts: List<SavedLayout>): Boolean {
        return try {
            context.getExternalFilesDir(null)?.mkdirs()
            val arr = JSONArray()
            layouts.forEach { l ->
                val entries = JSONArray()
                l.entries.forEach { e ->
                    entries.put(JSONObject().apply {
                        put("name", e.name)
                        put("visible", e.visible)
                        put("goal", e.goalMinutes)
                    })
                }
                arr.put(JSONObject().apply {
                    put("name", l.name)
                    put("entries", entries)
                })
            }
            file(context).writeText(arr.toString(2))
            true
        } catch (e: Exception) {
            false
        }
    }

    fun exists(context: Context, name: String): Boolean =
        readAll(context).any { it.name.equals(name.trim(), ignoreCase = true) }

    /** Saves under [name], replacing any existing layout with that name. */
    fun save(context: Context, name: String, library: List<LabelEntry>): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false

        val ordered = LabelStore.sortedBy(library, SettingsStore.SORT_MANUAL) { 0L }
        val layout = SavedLayout(
            name = trimmed,
            entries = ordered.map { LayoutEntry(it.name, it.visible, it.goalMinutes) }
        )
        val others = readAll(context).filter { !it.name.equals(trimmed, ignoreCase = true) }
        return writeAll(context, others + layout)
    }

    fun rename(context: Context, from: String, to: String): Boolean {
        val trimmed = to.trim()
        if (trimmed.isEmpty()) return false
        val all = readAll(context).toMutableList()
        val i = all.indexOfFirst { it.name == from }
        if (i < 0) return false
        all[i] = all[i].copy(name = trimmed)
        return writeAll(context, all)
    }

    fun delete(context: Context, name: String): Boolean =
        writeAll(context, readAll(context).filter { it.name != name })

    /**
     * Reconciles [layout] against the current library, restoring order,
     * visibility and goals.
     *
     * Labels created since the layout was saved go to the bottom and keep
     * their current goal — the layout knows nothing about them, and
     * re-targeting something just created would be surprising.
     * Labels deleted since are simply absent.
     */
    fun apply(
        context: Context,
        layout: SavedLayout,
        library: List<LabelEntry>
    ): Pair<List<LabelEntry>, LayoutDiff> {
        val libraryNames = library.map { it.name }
        val byName = layout.entries.associateBy { it.name }

        val known = layout.order.filter { libraryNames.contains(it) }
        val added = libraryNames.filter { !byName.containsKey(it) }
        val removed = layout.order.filter { !libraryNames.contains(it) }

        val updated = LabelStore.applyManualOrder(library, known + added).map { e ->
            val saved = byName[e.name] ?: return@map e
            // Visibility is no longer applied: hiding was removed. The field
            // is still stored so layouts saved by older versions still load.
            e.copy(goalMinutes = saved.goalMinutes)
        }
        return updated to LayoutDiff(added, removed)
    }
}
