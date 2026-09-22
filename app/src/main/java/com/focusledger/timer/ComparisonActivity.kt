package com.focusledger.timer

import android.app.DatePickerDialog
import android.graphics.Typeface
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.focusledger.timer.databinding.ActivityComparisonBinding
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Your week against someone else's, label by label, and the time you'd aim to
 * spend together.
 *
 *   You      your weekly hours, filled to your waking week
 *   Them     theirs, entered in hand-over mode so they don't see yours
 *   Diff     You \u2212 Them, coloured by whoever wants more
 *   Tog.     a goal for time together; red if it outruns either person
 *
 * Planning only: nothing here touches a timer.
 */
class ComparisonActivity : AppCompatActivity() {

    companion object {
        /** Hold before a drag arms \u2014 the same as the week screen. */
        private const val HOLD_MS = 250L
        /** Weekly figures move in quarter hours when dragged. */
        private const val DRAG_STEP_MINUTES = 15

        private const val TEAL = 0xFF5FBFB3.toInt()
        private const val LAVENDER = 0xFFB9A6D8.toInt()
        private const val AMBER = 0xFFE8A33D.toInt()
        private const val WARN = 0xFFC97064.toInt()
        private const val PLAIN = 0xFFDCE6E3.toInt()
        private const val DIM = 0xFF5C736E.toInt()
    }

    private lateinit var b: ActivityComparisonBinding

    private var person: String = ""

    /** While on, your column is hidden so the other person fills theirs blind. */
    private var handedOver = false

    private val revisedFmt = SimpleDateFormat("d MMM yyyy", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityComparisonBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.compSeed.setOnClickListener { seedMenu() }
        b.compHandOver.setOnClickListener { handedOver = !handedOver; render() }
        b.compMore.setOnClickListener { moreMenu() }
        b.compDone.setOnClickListener { finish() }
        b.compTitle.setOnClickListener { choosePerson() }

        val people = TogetherStore.readPeople(this)
        if (people.isEmpty()) askForFirstPerson()
        else { person = people.first().name; render() }
    }

    // ---- people -------------------------------------------------------------

    private fun askForFirstPerson() {
        nameDialog("Who are you planning with?") { name ->
            if (name.isBlank()) { finish(); return@nameDialog }
            TogetherStore.addPerson(this, name)
            person = name.trim()
            render()
        }
    }

    private fun choosePerson() {
        val people = TogetherStore.readPeople(this)
        if (people.size < 2) return
        AlertDialog.Builder(this)
            .setTitle("Planning with")
            .setItems(people.map { it.name }.toTypedArray()) { _, w ->
                person = people[w].name; handedOver = false; render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun currentPerson(): Person? = TogetherStore.readPeople(this).firstOrNull { it.name == person }

    // ---- drawing ------------------------------------------------------------

    private fun render() {
        val p = currentPerson() ?: return
        val rows = TogetherStore.sheet(this, person)

        b.compTitle.text = if (handedOver) "$person \u2014 your week" else "Together \u2014 $person"
        b.compHandOver.text = if (handedOver) "Show both" else "Hand over"
        b.compHeadThem.text = person

        // Hand-over mode shows only their column. Anchoring on your numbers is
        // the thing it exists to prevent.
        val showMine = !handedOver
        b.compHeadMe.visibility = if (showMine) View.VISIBLE else View.INVISIBLE
        b.compHeadDiff.visibility = if (showMine) View.VISIBLE else View.INVISIBLE
        b.compHeadTog.visibility = if (showMine) View.VISIBLE else View.INVISIBLE
        b.compTotalMe.visibility = if (showMine) View.VISIBLE else View.GONE

        val myWaking = TogetherStore.WEEK_MINUTES - SettingsStore.getMySleepMinutes(this)
        val theirWaking = TogetherStore.WEEK_MINUTES - p.sleepMinutes
        val myTotal = rows.sumOf { it.me }
        val theirTotal = rows.sumOf { it.them }
        val togTotal = rows.sumOf { it.together }

        // Both names padded to the longer of the two, then a gap, so the
        // figures line up and never run into the name. Padding to a fixed six
        // left no space at all after "Janice".
        val nameWidth = maxOf("You".length, person.length) + 2
        b.compTotalMe.text = "You".padEnd(nameWidth) + runningLine(myTotal, myWaking)
        b.compTotalThem.text = person.padEnd(nameWidth) + runningLine(theirTotal, theirWaking)

        b.compStatus.text = when {
            handedOver -> "Your figures are hidden. Tap \u201cShow both\u201d when done."
            p.revisedAt > 0 -> "Together ${hm(togTotal)} a week \u00b7 revised ${revisedFmt.format(Date(p.revisedAt))}"
            else -> "Together ${hm(togTotal)} a week"
        }
        b.compStatus.setTextColor(0xFF8FA39E.toInt())

        val scrollY = b.compScroll.scrollY
        b.compRows.removeAllViews()
        val inflater = LayoutInflater.from(this)

        rows.forEach { r ->
            val row = inflater.inflate(R.layout.row_comparison, b.compRows, false)
            val label = row.findViewById<TextView>(R.id.cmpLabel)
            val me = row.findViewById<TextView>(R.id.cmpMe)
            val them = row.findViewById<TextView>(R.id.cmpThem)
            val diff = row.findViewById<TextView>(R.id.cmpDiff)
            val tog = row.findViewById<TextView>(R.id.cmpTog)

            label.text = r.label
            label.setTextColor(if (r.theirsOnly) LAVENDER else PLAIN)

            // Together is a goal that can outrun either person. When it does,
            // it and the figure it exceeds both go red, so it's clear which
            // needs to move.
            val overMe = r.together > r.me
            val overThem = r.together > r.them

            me.text = hmOrDash(r.me)
            me.setTextColor(if (overMe && r.together > 0) WARN else TEAL)

            them.text = hmOrDash(r.them)
            them.setTextColor(if (overThem && r.together > 0) WARN else LAVENDER)

            val d = r.me - r.them
            diff.text = when {
                d > 0 -> "+${hm(d)}"
                d < 0 -> "\u2212${hm(-d)}"
                else -> "0"
            }
            // Coloured by who wants more, not by sign: a difference of view
            // isn't a problem, so neither colour reads as a warning.
            diff.setTextColor(if (d > 0) TEAL else if (d < 0) LAVENDER else DIM)

            tog.text = hmOrDash(r.together)
            tog.setTextColor(
                if (r.together > 0 && (overMe || overThem)) WARN else AMBER
            )

            if (handedOver) {
                me.visibility = View.INVISIBLE
                diff.visibility = View.INVISIBLE
                tog.visibility = View.INVISIBLE
            } else {
                attachCell(me, r, TogetherStore.Field.ME, "You")
                attachCell(tog, r, TogetherStore.Field.TOGETHER, "Together")
            }
            attachCell(them, r, TogetherStore.Field.THEM, person)

            if (r.theirsOnly) {
                label.setOnClickListener { confirmDeleteTheirs(r.label) }
            }
            b.compRows.addView(row)
        }
        b.compScroll.post { b.compScroll.scrollTo(0, scrollY) }
    }

    /** "96:00 of 119:00 \u00b7 23:00 left", or how far over. */
    private fun runningLine(total: Int, waking: Int): String {
        val left = waking - total
        val tail = when {
            left > 0 -> "${hm(left)} left"
            left < 0 -> "${hm(-left)} over"
            else -> "full"
        }
        return "${hm(total)} of ${hm(waking)} \u00b7 $tail"
    }

    // ---- adjusting a figure -------------------------------------------------

    /**
     * Tap for exact control; hold then drag sideways for 15-minute steps.
     * The change and new total show in the status line while dragging, since
     * the cell itself is too narrow for both.
     */
    private fun attachCell(cell: TextView, r: SheetRow, field: TogetherStore.Field, who: String) {
        val d = resources.displayMetrics.density
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        val stepPx = 16 * d
        val start = valueOf(r, field)

        var startX = 0f
        var startY = 0f
        var armed = false
        var moved = false
        var pending = start

        val arm = Runnable {
            armed = true
            cell.parent?.requestDisallowInterceptTouchEvent(true)
            cell.setBackgroundColor(0x40E8A33D)
            cell.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }

        cell.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = ev.rawX; startY = ev.rawY
                    armed = false; moved = false; pending = start
                    cell.postDelayed(arm, HOLD_MS)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - startX
                    val dy = ev.rawY - startY
                    if (!armed) {
                        if (abs(dx) > slop || abs(dy) > slop) {
                            moved = true
                            cell.removeCallbacks(arm)
                        }
                        return@setOnTouchListener false
                    }
                    pending = (start + (dx / stepPx).toInt() * DRAG_STEP_MINUTES)
                        .coerceIn(0, TogetherStore.WEEK_MINUTES)
                    val delta = pending - start
                    val sign = when {
                        delta > 0 -> "+${hm(delta)}"
                        delta < 0 -> "\u2212${hm(-delta)}"
                        else -> "  0"
                    }
                    cell.text = hmOrDash(pending)
                    cell.setTextColor(AMBER)
                    b.compStatus.text = "$who \u00b7 ${r.label}   $sign   ${hm(pending)}"
                    b.compStatus.setTextColor(AMBER)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    cell.removeCallbacks(arm)
                    cell.parent?.requestDisallowInterceptTouchEvent(false)
                    val wasArmed = armed
                    val value = pending
                    val wasMoved = moved
                    // Cleared first, and everything posted: redrawing removes
                    // this view mid-gesture, which is what crashed the week
                    // screen before v85.
                    armed = false
                    when {
                        wasArmed -> cell.post {
                            if (value != start) TogetherStore.set(this, person, r.label, field, value)
                            render()
                        }
                        !wasMoved -> cell.post { adjustDialog(r, field, who) }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    cell.removeCallbacks(arm)
                    cell.parent?.requestDisallowInterceptTouchEvent(false)
                    val wasArmed = armed
                    armed = false
                    if (wasArmed) cell.post { render() }
                    true
                }
                else -> false
            }
        }
    }

    private fun valueOf(r: SheetRow, f: TogetherStore.Field) = when (f) {
        TogetherStore.Field.ME -> r.me
        TogetherStore.Field.THEM -> r.them
        TogetherStore.Field.TOGETHER -> r.together
    }

    private fun adjustDialog(r: SheetRow, field: TogetherStore.Field, who: String) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_adjust, null)
        val value = view.findViewById<TextView>(R.id.adjValue)
        view.findViewById<TextView>(R.id.adjTitle).text = "$who \u00b7 ${r.label}"
        var current = valueOf(r, field)
        value.text = hm(current)

        val dialog = AlertDialog.Builder(this).setView(view).create()
        fun step(m: Int) {
            current = (current + m).coerceIn(0, TogetherStore.WEEK_MINUTES)
            value.text = hm(current)
            TogetherStore.set(this, person, r.label, field, current)
        }
        mapOf(
            R.id.adjM5h to -300, R.id.adjM1h to -60, R.id.adjM15 to -15, R.id.adjM5 to -5,
            R.id.adjP5 to 5, R.id.adjP15 to 15, R.id.adjP1h to 60, R.id.adjP5h to 300
        ).forEach { (id, m) -> view.findViewById<Button>(id).setOnClickListener { step(m) } }

        view.findViewById<Button>(R.id.adjDone).setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener { render() }
        dialog.show()
    }

    // ---- seeding your column ------------------------------------------------

    private fun seedMenu() {
        if (handedOver) return
        AlertDialog.Builder(this)
            .setTitle("Fill your column from")
            .setItems(arrayOf("A 7-day range\u2026", "Every label at zero")) { _, w ->
                when (w) {
                    0 -> pickSeedStart()
                    1 -> confirmSeed(emptyMap())
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickSeedStart() {
        val c = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -7) }
        DatePickerDialog(
            this,
            { _, y, m, d ->
                val start = Calendar.getInstance().apply {
                    set(y, m, d, 0, 0, 0); set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                seedFromRange(start)
            },
            c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)
        ).apply { setTitle("First of the seven days") }.show()
    }

    /**
     * Planned and actual for the seven days, side by side. Planned is chosen
     * by default; tap a row to take the other figure for that label only.
     */
    private fun seedFromRange(startMs: Long) {
        val days = (0..6).map { i ->
            Calendar.getInstance().apply {
                timeInMillis = startMs; add(Calendar.DAY_OF_YEAR, i)
            }.timeInMillis
        }
        val planned = mutableMapOf<String, Int>()
        days.forEach { ms ->
            WeekStore.goalsFor(this, WeekStore.dateKey(ms)).forEach { (label, m) ->
                planned[label] = (planned[label] ?: 0) + m
            }
        }
        val actualRaw = LogStore.sumByLabel(this, startMs, startMs + 7 * 86_400_000L)
            .mapValues { (it.value / 60_000L).toInt() }

        // Both rounded to the nearest quarter hour, per label for the week.
        // Actual time arrives as odd minutes \u2014 34:23 \u2014 which clutters a table
        // meant for comparing. The preview shows the rounded figures, so what's
        // chosen is exactly what goes in.
        planned.replaceAll { _, m -> quarter(m) }
        val actual = actualRaw.mapValues { quarter(it.value) }

        val labels = LabelStore.readLibrary(this).sortedBy { it.manualOrder }.map { it.name }
        val useActual = mutableSetOf<String>()
        val fmt = SimpleDateFormat("d MMM", Locale.getDefault())

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF1A2C2A.toInt())
            setPadding(pad(16), pad(14), pad(16), pad(10))
        }
        container.addView(TextView(this).apply {
            text = "${fmt.format(Date(days.first()))} \u2013 ${fmt.format(Date(days.last()))}"
            setTextColor(AMBER); textSize = 17f; typeface = Typeface.DEFAULT_BOLD
        })
        // Columns line up with the rows below: name 14, marker, planned 7,
        // marker, actual 8 \u2014 33 characters, about 257dp at 13sp.
        container.addView(TextView(this).apply {
            text = "${"label".padEnd(14)}  ${"planned".padStart(7)}  ${"actual".padStart(8)}"
            setTextColor(0xFF8FA39E.toInt()); textSize = 13f; typeface = Typeface.MONOSPACE
            setPadding(0, pad(8), 0, pad(4))
        })

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val totals = TextView(this).apply {
            setTextColor(TEAL); textSize = 13f; typeface = Typeface.MONOSPACE
            setPadding(0, pad(8), 0, pad(8))
        }

        fun drawRows() {
            list.removeAllViews()
            var sum = 0
            labels.forEach { name ->
                val p = planned[name] ?: 0
                val a = actual[name] ?: 0
                val takeActual = name in useActual
                sum += if (takeActual) a else p
                list.addView(TextView(this).apply {
                    typeface = Typeface.MONOSPACE; textSize = 13f
                    setPadding(0, pad(7), 0, pad(7))
                    val pTxt = hmOrDash(p).padStart(7)
                    val aTxt = hmOrDash(a).padStart(8)
                    val mp = if (takeActual) " " else "\u2022"
                    val ma = if (takeActual) "\u2022" else " "
                    text = "${name.take(14).padEnd(14)} $mp$pTxt $ma$aTxt"
                    setTextColor(PLAIN)
                    setOnClickListener {
                        if (takeActual) useActual.remove(name) else useActual.add(name)
                        drawRows()
                    }
                })
            }
            val waking = TogetherStore.WEEK_MINUTES - SettingsStore.getMySleepMinutes(this)
            totals.text = "chosen ${hm(sum)} of ${hm(waking)}   \u2022 = chosen, tap to swap"
        }
        drawRows()

        container.addView(ScrollView(this).apply {
            addView(list)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, pad(340)
            )
        })
        container.addView(totals)

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        container.addView(buttons)

        val dialog = AlertDialog.Builder(this).setView(container).create()
        fun button(text: String, colour: Int, action: () -> Unit) {
            buttons.addView(Button(this).apply {
                this.text = text; isAllCaps = false; setTextColor(colour); textSize = 14f
                setBackgroundResource(R.drawable.bg_button_small)
                layoutParams = LinearLayout.LayoutParams(0, pad(44), 1f).apply {
                    marginEnd = pad(4)
                }
                setOnClickListener { action() }
            })
        }
        button("All planned", PLAIN) { useActual.clear(); drawRows() }
        button("All actual", PLAIN) { useActual.addAll(labels); drawRows() }
        button("Use these", AMBER) {
            dialog.dismiss()
            confirmSeed(labels.associateWith { n ->
                if (n in useActual) actual[n] ?: 0 else planned[n] ?: 0
            })
        }
        dialog.show()
    }

    private fun confirmSeed(values: Map<String, Int>) {
        val any = TogetherStore.sheet(this, person).any { it.me > 0 }
        val apply = { TogetherStore.seedMine(this, person, values); render() }
        if (!any) { apply(); return }
        AlertDialog.Builder(this)
            .setTitle("Replace your column?")
            .setMessage("Your current figures are overwritten. $person's column and the Together goals are kept.")
            .setPositiveButton("Replace") { _, _ -> apply() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- more ---------------------------------------------------------------

    private fun moreMenu() {
        val items = listOf(
            "Add a label only $person has\u2026",
            "Sleep goals\u2026",
            "Plan with someone else\u2026"
        )
        AlertDialog.Builder(this)
            .setItems(items.toTypedArray()) { _, w ->
                when (w) {
                    0 -> nameDialog("A label only $person has") { name ->
                        if (name.isNotBlank() && !TogetherStore.addTheirsOnly(this, person, name))
                            Toast.makeText(this, "That label is already here", Toast.LENGTH_SHORT).show()
                        render()
                    }
                    1 -> sleepDialog()
                    2 -> nameDialog("Plan with") { name ->
                        if (name.isBlank()) return@nameDialog
                        TogetherStore.addPerson(this, name)
                        person = name.trim(); handedOver = false; render()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Each person has their own, so each column fills to their own week. */
    private fun sleepDialog() {
        val p = currentPerson() ?: return
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_text_input, null)
        view.findViewById<TextView>(R.id.inputTitle).text = "Sleep, hours a week"
        val mine = view.findViewById<EditText>(R.id.inputPrimary)
        val theirs = view.findViewById<EditText>(R.id.inputSecondary)
        mine.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        mine.hint = "You"
        mine.setText((SettingsStore.getMySleepMinutes(this) / 60).toString())
        theirs.visibility = View.VISIBLE
        theirs.hint = person
        theirs.setText((p.sleepMinutes / 60).toString())

        val dialog = AlertDialog.Builder(this).setView(view).create()
        view.findViewById<Button>(R.id.inputSave).setOnClickListener {
            mine.text.toString().toIntOrNull()?.let {
                SettingsStore.setMySleepMinutes(this, it * 60)
            }
            theirs.text.toString().toIntOrNull()?.let {
                TogetherStore.setSleep(this, person, it * 60)
            }
            dialog.dismiss(); render()
        }
        view.findViewById<Button>(R.id.inputCancel).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun confirmDeleteTheirs(label: String) {
        AlertDialog.Builder(this)
            .setTitle("Remove \u201c$label\u201d?")
            .setMessage("It's only in this comparison, so nothing else is affected.")
            .setPositiveButton("Remove") { _, _ ->
                TogetherStore.deleteTheirsOnly(this, person, label); render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun nameDialog(title: String, onName: (String) -> Unit) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_text_input, null)
        view.findViewById<TextView>(R.id.inputTitle).text = title
        val field = view.findViewById<EditText>(R.id.inputPrimary)
        field.setSingleLine(true)
        field.hint = "Name"
        val dialog = AlertDialog.Builder(this).setView(view).create()
        view.findViewById<Button>(R.id.inputSave).setOnClickListener {
            dialog.dismiss(); onName(field.text.toString().trim())
        }
        view.findViewById<Button>(R.id.inputCancel).setOnClickListener {
            dialog.dismiss(); onName("")
        }
        dialog.show()
    }

    // ---- helpers ------------------------------------------------------------

    private fun pad(dp: Int) = (dp * resources.displayMetrics.density).toInt()

    /** Nearest 15 minutes: 7 rounds down, 8 rounds up. */
    private fun quarter(minutes: Int): Int = Math.round(minutes / 15.0).toInt() * 15

    private fun hm(minutes: Int): String = String.format("%d:%02d", minutes / 60, minutes % 60)

    private fun hmOrDash(minutes: Int): String = if (minutes <= 0) "\u2014" else hm(minutes)
}
