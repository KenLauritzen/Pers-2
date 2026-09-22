package com.focusledger.timer

import android.content.Context
import java.io.File

/** Someone you plan time with. */
data class Person(
    val name: String,
    /** Their weekly sleep goal. Their waking week is 168h less this. */
    val sleepMinutes: Int,
    /** When the sheet with them was last changed. 0 if never. */
    val revisedAt: Long = 0L
)

/** One label's line in a comparison sheet. All figures are weekly minutes. */
data class SheetRow(
    val person: String,
    val label: String,
    val me: Int,
    val them: Int,
    val together: Int,
    /**
     * A label only they have. It lives in this sheet and never reaches your
     * main screen.
     */
    val theirsOnly: Boolean,
    val order: Int
)

/**
 * The comparison sheet: your week and someone else's, label by label, with
 * the time you'd aim to spend together.
 *
 * Standing, not weekly \u2014 revised whenever you sit down together again.
 *
 *   together_people.csv   name,sleep_minutes,revised_at
 *   together_sheet.csv    person,label,me_minutes,their_minutes,
 *                         together_minutes,theirs_only,sort_order
 */
object TogetherStore {

    private const val PEOPLE_FILE = "together_people.csv"
    private const val SHEET_FILE = "together_sheet.csv"
    private const val PEOPLE_HEADER = "name,sleep_minutes,revised_at"
    private const val SHEET_HEADER =
        "person,label,me_minutes,their_minutes,together_minutes,theirs_only,sort_order"

    const val WEEK_MINUTES = 168 * 60
    const val DEFAULT_SLEEP_MINUTES = 56 * 60

    private fun peopleFile(c: Context) = File(c.getExternalFilesDir(null), PEOPLE_FILE)
    private fun sheetFile(c: Context) = File(c.getExternalFilesDir(null), SHEET_FILE)

    // ---- people -------------------------------------------------------------

    fun readPeople(c: Context): List<Person> {
        val out = mutableListOf<Person>()
        try {
            val f = peopleFile(c)
            if (!f.exists()) return out
            f.readLines().drop(1).forEach { line ->
                if (line.isBlank()) return@forEach
                val p = parseCsvLine(line)
                if (p.isEmpty() || p[0].isBlank()) return@forEach
                out.add(
                    Person(
                        name = p[0],
                        sleepMinutes = p.getOrNull(1)?.toIntOrNull() ?: DEFAULT_SLEEP_MINUTES,
                        revisedAt = p.getOrNull(2)?.toLongOrNull() ?: 0L
                    )
                )
            }
        } catch (e: Exception) {
        }
        return out
    }

    private fun writePeople(c: Context, people: List<Person>): Boolean = try {
        c.getExternalFilesDir(null)?.mkdirs()
        peopleFile(c).writeText(
            PEOPLE_HEADER + "\n" +
                people.joinToString("\n") {
                    "${csvEscape(it.name)},${it.sleepMinutes},${it.revisedAt}"
                } + if (people.isEmpty()) "" else "\n"
        )
        true
    } catch (e: Exception) {
        false
    }

    fun addPerson(c: Context, name: String): Boolean {
        val people = readPeople(c)
        if (people.any { it.name.equals(name, ignoreCase = true) }) return false
        return writePeople(c, people + Person(name.trim(), DEFAULT_SLEEP_MINUTES))
    }

    fun setSleep(c: Context, name: String, minutes: Int) {
        writePeople(c, readPeople(c).map {
            if (it.name == name) it.copy(sleepMinutes = minutes.coerceIn(0, WEEK_MINUTES)) else it
        })
    }

    private fun touch(c: Context, name: String) {
        writePeople(c, readPeople(c).map {
            if (it.name == name) it.copy(revisedAt = System.currentTimeMillis()) else it
        })
    }

    // ---- the sheet ----------------------------------------------------------

    private fun readAllRows(c: Context): List<SheetRow> {
        val out = mutableListOf<SheetRow>()
        try {
            val f = sheetFile(c)
            if (!f.exists()) return out
            f.readLines().drop(1).forEach { line ->
                if (line.isBlank()) return@forEach
                val p = parseCsvLine(line)
                if (p.size < 7) return@forEach
                out.add(
                    SheetRow(
                        person = p[0], label = p[1],
                        me = p[2].toIntOrNull() ?: 0,
                        them = p[3].toIntOrNull() ?: 0,
                        together = p[4].toIntOrNull() ?: 0,
                        theirsOnly = p[5] == "1",
                        order = p[6].toIntOrNull() ?: 0
                    )
                )
            }
        } catch (e: Exception) {
        }
        return out
    }

    private fun writeAllRows(c: Context, rows: List<SheetRow>): Boolean = try {
        c.getExternalFilesDir(null)?.mkdirs()
        sheetFile(c).writeText(
            SHEET_HEADER + "\n" +
                rows.joinToString("\n") { r ->
                    listOf(
                        csvEscape(r.person), csvEscape(r.label),
                        r.me.toString(), r.them.toString(), r.together.toString(),
                        if (r.theirsOnly) "1" else "0", r.order.toString()
                    ).joinToString(",")
                } + if (rows.isEmpty()) "" else "\n"
        )
        true
    } catch (e: Exception) {
        false
    }

    /**
     * The sheet with [person], guaranteed to hold every one of your labels.
     *
     * Yours in their manual order, then theirs-only labels. A label you've
     * added since the sheet was made joins it at zero, the same way new labels
     * join planned days in the week screen.
     */
    fun sheet(c: Context, person: String): List<SheetRow> {
        val all = readAllRows(c)
        val mine = all.filter { it.person == person }
        val library = LabelStore.readLibrary(c).sortedBy { it.manualOrder }
        val present = mine.map { it.label }.toSet()

        val missing = library.filter { it.name !in present }
        if (missing.isNotEmpty()) {
            var next = (mine.maxOfOrNull { it.order } ?: -1) + 1
            val added = missing.map {
                SheetRow(person, it.name, 0, 0, 0, false, next++)
            }
            writeAllRows(c, all + added)
            return sheet(c, person)
        }

        val rank = library.withIndex().associate { (i, e) -> e.name to i }
        val yours = mine.filter { !it.theirsOnly }
            .sortedBy { rank[it.label] ?: (Int.MAX_VALUE / 2 + it.order) }
        val theirs = mine.filter { it.theirsOnly }.sortedBy { it.order }
        return yours + theirs
    }

    /** Which figure in a row. */
    enum class Field { ME, THEM, TOGETHER }

    fun set(c: Context, person: String, label: String, field: Field, minutes: Int) {
        val v = minutes.coerceIn(0, WEEK_MINUTES)
        writeAllRows(c, readAllRows(c).map { r ->
            if (r.person != person || r.label != label) r
            else when (field) {
                Field.ME -> r.copy(me = v)
                Field.THEM -> r.copy(them = v)
                Field.TOGETHER -> r.copy(together = v)
            }
        })
        touch(c, person)
    }

    /** Replaces your whole column, from a seed. Labels not given go to zero. */
    fun seedMine(c: Context, person: String, minutesByLabel: Map<String, Int>) {
        sheet(c, person)                                   // make sure rows exist
        writeAllRows(c, readAllRows(c).map { r ->
            if (r.person != person) r
            else r.copy(me = (minutesByLabel[r.label] ?: 0).coerceIn(0, WEEK_MINUTES))
        })
        touch(c, person)
    }

    fun addTheirsOnly(c: Context, person: String, label: String): Boolean {
        val all = readAllRows(c)
        if (all.any { it.person == person && it.label.equals(label, ignoreCase = true) }) return false
        val next = (all.filter { it.person == person }.maxOfOrNull { it.order } ?: -1) + 1
        val ok = writeAllRows(c, all + SheetRow(person, label.trim(), 0, 0, 0, true, next))
        if (ok) touch(c, person)
        return ok
    }

    fun deleteTheirsOnly(c: Context, person: String, label: String) {
        writeAllRows(c, readAllRows(c).filterNot {
            it.person == person && it.label == label && it.theirsOnly
        })
        touch(c, person)
    }

    // ---- helpers ------------------------------------------------------------

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
