package com.focusledger.timer

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.focusledger.timer.databinding.ActivityRangeBinding
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Hours by label between two dates.
 *
 * Separate from the main screen on purpose. A range breaks three things there:
 * goals are daily and have no meaning across a fortnight, the progress bar
 * would be hundreds of percent over on every row, and the clock projections
 * plan a single day. None of those exist here.
 *
 * The bar on this screen is a **share of the range's total**, which is
 * something a range can actually have.
 */
class RangeActivity : AppCompatActivity() {

    private lateinit var b: ActivityRangeBinding

    private var fromMs = 0L
    private var toMs = 0L

    private val dayFmt = SimpleDateFormat("EEE d MMM", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityRangeBinding.inflate(layoutInflater)
        setContentView(b.root)

        fromMs = lastSundayMidnight()
        toMs = System.currentTimeMillis()

        b.rangeFrom.setOnClickListener { pickDate(true) }
        b.rangeTo.setOnClickListener { pickDate(false) }
        b.rangeDone.setOnClickListener { finish() }

        render()
    }

    /** Midnight at the start of the most recent Sunday, today included. */
    private fun lastSundayMidnight(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        // Calendar.SUNDAY is 1, so this is how many days to step back.
        add(Calendar.DAY_OF_YEAR, -(get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY))
    }.timeInMillis

    private fun startOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun pickDate(isStart: Boolean) {
        val c = Calendar.getInstance().apply { timeInMillis = if (isStart) fromMs else toMs }
        DatePickerDialog(
            this,
            { _, year, month, day ->
                val picked = Calendar.getInstance().apply {
                    set(year, month, day)
                    if (isStart) {
                        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                    } else {
                        // The end date is inclusive, so it runs to midnight.
                        set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59)
                    }
                    set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999)
                }.timeInMillis

                if (isStart) fromMs = picked else toMs = picked
                if (fromMs > toMs) {              // keep them the right way round
                    val t = fromMs; fromMs = toMs; toMs = t
                }
                render()
            },
            c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)
        ).apply {
            // Nothing is recorded in the future.
            datePicker.maxDate = System.currentTimeMillis()
        }.show()
    }

    /**
     * Completed runs come from the log; time recorded today is still only in
     * TimerStore's counters, so it's added separately. Doing both for the same
     * day would count it twice.
     */
    private fun totals(): Map<String, Long> {
        val todayStart = startOfToday()
        val logEnd = minOf(toMs, todayStart)
        val out = LogStore.sumByLabel(this, fromMs, logEnd).toMutableMap()

        if (toMs >= todayStart) {
            LabelStore.readLibrary(this).forEach { e ->
                val today = TimerStore.getDayMs(this, e.name)
                if (today != 0L) out[e.name] = (out[e.name] ?: 0L) + today
            }
        }
        return out
    }

    private fun render() {
        b.rangeFrom.text = dayFmt.format(Date(fromMs))
        b.rangeTo.text =
            if (toMs >= startOfToday()) "now" else dayFmt.format(Date(toMs))

        val sums = totals()
        // Every label, including those with nothing against them: a column of
        // zeros says which labels you're carrying but not using.
        val rows = LabelStore.readLibrary(this)
            .map { it.name to (sums[it.name] ?: 0L) }
            .sortedWith(compareByDescending<Pair<String, Long>> { it.second }
                .thenBy { it.first.lowercase() })

        val total = rows.fold(0L) { a, r -> a + r.second }
        val largest = rows.maxOfOrNull { it.second } ?: 0L

        val inflater = LayoutInflater.from(this)
        b.rangeList.removeAllViews()

        rows.forEach { (name, ms) ->
            val row = inflater.inflate(R.layout.row_range_entry, b.rangeList, false)
            row.findViewById<TextView>(R.id.rangeLabel).apply {
                text = name
                setTextColor(if (ms > 0L) 0xFFF1EDE3.toInt() else 0xFF5C736E.toInt())
            }
            row.findViewById<TextView>(R.id.rangeHours).apply {
                text = fmtHm(ms)
                setTextColor(if (ms > 0L) 0xFFA9BDB8.toInt() else 0xFF5C736E.toInt())
            }
            row.findViewById<TextView>(R.id.rangePercent).text =
                if (total > 0L && ms > 0L) "${ms * 100 / total}%" else ""

            // Scaled against the largest label rather than the total, or every
            // bar would be a sliver once there are a dozen labels.
            val bar = row.findViewById<View>(R.id.rangeBar)
            val rest = row.findViewById<View>(R.id.rangeBarRest)
            val share = if (largest > 0L) ms.toFloat() / largest else 0f
            (bar.layoutParams as LinearLayout.LayoutParams).weight = share
            (rest.layoutParams as LinearLayout.LayoutParams).weight = 1f - share
            bar.requestLayout(); rest.requestLayout()

            b.rangeList.addView(row)
        }

        b.rangeTotal.text = fmtHm(total)

        val days = ((minOf(toMs, System.currentTimeMillis()) - fromMs) / 86_400_000L + 1)
            .coerceAtLeast(1L)
        b.rangeAverage.text =
            "$days day${if (days == 1L) "" else "s"}  \u00b7  ${fmtHm(total / days)} a day"
    }

    /** h:mm. Seconds mean nothing across a week. */
    private fun fmtHm(ms: Long): String {
        val mins = (ms / 60_000L).toInt()
        return String.format("%d:%02d", mins / 60, mins % 60)
    }
}
