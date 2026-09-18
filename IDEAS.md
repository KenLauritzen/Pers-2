# Ideas

Enhancements for Work Ledger. **Open ideas** first, then a **History** of what
shipped and in which version.

Ideas keep their original numbers permanently, so a number always refers to the
same thing whether it's open or built. The numbering is therefore not
sequential within either section.

---

# Open ideas

Not yet built. Each carries its open questions, to be settled before the work
starts.

---

## 7. Scheduled layouts

*Rewritten. This was originally "scheduled visibility" — a schedule attached to
each individual label. Idea 20 (named layouts) and the removal of per-label
hiding (idea 24) make that unnecessary: a schedule now attaches to a **layout**,
which already carries order and goals.*

**What:** Give a saved layout a schedule, so it applies itself on the right
days and you never have to remember which one to pick.

| Schedule | Shows as | Meaning |
|---|---|---|
| Weekdays | `MWF` | Mon, Wed, Fri |
| Weekdays | `TH` | Tue, Thu |
| Weekdays | `SA` | Sun, Sat |
| Day of month | `5, 20` | the 5th and 20th |
| Every N days | `X3` | every 3rd day |
| None | *(blank)* | manual only |

Day letters use the **S M T W H F A** convention, so Sunday and Saturday stay
distinct (S / A), as do Tuesday and Thursday (T / H).

**Why:** "First Saturday of the month" is a recurring shape of day. Picking the
layout by hand works, but the whole point of naming it was to stop thinking
about it.

**Why this is far simpler than the original design:**
- One schedule per **layout**, not per label. A dozen labels sharing a Saturday
  pattern is one rule, not twelve.
- No carry-over logic. A layout isn't a task you can fall behind on — if
  Saturday's layout didn't apply because the app wasn't opened, applying it on
  Sunday would be wrong, not helpful.
- No override row. Layouts can already be picked manually at any time, which
  *is* the override.
- No red styling for missed days, no anchor dates for most cases, no
  per-label schedule editor.

**Notes:**
- Applies on cold start, and on the midnight rollover if the app is open.
- Should almost certainly **prompt rather than apply silently** — "Apply
  ‘First Saturday’?" — since a layout rewrites goals and order, and having
  that happen unannounced would be alarming. See 7.11.
- Schedule lives in `layouts.json` beside the entries, so nothing else changes.
- `X3` still needs an anchor. For a layout the sensible one is the date it was
  last applied.

**Open questions — to answer before building:**

| # | Question | Leaning | Answer |
|---|---|---|---|
| 7.11 | Apply automatically, or prompt first? | Prompt — silently rewriting goals and order would be alarming | |
| 7.12 | If two layouts both match today (say an `MWF` and a `5, 20`), which wins? | Most specific: day-of-month beats every-N beats weekday | |
| 7.13 | If a layout was applied today and you then rearrange by hand, should it re-apply on the next cold start that day? | No — once applied for a date, don't apply again that date | |
| 7.14 | Where is a layout's schedule set — in the layout picker via long-press, alongside Rename and Delete? | Yes, same menu | |
| 7.15 | Should today's scheduled layout be shown somewhere on the main screen, so you know one is in effect? | Yes, small text in the header | |

---

## 9. Highlightable lines within notes

**What:** Inside a note, each line can be tagged, cycling through three states
by tapping it:

| State | Meaning | Suggested look |
|---|---|---|
| Plain | ordinary note text | normal |
| **Favorite** | still relevant, worth surfacing | amber star, brighter text |
| **Was a favorite** | mattered once, no longer current | dim star, muted text |

The point of the third state is that un-starring shouldn't erase the fact that
something was important — it just stops competing for attention.

A **Favorites view** then filters to starred lines only, across every note and
every day, so the useful parts of long conversations are reachable without
scrolling through everything around them.

**Why:** A two-hour call produces a lot of text, of which three lines matter
next week. Without this, finding them means re-reading the whole note; with it,
the good parts stay one tap away and stale ones fade out without being deleted.

**Notes:**
- Requires notes to become **line-structured** rather than one blob. Each line
  needs its own state, so the note field has to carry per-line markers.
- Keeps the single CSV `note` column if each line is prefixed:
  `*` favorite, `~` was-favorite, no prefix plain, with lines joined by a
  character that isn't a comma or newline (`|` would work, escaped in the CSV
  as it already is for quotes).
- Depends on idea 8 — there's no sensible place to tap a line until the note
  sheet with history exists. Build 8 first.
- Editing history: tapping a line in a *past* note changes that CSV row, which
  the existing atomic rewrite already handles for the last row but would need
  extending to any row.

**Open questions — to answer before building:**

| # | Question | Leaning | Answer |
|---|---|---|---|
| 9.1 | How does a line get tagged — tap the line itself, or a star at its edge? | Star at the edge; tapping text should place the cursor for editing | |
| 9.2 | Does the cycle go plain → favorite → was-favorite → plain, or is "was" only reachable from favorite? | Full cycle, so any state is a few taps away | |
| 9.3 | Where does the Favorites view live — inside the note sheet filtered to one label, a global screen, or both? | Both: a filter toggle in the sheet, and a global view later | |
| 9.4 | Do "was a favorite" lines appear in the Favorites view, greyed, or drop out entirely? | Drop out of the default view, with a toggle to include them | |
| 9.5 | What counts as a "line" — every newline you type, or sentence detection? | Newlines only; sentence detection guesses wrong too often | |
| 9.6 | Should favorites be searchable by text later on? | Yes, but a separate idea once this exists | |

---

## 26. Export reminder, and knowing when you last exported  — LOW PRIORITY

**What:** Track the date of the last export, show it somewhere, and remind
after a chosen interval — weekly by default.

**Why:** Uninstalling wipes everything: labels, layouts, goals and the whole
CSV log. Nothing currently tells you how long it's been since you had a copy.

**Deferred.** There isn't enough accumulated history yet for loss to matter
much. Worth revisiting once a few months of log has built up.

**Notes — check this first:**
- The manifest already sets `android:allowBackup="true"`, and Android's Auto
  Backup includes the app's external files directory by default. **Google may
  already be backing this up**, in which case a reinstall would restore it.
  Worth verifying on the device before building anything, because it changes
  what the reminder is for: a controlled copy you can analyse, versus the only
  thing preventing real loss.
- A reminder that can be dismissed protects nothing on its own. Consider
  having the app **write a dated copy automatically** to its own folder — no
  interaction, no way to forget — with the reminder being about getting a copy
  *off the phone*.
- Last-export date is one value in preferences; the reminder can reuse the
  existing notification channel.

**Open questions — to answer before building:**

| # | Question | Leaning | Answer |
|---|---|---|---|
| 26.1 | Does Android Auto Backup already cover this on your phone? | Verify before building | |
| 26.2 | Automatic dated copies in the app folder, as well as the reminder? | Yes — an unmissable safeguard costs almost nothing | |
| 26.3 | Where does "last exported" show — Settings, or the main header? | Settings, beside the export button | |
| 26.4 | Interval: fixed weekly, or configurable? | Configurable in days, defaulting to 7 | |
| 26.5 | Should the reminder escalate if ignored for a long time? | No — nagging gets dismissed reflexively | |

---

## 32. Approaching-goal warning

**What:** A warning a set number of minutes before the running timer reaches
its goal — five by default, adjustable in Settings.

- A **toggle on the main screen**, bottom left, to turn it on or off without
  opening Settings. It's the kind of thing you'd want on for a focused
  afternoon and off the rest of the time.
- **Vibration alongside the sound** when sound is on, so it registers without
  needing to be heard.
- If sound and notifications are both off, **a popup instead**, so the warning
  can't pass unnoticed.

**Why:** With the Start and ETA columns turning the list into a schedule, the
useful moment isn't when a goal is passed — it's shortly before, while there's
still time to wrap up and move on.

**Notes:**
- Distinct from the existing reminder, which fires on an interval regardless of
  goals. This one fires relative to *this label's* goal, so it happens once per
  label per day rather than repeatedly.
- Most of the parts exist: `TimerForegroundService` already ticks every 30
  seconds, owns the notification channels, and knows the remaining time via
  `TimerStore.getRemainingMs`.
- Needs a per-label, per-day "already warned" flag, or it would re-fire on
  every tick for the last five minutes.
- Vibration needs the `VIBRATE` permission, which isn't in the manifest yet.
- The bottom row is currently `Settings | Stop | Exit` weighted 1:2:1. A
  toggle in the bottom-left means either shrinking those or putting it in the
  row above beside the totals.

**Open questions — to answer before building:**

| # | Question | Leaning | Answer |
|---|---|---|---|
| 32.1 | Where exactly does the toggle go, given the bottom row is full? | Left end of the totals row, where there's space beside the −5m/+5m buttons | |
| 32.2 | Does it fire once per label per day, or every time that label runs? | Once per label per day — restarting a timer you've already been warned about shouldn't warn again | |
| 32.3 | What happens if a label is already past its goal when started? | No warning — there's nothing to approach | |
| 32.4 | Should the warning interval be per-label, or one setting for all? | One setting; per-label would be a lot of configuration for a small gain | |
| 32.5 | Should it also warn at the goal itself, or only before? | Only before — the bar already turns red at the goal | |
| 32.6 | Vibrate even when sound is off but notifications are on? | Yes — vibration is the point when sound isn't wanted | |

---

## 34. Help page

**What:** A help screen reached from Settings, listing every gesture and
feature.

**Why:** The app has accumulated roughly eighteen distinct interactions, and
several — long-press a row for the popup, long-press the note icon to drag,
tap a label name to reorder — are invisible until someone says so. There's
currently no way to rediscover a gesture you've forgotten.

**What it would cover:**

*Main screen*
| Gesture | Does |
|---|---|
| Tap a row | Start that timer |
| Long-press a row | Goal / time / delete popup |
| Tap the label name | Move that label above another (Manual order only) |
| Tap the note icon | Notes for that label |
| Long-press the note icon | Drag to reorder (Manual order only) |
| Tap `+ New label` | Add a label, then set its goal |
| Tap the start time, top left | Set the time the day plans from |
| Tap the order button | Sort mode, plus save and open layouts |
| Tap the column button | Choose what the right column shows |
| Tap `Day` / `Ses` | Totals for the day, or this session |
| `−5m` / `+5m` | Adjust the running timer |
| `Stop` / `Exit` | Stop the timer / end the session and close |

*Notes screen*
| Gesture | Does |
|---|---|
| Type in the bottom field | Note for the run in progress, saved as you type |
| Tap a past entry | Edit that run's note |
| `Show runs with no note` | Include runs you didn't annotate |

*What the columns and colours mean* — the six column modes, the `Σ` / `@` / `~`
markers, the green and red bar, and the amber border on the running row.

**Notes:**
- **It has to be maintained.** A help page that's out of date is worse than
  none, because it teaches gestures that no longer work. Every build that
  changes an interaction has to update it in the same commit.
- Simplest form is a scrolling screen of static text — no interaction, no
  state, nothing to go wrong.
- Writing it will probably expose interactions that are hard to justify. That's
  useful in itself: anything awkward to explain is worth reconsidering.

**Open questions — to answer before building:**

| # | Question | Leaning | Answer |
|---|---|---|---|
| 34.1 | One long scrolling page, or sections you expand? | One page — scrolling is easier than hunting through collapsed sections | |
| 34.5 | Lay it out as the four sections across, matching the screen, with tap / double-tap / long-press down the side? | Yes — it mirrors what you're looking at | |
| 34.6 | Landscape too? | Once idea 49 exists | |
| 34.2 | Should it show on first launch, or only when opened from Settings? | Settings only. First-launch help gets dismissed unread | |
| 34.3 | Include the file locations and export explanation, or leave those in Settings? | Include them; it's the natural place to explain where data lives | |
| 34.4 | Should it carry the version number, so it's obvious which build it describes? | Yes | |

---

## 47. Sleep timer — stop everything at a set time  ⭐ HIGH

**What:** A time after which any running timer stops on its own.

**Why:** A timer left running overnight silently ruins a day's data, and the
correction afterwards is guesswork.

**Notes:**
- The service already ticks and already splits runs at midnight, so the
  machinery is there.
- Interacts with idea 57: an after-hours prompt that needs acknowledging is
  the gentler version of the same idea, and a hard stop is the backstop.

| # | Question | Leaning | Answer |
|---|---|---|---|
| 47.1 | Hard stop, or prompt first with a stop on no answer? | Prompt, then stop — a silent stop loses real time too | |
| 47.2 | One time for every label, or per label? | One, in Settings | |
| 47.3 | Should it write a note on the run saying it was auto-stopped? | Yes — otherwise the row looks like a deliberate stop | |

---

## 48. Hide rows once the goal is met  ⭐ HIGH

**What:** A toggle that hides any row whose goal is met, and any row with no
goal. A tick mark marks a label done for the day. Next day it reappears.

**Why:** Late in the day the list is mostly finished work. What's left is what
matters.

**Notes:**
- **This is per-label hiding returning in a different form.** Idea 24 removed
  it because nothing could be unhidden. This version is safe because hiding is
  a computed state, not a stored one — flip the toggle and everything is back.
- With the toggle off, a done label shows its tick.

| # | Question | Leaning | Answer |
|---|---|---|---|
| 48.1 | Is the tick separate from meeting the goal, or the same thing? | Separate — "I'm finished with this" isn't always "I hit the number" | |
| 48.2 | Does a ticked label still count toward the totals? | Yes; it's real time | |
| 48.3 | Where does the toggle live? | Beside the sort pill | |
| 48.4 | Does the tick clear at midnight, like the day counters? | Yes | |

---

## 49. Landscape focus screen  ⭐ HIGH

**What:** A landscape layout built for watching rather than managing:
- One giant timer across the left two-thirds — elapsed, remaining, or time to
  the next break, chosen per label with a default
- Tasks with tick boxes down the right third (idea 27)
- The other figures small along the bottom, around 52dp
- Per-label almost-done settings: sound, flashing, which figure is giant

**Why:** The portrait screen manages ten labels. This one watches a single
piece of work.

| # | Question | Leaning | Answer |
|---|---|---|---|
| 49.1 | Does rotating enter it automatically, or is it a mode? | Rotating — that's what rotating is for | |
| 49.2 | Which label does it show — the running one, or one you pick? | The running one; if none, the last | |
| 49.3 | Can timers be started and stopped from it, or is it read-only? | Start/stop only; managing stays in portrait | |
| 49.4 | Is this worth building before idea 27, given the task panel is half of it? | No — 27 first | |

---

## 52. Goal bar scaled across all labels  ⭐ HIGH

**What:** A second bar where 100% is the *largest goal of the day*, so every
label's bar is proportional to every other. Green fills the blue, red goes
past it.

**Why:** The current bar shows each label against its own goal, so a 15-minute
label and a 4-hour one look identical at 50%. This shows the shape of the day.

**Notes:**
- **The scaling problem you identified is real.** Once the largest-goal label
  goes past its goal, the 100% mark isn't the right edge any more, and every
  other bar shifts as it keeps running. Two ways out: scale to the largest
  *goal* and let overage overflow the row, or scale to the largest *goal or
  elapsed*, and accept that all bars move.

| # | Question | Leaning | Answer |
|---|---|---|---|
| 52.1 | Scale to the largest goal, or the largest of goal-or-elapsed? | Largest goal, fixed for the day — bars that move while you watch are hard to read | |
| 52.2 | Does this replace the current bar, or is it a toggle? | Toggle; they answer different questions | |

---

## 54. Split a label into numbered parts  — MEDIUM

**What:** Write `Work (1)`, `Work (2)` and so on, so a large area can be
placed at several points in the day, then recombined by stripping everything
from ` (` when reporting.

**Notes:** You leaned toward stripping *before* the log is written, which
keeps the CSV clean but loses which part it was. Stripping at report time
keeps both, at the cost of every consumer knowing the convention.

| # | Question | Leaning | Answer |
|---|---|---|---|
| 54.1 | Strip before writing, or keep the part number and strip when reporting? | Keep it in the log — you can always ignore a column, never recover one | |
| 54.2 | Do the parts share a goal, or have their own? | Their own, or the schedule can't be laid out | |

---

## 56. Line the pills up with the columns  — MEDIUM

**What:** Position the header pills over the columns they describe.

**Notes:** Only some pills map to a column — sort and Day/Ses don't. Partial
alignment may read as broken alignment.

---

## 57. After-hours prompt that must be answered  — MEDIUM

**What:** Late in the day, a prompt that stops the timer unless a person
answers it. Plus making the day's figures adjustable afterwards, for when a
wind-down timer was left running.

**Notes:** The gentler half of idea 47. Both want the same "is anyone there?"
check; 47 is what happens when the answer is no.

---

## 58. Time logged against a label with no goal  — MEDIUM

**What:** Two parts:
- A red bar across the whole row when a label has time but no goal — time
  recorded against nothing planned.
- A way to clear a few stray seconds from an accidental start.

**Notes:** The second half largely exists: the Notes screen deletes a run, and
long-press on the timer column adjusts. Neither is good for wiping 8 seconds,
though — the slider steps 5 minutes. A "clear today" action in the popup may
be what's missing.

| # | Question | Leaning | Answer |
|---|---|---|---|
| 58.1 | Is a full red bar too alarming for something as ordinary as an unplanned label? | Possibly — a thin edge stripe may say it without shouting | |
| 58.2 | Add "Clear today" to the label popup? | Yes, behind a confirmation | |

---

## 60. Offer the last-used layout name when saving  — LOW

**What:** Prefill the save dialog with the layout last opened, so re-saving it
is one tap. A search box beside it for the rest.

**Notes:** Overwrite already prompts; this removes the retyping.

---

## 61. Consistent button colours  — LOW

**What:** Save, Cancel and Delete differ between screens — amber in one place,
white or blue in another.

**Notes:** Worth settling one rule: amber for the affirmative action, muted for
cancel, red for destructive, and applying it everywhere.

---

## 62. Two more sort orders  — LOW

**What:** Most time recorded first, and least-percentage-of-goal first.

**Notes:** The second is the interesting one — it surfaces what's furthest
behind rather than what's largest. Labels with no goal have no percentage and
would need a defined place, probably the bottom.

---

## 63. One pill for Day / Session / Since last start  — NEEDS CHECKING

**What:** Collapse Day and Ses into a single pill cycling three options, the
third being time since the timer was last started.

**Note:** Your list has this under Done, but it isn't built — Day and Ses are
still two separate pills and there's no "since last start" option. Worth
confirming whether you meant something else, or whether it belongs in the open
list.

---

## 73. When a task was completed  ✅ built in v70

**What:** Two records of a completion, not one.

- **`completed_at` on the task**, shown in the editor: `✓ 9 Sep 3:15p` beside
  how long it took.
- **A row in `timer_log.csv`**, typed `task_done`, carrying the task's text,
  the time accrued against it and its estimate.

**Why both:** the field answers "when did I finish this" while the task still
exists. The log row survives the task being edited, archived or deleted, and
is what a date range can count — which a task's own lifetime figure never
could. It's also what makes idea 72.4 possible, the task totals I had to leave
out of the range screen.

**Why not written into notes**, which was the first thought: notes are free
text tied to a run, so completions would be mixed in with what you actually
wrote, unqueryable and awkward to separate later. It would also only catch
completions that happened to coincide with a timer switch.

**Notes:**
- A `type` column on the log. Rows without it read as `run`, which is what they
  all were. Existing files have their header upgraded in place; both earlier
  headers are recognised.
- **The time on a completion row is already inside its label's runs**, so
  every reader excludes these rows or it would count twice. That meant touching
  all five: `readRunsForLabel`, `sumByLabel`, `labelsWithNotes`,
  `exportForLabel`, and `attachNoteToLastRow` — which had to start finding the
  last *run* rather than the last row, since a completion can be written after
  one.
- Only completion logs a row. The other statuses are intermediate and get
  cycled through by accident as often as not.
- `completed_at` is stamped the first time and kept, so cycling past DONE and
  round again doesn't rewrite the date it was actually finished.

---

## 74. Drag tasks instead of arrow buttons  ✅ built in v72

**What:** Long-press a task in the editor and drag it. The up/down arrows are
gone.

**Why:** Two taps per position is tedious once a list is more than a few long,
and the arrows took width from the text they were beside.

**Notes:**
- The editor's list was a plain `LinearLayout` inside a `ScrollView`, which
  can't drag. It's a `RecyclerView` with `ItemTouchHelper` now — the same
  mechanism the main list uses, so the gesture matches.
- **Fixed 340dp height rather than wrap_content.** A RecyclerView scrolls
  itself, and inside a dialog there's nothing to bound a wrapping one.
- The new order is written **when the finger lifts**, not on every swap — one
  file write per drag instead of a dozen.
- Gestures in the editor: tap the marker to cycle status, tap the text to edit,
  long-press the row to drag.

---

## Template for new entries

New ideas go in **Open ideas** with the next unused number. When one ships,
move its whole section to **History**, append `✅ built in vNN` to its heading,
and keep History ordered by version.

**What:** one or two sentences describing the change.

**Why:** the problem it solves.

**Notes:** technical detail, constraints, things already known.

**Open questions — to answer before building:**

| # | Question | Leaning | Answer |
|---|---|---|---|
| n.1 | | | |

---

# History

Built and shipped, oldest release first.

---

## 1. Manual sort should restore the last manual order  ✅ built in v17

**What:** Switching the sort mode back to Manual should return the rows to the
last hand-built arrangement. Any labels that weren't part of that arrangement
(newly added, or newly made visible) go to the **bottom**, below the arranged
ones, where they can then be placed deliberately.

**Why:** Right now a new label lands wherever its default order number happens
to put it, which is accidental rather than intentional. Appending at the bottom
also leaves the established arrangement undisturbed — the rows you use daily
stay exactly where your thumb expects them.

**Several new labels at once:** oldest first, newest at the very bottom. Add
three labels in a row and they appear beneath the arranged list in the order
they were created, with the most recent last.

**Notes:**
- The last manual order already persists (`manualOrder` per label in
  `labels.txt`), so switching modes doesn't currently destroy it. The new part
  is the "unplaced labels go to the bottom" rule.
- Needs a sentinel to distinguish "never manually placed" from "manually
  placed at position 1" — probably `manualOrder = 0` meaning unplaced, with
  real positions starting at 1. Internal detail, no decision needed.

**Open questions — to answer before building:**

| # | Question | Leaning | Answer |
|---|---|---|---|
| 1.1 | Does a label that was hidden and is later made visible count as unplaced (goes to bottom), or keep its old manual position? | Keep its old position | **Keeps its old manual position.** Only genuinely new labels append to the bottom. |

---

## 2. Fit 10 rows on the main screen  ✅ built in v17

**What:** The main screen currently shows about 8 rows before scrolling. Make
it fit 10 without shrinking any text — same label, timer and goal font sizes.
The space has to come from padding and margins around the content, not from
the numbers themselves.

**Why:** 10 visible labels covers a realistic full set without scrolling, and
the current rows have more empty space than the content needs.

**Notes:**
- Row height is currently a fixed 64dp (`MainActivity.buildRows`). Ten rows
  plus header and the two button rows won't fit at 64dp; likely needs ~52–56dp.
- Space to reclaim: row bottom margin (6dp), the row's internal
  `paddingStart`/`paddingEnd` (14dp), and the container padding (16dp).
- The timer is 32sp, which needs roughly 44dp of line height on its own — so
  ~52dp is close to the floor before the text starts to feel cramped.
- Exact available height depends on the status bar and gesture area, so this
  needs checking on the device rather than by arithmetic.

**Open questions — to answer before building:**

| # | Question | Leaning | Answer |
|---|---|---|---|
| 2.1 | If 10 rows won't fit at full font size, which gives — the row count, or the 32sp timer? | — | **Try 10 rows at full size.** Overflow scrolls; no need to shrink the timer. |
| 2.2 | Should the progress bar stay full row height at ~52dp, or would a thinner bar read better once rows are shorter? | Keep full height | **Keep full row height.** |

---

## 3. Compact +5/−5 buttons, with a goal total beside them  ✅ built in v17

**What:** Shrink the `− 5m` and `+ 5m` buttons on the main screen to half
their current width or less, and sit them together as a pair on the left. Use
the freed space on the right to show a running total.

Sort mode drives both the per-row number and the total:

| Sort mode | Number at the right of each row | Total beside the buttons |
|---|---|---|
| Manual, A–Z, Goals | that label's **goal** (h:mm) | **sum of all goals** |
| Remain | that label's **time remaining** (h:mm) | **sum of all remaining** |

So Remain mode switches the whole right-hand column from goal to remaining,
not just the total. Each label floors at zero, so one past its goal
contributes nothing rather than offsetting a label still short.

**Why:** The two adjust buttons take a full third of the row each for what is a
minor, occasional action. A day-level total is more valuable in that space —
it answers "how much have I actually committed to today" at a glance.

**Notes:**
- Stop stays where it is; only the two adjust buttons shrink.
- Remaining total floors each label at zero (answered, 3.2), so a label past
  its goal contributes nothing rather than offsetting labels still short.

**Open questions — to answer before building:**

| # | Question | Leaning | Answer |
|---|---|---|---|
| 3.1 | Should the total cover only *visible* labels, or every label in the library? | Visible only | **Every label in the library**, including any set to hidden. See 3.5 — this needs a second look now that hiding is being kept. |
| 3.2 | In Remaining mode, do labels already past goal count as 0, or as a negative that offsets others? | Count as 0 | **Count as 0** — each label floors at zero, so the total reads "time still to do" and an over-run label never masks a shortfall elsewhere |
| 3.3 | Should the total be tappable to switch between goal-sum and remaining-sum, independently of the sort mode? | No | **Not tappable — driven entirely by sort mode.** See the expanded behaviour above. |
| 3.4 | Label the total, or leave it as a bare number? | Small caption | **Bare number, no caption.** |
| 3.5 | Since hiding is being kept, should the total still include hidden labels — meaning it won't match the rows on screen? | Include them | **Include hidden labels** — the total is the full daily commitment. Revisit if idea 7 lands, since scheduled labels will be hidden far more often than manual ones. |

---

## 4. Prompt for unsaved text in the new-label box  ✅ built in v17

**What:** On the Settings page, if there's text typed into the "New label"
field and you press Save without having pressed Add, prompt rather than
silently discarding it.

Suggested wording: title `Add "<text>" as a label?` with buttons
**Add and save** / **Discard it** / **Cancel**.

**Why:** Typing a label and then hitting Save is a natural mistake — the text
just vanishes with no indication anything was lost.

**Notes:**
- Sits alongside the existing unsaved-changes prompt. If both apply (typed
  text *and* other edits), they shouldn't stack into two dialogs — see 4.1.
- Same check should probably apply to Cancel and the back gesture, not just
  Save.

**Open questions — to answer before building:**

| # | Question | Leaning | Answer |
|---|---|---|---|
| 4.1 | If there's typed text *and* other unsaved changes, one combined prompt or two in sequence? | One combined | **One combined prompt.** |
| 4.2 | Does the same prompt appear on Cancel and back, or only on Save? | All three | **All three — Save, Cancel and the back gesture.** |
| 4.3 | Button wording — "Add and save" / "Discard it" / "Cancel"? | As written | **"Add and save" / "Discard it" / "Cancel".** |

---

## 5. Wider sort button with fuller wording  ✅ built in v17

**What:** Widen the sort button in the main screen header and replace the
abbreviations with clearer words: **Manual**, **Remain**, **Goals**, **A–Z**.

**Why:** "Man" and "Rem" are terse enough to need decoding. The header has room
for a few more characters.

**Notes:**
- Changes `SettingsStore.SORT_SHORT`; the long names used in the picker dialog
  and on the Settings button stay as they are.
- The header also holds the date, session time, and the Day/Session pills, so
  the extra width has to come from somewhere — see 5.1.

**Open questions — to answer before building:**

| # | Question | Leaning | Answer |
|---|---|---|---|
| 5.1 | Where does the extra width come from? | Drop "session" | **Drop the word "session"**, keeping just the time. |
| 5.2 | Should the button width be fixed, or size itself to the current word? | Fixed | **Fixed width**, sized to the longest word ("Manual"). |

---

## 6. Make the show/hide toggle understandable  — CLOSED, hiding removed in idea 24

**What:** Settings has a ◉ / ○ toggle at the right of each label row. ○ means
the label does **not** appear on the main screen at all — not further down, not
by scrolling; its row simply isn't drawn. It stays in the library, keeps its
goal, and keeps every minute already logged against it.

That wasn't clear from the UI, so make it obvious. Options: a one-line caption
above the list, a column header, or replacing the circles with something more
literal (an eye icon, or the words Show/Hide).

**Why:** The control changes what the main screen contains, which is a
significant effect for an unlabelled circle. It was mistaken for decoration.

**Notes:**
- Keeping the feature — useful for labels that only matter some of the time
  (seasonal projects, a course that finishes, holiday-only categories).
- Nothing is lost by hiding: the log is untouched and unhiding restores the
  row with its history and manual position intact (see 1.1).

**Open questions — to answer before building:**

| # | Question | Leaning | Answer |
|---|---|---|---|
| 6.1 | Eye icon, Show/Hide words, or keep circles with a caption explaining them? | Words | **Open** — likely superseded by idea 7, where the control becomes a schedule rather than a toggle. |
| 6.2 | Should hidden labels sort to the bottom of the Settings list so the active ones group together? | Yes | **Yes — group not-showing-today labels at the bottom.** |

---

## 8. Notes while a timer runs, with history  ✅ built in v20

**What:** A note sheet that slides up from the bottom of the main screen,
opened by a small notebook icon on a timer row.

**Layout:**
- Sheet covers the lower part of the screen — precisely the region the keyboard
  occupies anyway, so nothing fights for space.
- Its header shows the label and the **live running timer**, still ticking, so
  the timer stays visible even though the row behind it may be scrolled away.
- Below the header, a scrollable **history** for that label: every past run,
  newest first, each showing date, start time, how long it ran, and its note.
  Scrolls back through previous days indefinitely.
- At the bottom, the input for the current run's note.
- Closes on tapping outside or a Done button. Autosaves as you type, so an
  interruption never loses anything.
- Always closed when a timer starts or restarts — it only opens on the icon.

**Why:** A long block of work — a call, a debugging session — accumulates
things worth recording as they happen, not reconstructed afterwards. And being
able to scroll back to "what was that conversation last Tuesday" turns the log
from a time record into a work journal.

**Notes:**
- The CSV already holds everything the history needs: `run_start`, `label`,
  `duration_minutes`, `note`. This is a read-and-filter of the existing file,
  no new storage.
- Per-run notes keep the current one-row-per-run schema unchanged. History is
  a view over past rows, not a second place data lives.
- The in-progress run has no row yet, so its note has to be held in
  preferences until the run stops and the row is written — same mechanism as
  the pending-adjustment buffer already used for +5/−5.
- File growth is not a concern for a long time: a year of heavy use is a few
  thousand lines, trivial to read and filter.

**Open questions — to answer before building:**

| # | Question | Leaning | Answer |
|---|---|---|---|
| 8.1 | Does the history show every past run, or only runs that have a note? | Every run | **Notes-only by default**, with a "Show runs with no note" toggle. Also: notes live on their own **screen**, not a bottom sheet. |
| 8.2 | Does the notebook icon appear on every row, or only the running one? | Every row | **Every row.** |
| 8.3 | Should a row whose label has a note today look different? | Yes | **Yes — the icon is amber when the label has any note logged, dim grey otherwise.** |
| 8.4 | Can you edit the note on a *past* run? | Editable | **Editable** — tap any entry to open its note in a dialog. |
| 8.5 | How far back does the history load? | Everything | **Everything**, oldest first so the newest sits beside the input. Revisit if it ever gets slow. |
| 8.6 | If a run is split at midnight, does its note attach to both halves or just the second? | Final segment | **Final segment only**, same as adjustments. |

---

## 10. Single bottom control row  — partly superseded by idea 11

**What:** Collapse the two bottom rows into one. Currently there's
`[−5m] [+5m] [total] [Stop]` on one row and `[Settings] [Done for now]` on
another.

Proposed single row:

`[⚙] [−5] [+5]` `[total]` `[Stop] [Exit]`

- `−5` / `+5` as narrow as they can be and still be tappable — around 44dp,
  dropping the "m".
- "Done for now" becomes **Exit** — shorter and no less clear.
- "Settings" becomes a **⚙ gear icon**, roughly 44dp square.
- Total keeps the flexible middle space.

**Why:** Two rows of buttons cost about 55dp of vertical space for six
controls that fit on one. That space goes back to the timer rows, which is
where it matters — and it should be reclaimed before idea 7 adds a
`+ Other labels` row at the bottom of the list.

**Notes:**
- Should precede idea 7 for exactly that reason: 7 adds a row to the main
  screen, so the space wants finding first.
- Six controls on one row on an S24 Ultra: gear 44dp, −5/+5 44dp each, Stop
  and Exit around 60dp each, leaving roughly 130dp for the total. Comfortable.
- `Exit` still gets its confirmation dialog — the wording change doesn't make
  it a one-tap action.

**Open questions — to answer before building:**

| # | Question | Leaning | Answer |
|---|---|---|---|
| 10.1 | Left-to-right order? | Separate gear from Exit | **Gear at the far left**, then −5/+5, the total in the flexible middle, then Stop and Exit at the right. Keeps the widest possible gap between the gear and Exit so a mis-tap can't end the session. |
| 10.2 | Should Stop stay a word, or also become an icon (■) to save more width? | Keep the word — it's the most-used control | |
| 10.3 | Does Exit need its own icon instead of a word? | Word — an exit icon is easily confused with Stop | |

---

## 11. Column totals, and Stop moved to the bottom row  ✅ built in v19

**Revises idea 3 (built in v17) and conflicts with idea 10 — see below.**

**What:** Two totals, each sitting directly beneath the column it sums, so
their meaning is positional rather than remembered.

```
[ label      ] [ elapsed ] [ goal/remaining ]   <- timer rows
[-5m] [+5m]    [  total  ] [     total      ]   <- row A
[ Settings ]   [    Stop (2x wide)    ] [ Done ]  <- row B
```

- **Middle total** — sum of elapsed time across all labels. Follows the
  Day/Session toggle: day totals when Day is selected, session totals when
  Session is.
- **Right total** — sum of goals normally, sum of remaining when sorted by
  Remain. Matches whatever the column above it is showing.
- Both in **h:mm only** — no seconds. They're summaries, not stopwatches.
- `−5m` / `+5m` stay at the left of row A, under the label column.
- **Stop moves down** to row B, twice the width of Settings and Done for now
  either side of it.

**Why:** A total under its column reads instantly. The single total from idea 3
sits between the buttons and doesn't line up with anything, so you have to
recall whether it means goals or remaining. Stop is also better placed in the
button row than mixed in with numbers.

**Notes:**
- Supersedes idea 3's single `tvTotal`: that view moves right and becomes the
  goal/remaining total, and a second total is added under the elapsed column.
- **Conflicts with idea 10**, which proposed collapsing everything to one row
  with a gear icon and "Exit". Those two can't both happen as written — see
  11.1. The gear icon and the "Exit" wording could still apply to row B.
- Column alignment has to match `row_timer.xml` exactly, which uses a
  FrameLayout with the timer centred and the goal at the end. The totals row
  needs the same three-part structure or they won't line up.
- Seconds dropped means the totals only change once a minute, which also makes
  them calmer to look at while a timer runs.

**Open questions — to answer before building:**

| # | Question | Leaning | Answer |
|---|---|---|---|
| 11.1 | Does idea 10 still apply — gear icon instead of "Settings", and "Exit" instead of "Done for now" — on row B? | Yes to both | **"Done for now" → "Exit".** "Settings" stays a word: the 1:2:1 weighting leaves it about 90dp, plenty of room, so the gear isn't needed for space. |
| 11.2 | Should the elapsed total also floor at zero, given adjustments can make a label negative? | No | **No flooring** — the elapsed total shows the true sum, negatives included. |
| 11.3 | Do the totals cover every label in the library, or only those shown? | Every label | **Every label in the library**, consistent with 3.1. |
| 11.4 | Should the totals be visually distinct from the timer rows — a divider line, or dimmer text? | Divider | **Thin divider above row A**, plus slightly smaller type than the timer rows. |

---

## 12. Seed the manual order from the current sort  ✅ built in v20

**What:** A fifth entry in the sort picker — something like
**"Manual (start from current order)"** — that takes whatever order is on
screen right now, writes it into the manual positions, and switches to Manual
mode.

So: sort by Goals, see that it's roughly what you want, pick this, and you're
in Manual mode with that arrangement as the starting point, ready to nudge
individual rows.

**Why:** Building a manual order from scratch means moving every row one at a
time. Most of the time an automatic sort is already close, and the manual pass
is just a few adjustments.

**Notes:**
- It's an **action, not a mode** — it does its work and leaves you in Manual.
  It shouldn't stay selected or appear as the current mode afterwards.
- Implementation is small: it's `LabelStore.applyManualOrder(library,
  displayedNames)` followed by `setSortMode(SORT_MANUAL)`. Both already exist.
- **Destructive**: it overwrites whatever manual arrangement was there before,
  with no undo. That's the main reason it needs a confirmation — see 12.1.
- Sits oddly in a list of four orderings, since picking it doesn't leave the
  list in that state. Possibly better as a separate button in the picker
  dialog, below the four radio options.

**Open questions — to answer before building:**

| # | Question | Leaning | Answer |
|---|---|---|---|
| 12.1 | Confirm before overwriting an existing manual order? | Conditional | **Confirms only when a manual order already exists**; silent when everything is still unplaced. |
| 12.2 | A fifth item in the radio list, or a separate button? | Separate button | **Separate button** in the sort dialog: "Use current order as manual". |
| 12.3 | Does it order every label, or only those on screen? | Every label | **On-screen labels in displayed order, everything else appended after** in its existing order. |
| 12.4 | Should the same action exist in Settings? | Yes | **Not yet** — main screen only for now; add to Settings if it proves useful. |

---

## 13. Carry the selection from the main screen into Settings  ✅ built in v20

**What:** When a timer is running (or a row is otherwise the current one) and
you tap Settings, that same label starts out selected in the Settings list —
rather than defaulting to whichever label happens to be first.

**Why:** Tapping Settings while working on something is almost always *about*
that thing: adjusting its goal, renaming it, checking its schedule. Landing on
the right label saves a scroll and a tap, and removes the risk of editing the
goal of whatever was at the top by mistake.

**Notes:**
- Small change. `SettingsActivity.onCreate` currently does
  `selected = library.firstOrNull()?.name`; it would instead prefer
  `TimerStore.getActiveLabel()` when one is running and still in the library.
- Should also scroll the label list so the selected row is in view — with the
  list windowed to a few rows, selecting something off-screen is invisible.
- The pinned readout above the goal buttons already shows which label is
  selected, so the effect is immediately obvious.

**Open questions — to answer before building:**

| # | Question | Leaning | Answer |
|---|---|---|---|
| 13.1 | If no timer is running, what gets selected? | Last one that ran | **The last label started**, falling back to the first in the list. |
| 13.2 | Should it work in reverse? | No | **No** — the main screen highlight means "running", and overloading it would mislead. |

---

## 14. Swap the progress bar colours  ✅ built in v21

**What:** Progress up to the goal fills **green**; time beyond the goal fills
**burnt red** rather than amber.

| Portion | Was | Now |
|---|---|---|
| Up to goal | amber `#4A3A1C` | green `#2A4A2E` |
| Beyond goal | green `#2A4A2E` | burnt red `#4A2A18` |

**Why:** Green reads as "on track" and warm red as "past the mark", which
matches how the two portions actually feel. The previous pairing had it
backwards — green for overage suggested overshooting was the good outcome.

**Notes:**
- The view ids were renamed from `barAmber`/`barGreen` to
  `barProgress`/`barOver`, so the names describe the role rather than the
  colour and a future palette change won't leave them lying.
- The active-row border stays amber; it signals "running", which is unrelated
  to goal progress.
- Both fills stay dark and desaturated, since text sits on top of them.

---

## 15. Preserve line breaks in notes  ✅ built in v22

**What:** Notes keep their paragraph breaks. Typing a note with blank lines
between paragraphs now reads back the same way instead of collapsing into one
run-on block.

**Why:** The formatting is what makes a long note readable later, which is the
whole point of keeping them.

**Notes:**
- The loss happened at **write** time, not display. `csvEscape` was replacing
  newlines with spaces to keep each row on one physical line, so the breaks
  were gone before reaching the file.
- Fixed by **encoding** line breaks as a literal backslash-n and decoding on
  read. A real newline inside a quoted field is valid CSV, but it would split
  a row across two physical lines and break the line-indexed reads and atomic
  rewrites the log depends on.
- Backslashes are escaped first, so a note containing a literal backslash
  still round-trips. Verified against quotes, commas, blank lines, trailing
  newlines and Windows line endings.
- **Trade-off:** an exported CSV shows `\n` where a line break was, rather
  than a genuine multi-line cell. Proper multi-line CSV would need a streaming
  parser and a different way to address rows for editing — worth doing if the
  export readability ever matters more than the simplicity.
- Existing notes written before v22 already lost their breaks; that can't be
  recovered.

---

## 16. Stop the timer overlapping long labels  ✅ built in v23

**What:** Move the timer column right, roughly 15–20% of the screen, so long
labels no longer collide with it — especially once a run passes an hour and the
duration gains digits (`00:16` becomes `1:13:32`).

**Why:** "Sterwardship" currently runs straight into `1:13:32`. Both are
unreadable at that point.

**Notes:**
- The real cause isn't position, it's that `row_timer.xml` uses a FrameLayout
  with the timer **absolutely centred** and the label placed independently.
  Nothing stops them occupying the same space, so shifting the timer right
  only postpones the collision — a longer label would still reach it.
- Proper fix: give each of the three parts a share of the row width, so they
  can't overlap by construction. Roughly **42% label / 36% timer / 22% goal**,
  which puts the timer about where the 15–20% shift would.
- The label then ellipsises when too long rather than running underneath.
- The totals row (row A) must use the identical split, or the totals stop
  lining up with the columns they sum — the whole point of idea 11.

**Open questions — to answer before building:**

| # | Question | Leaning | Answer |
|---|---|---|---|
| 16.1 | Right-align the timer in its column, or keep it centred? | Right-align | **Right-aligned**, so the colons hold position as the hours digit appears. |
| 16.2 | Are the 42/36/22 proportions right? | Start there | **42/36/22 as a starting point** — easy to nudge once seen on the device. |
| 16.3 | Long labels: ellipsise or shrink to fit? | Ellipsise | **Ellipsise**, single line. Shrinking would make rows visually inconsistent. |
| 16.4 | Note icon fixed at 34dp? | Yes | **Yes** — fixed 34dp outside the weighted split, mirrored by a 34dp spacer on the totals row. |

---

## 17. Drag to reorder in Manual mode  ✅ built in v26

**What:** In Manual mode, long-pressing the **note icon** at the left of a row
picks that row up so it can be dragged to a new position. Sits alongside the
existing gesture: tapping a label opens the picker to insert another label
above it.

Two ways to reorder, each better at something — insert-before is precise when
you know exactly where a label should go; dragging is faster for nudging a row
a couple of places.

**Why:** Moving a row several positions currently takes a tap, a scroll through
the picker, and a selection. Dragging is one gesture.

**Notes:**
- **Long-press is getting crowded.** Long-pressing the row already opens the
  ±15/±5 adjuster; long-pressing the icon would start a drag. The targets are
  34dp apart, so an imprecise press does the wrong thing — see 17.1.
- **Needs different plumbing.** Rows are inflated into a plain LinearLayout
  inside a ScrollView. Drag-and-drop with auto-scroll at the edges really
  wants a **RecyclerView with ItemTouchHelper**, which means rewriting how
  rows are built and cached in `MainActivity.buildRows`.
- Built ahead of idea 7 rather than alongside it, since it's useful now and
  idea 7 may not be needed at all. Idea 7 will have to work with the adapter
  when it comes.
- Only active in Manual mode. In the sorted modes the position is computed, so
  a drag would have nowhere to persist to — same rule the tap gesture uses.
- Dropping a row writes new `manualOrder` values for everything, exactly as
  `applyManualOrder` already does.

**Open questions — to answer before building:**

| # | Question | Leaning | Answer |
|---|---|---|---|
| 17.1 | Long-press on the icon starts a drag, long-press on the row opens the adjuster — too easy to confuse? | Resolved | **No longer an issue** — idea 18 removed the long-press adjuster, so the gesture is free. The whole row can be the drag handle. |
| 17.2 | Should dragging work in the sorted modes by switching to Manual first? | No | **No** — dragging is disabled outside Manual mode. |
| 17.3 | Visual feedback while dragging? | Lift and shadow | **The dragged row lifts** (elevation) and goes slightly translucent; the rest is left alone. |
| 17.4 | Should the same drag exist in the Settings label list? | Eventually | **Not yet** — main screen only. Settings still uses long-press insert-above. |

**How it turned out:**
- `MainActivity` now drives a `RecyclerView` with a `TimerAdapter`, replacing
  the hand-inflated LinearLayout and its eight parallel view caches.
- Per-second updates go through `bindValues`, shared with `onBindViewHolder`,
  so a freshly scrolled row and a ticking one can't drift apart.
- `itemAnimator` is off: with a refresh every second, animations would make
  the list twitch constantly.
- Drops persist via `applyManualOrder`, and now report failure rather than
  silently reverting on next launch.

---

## 18. Move time adjustment from long-press to Settings  ✅ built in v24

**What:** The long-press adjuster on the main screen is gone. Adjusting the
time recorded against a label now happens in Settings, on the selected label,
with **−15 / −5 / +5 / +15** buttons.

**Why:** The long-press dialog went unused, and removing it frees the gesture
for drag-to-reorder (idea 17). But it was also the only way to adjust a timer
that *isn't* running — the −5m/+5m buttons on the main screen act on the
active timer only — so that capability moved rather than disappearing.

**Notes:**
- Settings now has two clearly captioned rows: **Goal** (+1 … +60, Clear) and
  **Time** (−15 −5 +5 +15). Without captions the two rows of plus-buttons
  would be easy to confuse.
- The pinned readout now shows `label    goal 5:00    today 1:20`, so both
  rows have visible feedback while tapping.
- **Time adjustments apply immediately and are written to the log**, unlike
  goals which are saved with the rest of the page. A correction to recorded
  time isn't a setting, and deferring it until Save would misrepresent it.
- `dialog_adjust.xml` deleted — remove it from the repo.
- Long-press on a row is now free for idea 17's drag, which removes the
  gesture collision flagged in 17.1.

---

## 19. Wider duration column, and colour-coded goal column  ✅ built in v25

**What:** Two adjustments to the main screen.

1. **Duration column widened** from 36% to 40% of the row, with the label
   giving up 4% (42% → 38%). `1:13:32` was being clipped to `1:13:3`.
2. **Third column colour now says what it's showing:**

| State | Colour |
|---|---|
| Showing a goal (Manual, A–Z, Goals) | green `#7DBE7A` |
| Showing time remaining (Remain) | blue `#6FAFC4` |
| Remain, goal already passed | red `#C97064` |
| No goal set | grey, blank |

**Why:** The column shows two different things depending on sort mode, and
nothing distinguished them. Colour makes it readable without checking which
mode is active.

**Notes:**
- The totals row uses the same scheme, so the column reads consistently top to
  bottom.
- Both layouts had to change together — row A mirrors row_timer.xml, so the
  totals would have drifted out of alignment otherwise.
- A passed goal shows `0:00` in red rather than a negative. Showing how far
  over would be more informative; worth revisiting if the red alone proves too
  blunt.
- The active row no longer brightens its goal text, since the state colour now
  carries that information.

---

## 20. Named layouts (saved orders you can recall)  ✅ built in v27

**What:** Save the current arrangement under a name — "First Saturday of the
month", "Weekday morning", "Travel" — and recall it later from a searchable
list. With thirty or forty saved, typing "saturday" narrows to the few that
match.

Applying a layout reorders the labels to match it. Anything that has changed
since it was saved is reconciled automatically:

- **Labels added since** — appended at the bottom, consistent with how newly
  created labels behave in Manual mode (idea 1).
- **Labels deleted since** — dropped from the layout.
- A short summary says what changed — "Added: Reading, Errands · Removed:
  Taxes" — with a dismiss.

**Why:** A recurring day has a recurring shape. Rebuilding it by hand each time
is the chore; naming it once and recalling it is not.

**Notes — how this relates to idea 7:**
- **Substantially overlaps idea 7 (scheduled visibility).** A layout called
  "First Saturday" is the manual version of the same need: you pick it rather
  than it appearing on its own.
- Presets are **simpler and more predictable** — no carry-over rules, no
  override row, no red styling for missed days, no anchor dates. You stay in
  control of when a layout applies.
- If layouts work well in practice, **idea 7 may never be needed**, which
  would remove the largest item in the queue. Worth living with layouts for a
  while before deciding.
- The two could also combine later: a layout could optionally be *scheduled*,
  which is idea 7 rebuilt on a simpler foundation.

**Notes — implementation:**
- Needs a new file, e.g. `layouts.json` beside `labels.txt`, holding a list of
  `{ name, labels[] }`. Not in `labels.txt`, which is a flat one-per-line
  format that wouldn't carry a nested list cleanly.
- Applying is `LabelStore.applyManualOrder` with the layout's order plus the
  reconciliation, then switching the sort mode to Manual — both already exist.
- The picker is the same dialog pattern as the label picker, plus a filter
  field. Straightforward.
- Effort is comparable to idea 8: a store, a screen or dialog, and the
  reconcile logic. Not trivial, but far short of idea 7.

**Open questions — to answer before building:**

| # | Question | Leaning | Answer |
|---|---|---|---|
| 20.1 | Order only, or also shown/hidden? | Both | **Both**, plus goals — see 20.2. |
| 20.2 | Should a layout also carry **goals**? | Initially no | **Yes — goals are stored and restored.** A Saturday warrants different targets from a Tuesday, so a layout carries order, visibility and goals together. |
| 20.3 | Where does saving live? | Sort dialog | **Sort dialog.** It became a single list rather than radio buttons plus dialog buttons — AlertDialog allows only three buttons and there are now three actions beside the four orderings. |
| 20.4 | Rename and delete? | From the picker | **Long-press a layout in the picker** for Rename / Delete. |
| 20.5 | Undo after applying? | Re-pick | **No undo.** Re-picking the previous layout restores it, provided it was saved. Saving over an existing name asks first. |
| 20.6 | Dialog or inline banner for the change summary? | Banner | **Inline dismissible banner** above the timer rows. |

**How it turned out:**
- `layouts.json` sits beside `labels.txt`, holding each layout as a name plus
  a list of `{ name, visible, goal }`.
- Applying restores order, visibility and goals in one step, then switches to
  Manual mode.
- Labels created since a layout was saved go to the bottom and keep their
  current visibility and goal — the layout knows nothing about them, and
  silently hiding or re-targeting something just created would be surprising.
- Saving over an existing name asks to replace rather than doing it silently.
- The picker filters as you type and shows `4 shown of 9 · 6:30 of goals` under
  each name.

---

## 21. Cumulative column — running totals down the rows  ✅ built in v28

**What:** A `Cum` pill in the header that switches the rightmost column from
each label's own figure to a **running total down the list**. Row 1 shows its
own value, row 2 shows rows 1+2, row 3 shows 1+2+3, and the bottom row equals
the day's total.

Reading any row then answers: "by the time I've worked down to here, that's
what I'll have committed."

**Two independent toggles rather than one four-way control:**

| Sort mode decides | `Cum` pill decides | Column shows |
|---|---|---|
| Goal (Manual, A–Z, Goals) | off | that label's goal |
| Goal | on | **Acc Goal** — goals summed down to this row |
| Remain | off | that label's time remaining |
| Remain | on | **Acc Remain** — remaining summed down to this row |

Neither control needs to know about the other, and the existing sort behaviour
is unchanged.

**Why:** The order of the list is a plan for the day. A running total turns it
into a schedule — you can see where the commitment passes what's actually
available.

**Notes:**
- Current day only. No week or month accumulation.
- Follows whatever order the list is in, so re-sorting or dragging changes the
  running totals immediately. That's the point, not a side effect.
- Labels with no goal contribute nothing, so the running total simply repeats
  the previous row's value on those rows. Worth expecting rather than fixing.
- Cheap to compute: one pass over `displayed`, which is already in order.
  Recomputed on each tick along with everything else.
- **Header space is the real constraint.** It currently holds the date and
  time, the sort button, and the Day/Session pair. A fifth control is tight —
  shortening the date, or dropping the time, is likely needed. See 21.2.
- Cumulative remaining is arguably the more useful of the two — it says what's
  still ahead — but it moves as you work, so it's a less stable number to look
  at than cumulative goal.

**Open questions — to answer before building:**

| # | Question | Leaning | Answer |
|---|---|---|---|
| 21.1 | Include cumulative **remaining**? | Both | **Both.** The `Cum` pill accumulates whichever figure the sort mode is showing. |
| 21.2 | Where does the header width come from? | Drop the time | **Session time dropped from the header**, "Session" pill shortened to "Ses", sort button 66→58dp, pill padding tightened. |
| 21.3 | Totals row when Cum is on? | Leave it | **Unchanged.** The last row already equals it; blanking it would look like a fault. |
| 21.4 | Visually distinct? | Different shade | **Its own shade** (`#D8C89A`), separate from the green/blue/red set, since a running total means something different from a per-row figure. |

**Also fixed here — a bug introduced in v26:**

The RecyclerView rewrite in v26 replaced a block of `MainActivity` that
happened to contain `updateTotals`, `fmtHm` and `fmtGoalMs`. All three were
lost. `bindValues` still called `fmtGoalMs`, so **v26 and v27 would not have
compiled**, and the totals row was no longer being updated at all.

Restored here. The lesson is that replacing a range of a file by its start and
end markers is only safe if you know everything in between — a targeted
replacement of the specific function would not have had this failure mode.

---

## 22. Rename the `Cum` pill to `Sum`  ✅ superseded, built as part of v29

**What:** The cumulative pill currently reads `Cum`. Change it to `Sum`.

**Why:** Those three letters carry an unrelated and unwelcome meaning. `Sum`
is also more literally correct — each row *is* the sum of everything above it.

**Notes:**
- One string in `activity_main.xml`. Nothing else changes; the id stays
  `btnCum` so no code needs touching.
- Alternatives considered: `Tot` (reads as a grand total rather than a running
  one), `Run` (ambiguous on a timer app), `Roll`, and the summation symbol
  `Σ` (compact and precise, but cryptic beside word pills like Day and Ses).
- Deferred to the next build rather than shipping a version for a single word.

**How it turned out:** overtaken by a better change. The pill is no longer a
toggle at all — it's a four-way picker whose label shows the current choice
(`Goal`, `Rem`, `SGoal`, `SRem`), so there's no fixed word left to rename.

---

## 23. Goal popup on the main screen, and a clearer split with Settings  ✅ built in v29

**What:** Long-press the right-hand column of a row — whatever it's currently
showing — to open a popup for that label, styled like the Settings readout:

```
        Rest      goal 0:15      today 00:00

  Goal   +1   +5   +15   +30   +60
         -1   -5   -15   -30   -60
```

- A matching row of negatives, each subtracting from the goal, floored at zero.
- `+10` / `-10` dropped — `+5` twice covers it, and five buttons a row reads
  better than six.
- The same button set replaces the Settings goal row, so there's one design
  rather than two.

**The larger point — a cleaner split of responsibilities:**

| Screen | Owns |
|---|---|
| **Popup** | A label's *numbers*: goal, and time recorded today |
| **Settings** | The label *list* and app preferences: add, delete, rename, visibility, sort, reminders, notes, export |

That removes both the Goal row and the Time row from Settings — most of what
makes that page crowded — and means a normal working day rarely needs Settings
at all.

**Why:** Adjusting a goal mid-day currently means leaving the main screen,
finding the label, tapping it, adjusting, and coming back. The numbers belong
where the numbers are.

**Notes — the gesture problem:**
- Long-press is currently the **drag handle** for reordering (idea 17),
  applied to the whole row. A long-press on the goal column would collide with
  it — the same conflict that idea 18 removed, reintroduced in a new place.
- Fix: turn off `isLongPressDragEnabled`, and start dragging explicitly with
  `startDrag()` from a long-press on the **note icon**. Then every gesture has
  exactly one meaning: tap row = start timer, tap label = insert-above, tap
  icon = notes, long-press icon = drag, long-press goal column = this popup.
- The goal column is 22% of the row, which is a large enough target.

**Notes — what's left of Settings:**

With goal and time both in the popup, Settings would hold only: reminder
options, the note prompt, export, and whatever remains of label management.

**The unhide problem.** If the label list leaves Settings entirely and hidden
labels aren't on the main screen, there is nowhere to unhide one — they become
invisible everywhere and unrecoverable short of editing `labels.txt` by hand.
Three ways out, see 23.6:

1. **Drop hiding entirely.** Every label always shows; manage the list by
   deleting instead. Simplest, but loses the seasonal-label case that hiding
   was added for.
2. **A "show hidden" control on the main screen.** Hidden labels appear dimmed
   at the bottom and the popup can unhide them. One more control, but keeps
   everything on one screen.
3. **Keep a minimal list in Settings** — names with a visibility dot, nothing
   else. Far smaller than today's list, and the popup still owns the numbers.

- Adding a label still needs somewhere. A `+ New label` row at the bottom of
  the main list would close that, and sits naturally beside the
  `+ Other labels` row idea 7 proposed. See 23.4.

**Open questions — to answer before building:**

| # | Question | Leaning | Answer |
|---|---|---|---|
| 23.1 | Should the popup also carry the ±15/±5 **time** adjustment? | Yes | **Yes.** The popup owns both goal and today's time; Settings loses the Time row as well as the Goal row. |
| 23.2 | Does the popup replace the Settings goal row entirely? | Replace | **Replace.** Two places to do one thing is how a page gets crowded. |
| 23.3 | Long-press anywhere on the row, or only the goal column? | Anywhere | **Anywhere on the row** — a much larger target, and dragging moved to the note icon so nothing competes. |
| 23.4 | Add a `+ New label` row at the bottom? | Yes | **Yes**, and it opens the popup straight after so the goal can be set in the same motion. |
| 23.5 | Should the popup allow renaming? | No | **No.** Renaming would orphan logged history under the old name. |
| 23.6 | Does the label list leave Settings entirely? How would you unhide a label? | Minimal list | **Moot — hiding is being removed (idea 24).** With nothing to unhide, the label list can leave Settings completely. |
| 23.7 | Where does **delete** live once the list is gone? | Popup | **In the popup**, behind a confirmation. Visibility no longer exists. |

**How it turned out:**
- Gestures now have exactly one meaning each: tap row = start timer, tap label
  = insert-above, tap icon = notes, **long-press icon = drag**, **long-press
  row = label popup**. `isLongPressDragEnabled` is off and dragging starts
  explicitly via `startDrag`.
- The popup carries goal (±1/5/15/30/60, floored at zero), today's time
  (±5/±15), and Delete.
- Settings is now four settings, an export button and a note pointing back to
  the main screen.
- `row_label_edit.xml` deleted; idea 13's Settings preselect removed with it,
  since there's no list left to preselect in.

---

## 24. Remove per-label hiding  ✅ built in v29

**What:** Drop the show/hide capability. Every label in the library always
appears on the main screen.

**Why:** It was solving a problem that layouts (idea 20) solve better and
scheduled layouts (idea 7) will solve automatically. Keeping it forces a label
list to stay in Settings purely so hidden labels can be found again — which is
what blocks the cleaner split in idea 23.

**What goes:**
- The ◉/○ toggle and visibility column in the Settings label list.
- The `visible` field in `labels.txt` — still *parsed* so existing files load,
  but ignored. Old files keep working; the field simply stops mattering.
- Visibility from saved layouts. Layouts store order and goals only. Layouts
  already saved keep working; their visibility data is ignored.

**What it resolves:**
- **Idea 6** closed outright — there's no toggle left to explain.
- **Idea 7** rewritten from per-label schedules to scheduled *layouts*, which
  is a far smaller feature.
- **Idea 23** unblocked: with nothing to unhide, the label list can leave
  Settings entirely.
- Questions 3.1 and 3.5 dissolve — "all labels" and "visible labels" become
  the same set, so the totals can't disagree with the rows.

**Notes:**
- With every label always showing, a long list scrolls. Shortening the day's
  list stops being possible except by deleting a label — which is the trade,
  and layouts are the answer if a shorter list is ever wanted.
- Adding a label needs a home on the main screen once the Settings list goes;
  see 23.4.

**Open questions — to answer before building:**

| # | Question | Leaning | Answer |
|---|---|---|---|
| 24.1 | Standalone or folded into 23? | Fold in | **Folded in.** One rework of Settings rather than two. |
| 24.2 | Strip `visible` from `labels.txt`? | Leave it | **Left in place**, still written and parsed but never acted on. Keeps older builds readable if you ever roll back. |

---

## 25. Right-hand column picker  ✅ built in v29

**What:** The header pill is now a four-way picker rather than an on/off
toggle, and its label shows what's selected:

| Button | Column shows |
|---|---|
| `Goal` | each label's own goal |
| `Rem` | each label's time remaining |
| `SGoal` | goals summed down the rows |
| `SRem` | remaining summed down the rows |

**Why:** The column used to be driven partly by the sort mode — sorting by
Remain forced it to show remaining. That coupling was implicit and limiting:
there was no way to sort by remaining while still reading goals, or to
accumulate goals while sorted alphabetically. Sorting and what the column shows
are now independent.

**Notes:**
- Replaces idea 21's two-toggle design and idea 22's rename in one go.
- Colour still signals meaning: green for goals, blue for remaining, red once a
  goal is passed, and a separate shade for either running total.
- The totals row follows the column, so the pair stays consistent.

---

---

## 27. Task list under each label  ✅ built in v62

**What:** Each label carries an ordered list of tasks. While that label's timer
is running — and only then — a compact block of text appears beneath its row
listing the top few, as a reminder of what you're meant to be doing.

**On the main screen it is display only:**

```
  Work                    1:13:32        2:00
    - Draft the Q3 summary
    - Reply to Sam
    - Book the venue
```

- Plain hyphenated lines, tight line spacing. Not rows, not tappable, nothing
  interactive.
- Every other label stays a single short row, unchanged.
- The block disappears when the timer stops.
- **A label with no tasks still gets the space and the edit icon** — otherwise
  there'd be no way to add the first one. The running row is always at least
  tall enough to show it.

**The running row's height therefore varies**, growing with the number of lines
shown. Every other row keeps the fixed height it has now.

**All editing happens in a maintenance popup**, opened by an icon on the
expanded block:
- reorder by dragging
- add a task
- tick one off — it stays in the list, greyed, with the date and time it was
  completed
- a ± control for **how many lines to show** while running — minimum 1,
  maximum 5 — so eight prioritised tasks can display as three

No keyboard except when adding a task.

**Why:** The timer says what you're spending time on; the task list says what
you're trying to finish. Together they close the loop between intent and
record.

**Notes:**
- **Screen cost is small.** Display-only text at roughly 18dp a line means
  three tasks add ~55dp, on one row at a time, and only while a timer runs.
  Far cheaper than the expandable list this idea started as.
- **Simple to render.** A single `TextView` with newlines, shown or hidden on
  the running row. No nested list, no view recycling, no scroll conflicts —
  which removes most of what would have made this expensive.
- **Variable row height needs a small change.** `rowHeightPx` is currently
  applied to every row in `onCreateViewHolder`. The running row will need
  `wrap_content` instead, with the fixed height kept for the rest. Straight-
  forward, but it's the one place the current layout assumes uniformity.
- **Overlap with notes.** Notes record what happened; tasks record what's
  planned. A completed task with a timestamp sits between the two, which is
  why writing completions to the log (27.4) is worth doing.

**Notes — implementation:**
- New file, `tasks.json`, keyed by label: `{ text, order, completedAt }`.
- The maintenance popup is the same shape as the layout picker plus the drag
  mechanism already built for the main list, so most of the pieces exist.
- **This is the largest feature proposed so far** — larger than notes. Not an
  argument against it, but worth sequencing on its own rather than alongside
  anything else.

**Open questions — to answer before building:**

| # | Question | Leaning | Answer |
|---|---|---|---|
| 27.1 | Default number of tasks shown while running? | 3 | **3, adjustable per label** with the ± control, **range 1–5**. |
| 27.2 | Do completed tasks appear on the main screen? | Popup only | **Popup only.** The running block shows what's still to do. |
| 27.3 | Can a task be ticked from the main screen? | Popup only | **Popup only.** The main-screen block is display-only text — no checkboxes, nothing tappable. |
| 27.4 | Should completing a task write a row to the CSV log? | Yes | **Yes.** Turns the log from a time record into a record of work done. Needs a row type or a marker so completions can be told apart from runs — see 27.8. |
| 27.5 | Do tasks belong to a label permanently, or reset daily like the counters? | Permanently — a task list isn't a daily total | |
| 27.6 | Should layouts (idea 20) capture task lists too? | No — layouts are about arrangement, and this would make them much heavier | |
| 27.7 | Where does the block appear if the running label is scrolled off screen? | Nowhere | **Nowhere** — it's attached to the row. A pinned variant could come later if it proves a nuisance. |
| 27.8 | How does a completion row differ from a run row in the CSV? Options: a new column, or a convention such as duration 0 with the task text in the note field. | A dedicated `type` column — clearer than overloading existing fields, and the schema has changed before | |
| 27.9 | Should the icon that opens the popup sit on the task block, or would long-pressing the block be enough? | An icon — long-press is already carrying the label popup on that row | |
| 27.10 | With no tasks yet, does the block show a prompt such as "No tasks — tap to add", or just the bare icon? | A short prompt; a lone icon with empty space beside it reads as a fault | |


**Built in v62.**

- **`tasks.csv`**, deliberately CSV and not JSON: the intention is to merge a
  spreadsheet of tasks-by-category into it one day, so it is shaped for that
  now — one row per task, the label as plain text, the estimate in whole
  minutes.
- **Four statuses, cycled by tapping the marker:** open → doing → done →
  archived. Four rather than a tick box because "finished" and "get it off my
  screen" are different moments. Done still shows, greyed; archived drops out
  of the row but stays in the editor.
- **Tasks accrue their own time**, but only while their label's timer is
  running *and* the task is marked doing. **The label total stays
  authoritative.** Task time will usually sum to less, and that gap is honest
  — thinking, interruptions and forgetting to advance a task all live in it.
- Only one task can be doing at a time, across every label. Two accruing
  against one timer would double-count.
- **Show count is 0–5 per label**, so zero opts a label out entirely and no
  separate on/off is needed.
- The editor is a popup: add, reorder, edit, set estimates, cycle status, set
  the count.
- Accrued time is written on stop, on switching label, and about once a
  minute, so it survives the process being killed without writing sixty times
  a minute.
- The running row is `wrap_content` and every other row keeps its fixed
  height.

**Open questions for after some use:**

| # | Question | Leaning | Answer |
|---|---|---|---|
| 27.11 | Should the row let you advance a task without opening the editor? | Probably — but see how often it's wanted first | |
| 27.12 | Should completing a task write to the CSV log (27.4 said yes)? | Yes, once the shape of a task's life is clear | |
| 27.13 | Should the gap between label time and task time be shown anywhere? | Only if it turns out to be interesting | |
| 27.14 | Time-based fitting instead of a fixed count? | Only once estimates prove accurate | |

---

## 28. Fix the label popup's readout wrapping  ✅ built in v31

**What:** The readout currently wraps mid-phrase — `today` ends one line and
`00:00` starts the next. Split it deliberately instead:

```
Learning          goal 0:45
                  today 00:00
```

**Why:** A value separated from its own label reads as broken.

**Notes:**
- It's a single string in one `TextView`, so the wrap point is wherever the
  width happens to run out. Two explicit lines — or a small two-row layout —
  removes the guesswork.
- Worth aligning the two values so `0:45` and `00:00` line up under each other
  rather than sitting at different offsets.

**Open questions — to answer before building:**

| # | Question | Leaning | Answer |
|---|---|---|---|
| 28.1 | How should the two lines be arranged? | As described | **Name on the left, with `goal` and `today` stacked on the right**, so their figures align under each other. |

---

## 29. Reverse the manual reorder direction  ✅ built in v31

**What:** Tapping a label and choosing another should move **the label you
tapped** to sit above **the one you chose**. Currently it does the opposite:
the chosen label moves above the tapped one.

**Why:** Tapping something implies acting on it. "Move this one before that
one" is how the gesture reads, and the current behaviour is the reverse of
that — which means picking the wrong one is easy and the result is confusing.

**Notes:**
- The picker list should be in the **current manual order**, not the file
  order it uses now, so it matches what's on screen.
- `moveAbove(moving, target)` already exists and does the work; only the
  argument order at the call site changes, plus sorting the choices.
- The same gesture exists in the Settings label list — except that list was
  removed in v29, so there's only one call site left.

**Open questions — to answer before building:**

| # | Question | Leaning | Answer |
|---|---|---|---|
| 29.1 | Make the direction explicit in the title? | Yes | **Yes** — the title reads `Move "Learning" above…`. |
| 29.2 | Offer "move to the end"? | Yes | **Yes**, as the last entry in the list. |

**Also tidied here:** `moveAbove` no longer forces a label visible (hiding was
removed in v29), the layout picker's summary no longer says "4 shown of 9",
and both reorder paths now report a failed write rather than reverting
silently on the next launch.

---

## 30. A distinct colour per column mode  ✅ partly built in v32

**What:** Make each column mode identifiable at a glance, without reading the
button at the top.

With idea 31 there are **six** modes, which is more than colour alone can carry
reliably. Colour plus a prefix character is the workable combination:

**Colour says what kind of figure it is; the prefix says per-row or summed.**
Two signals doing separate jobs, rather than one signal doing both badly.

| Mode | Colour | Prefix |
|---|---|---|
| Goal | light mint `#9FD8A8` | — |
| SGoal | light mint `#9FD8A8` | `Σ` |
| Rem | blue `#6FAFC4` | — |
| SRem | blue `#6FAFC4` | `Σ` |
| Start | pale grey-blue | `@` |
| ETA | pale grey-blue | `~` |

Giving all six their own colour would make the prefix redundant and put more
meaning on colour than it can carry.

**Why:** The column shows four quite different things and currently only two
colours distinguish them, so the sums are indistinguishable from each other.

**Notes:**
- **The green-on-green problem is real.** The progress bar is dark green
  `#2A4A2E` and the goal text `#7DBE7A`. Readable, but they blend. Going
  lighter separates them without changing the bar.
- **Red is out**, as you say: it's the overage bar, and it reads as a problem
  rather than a mode.
- **The risk is a rainbow.** Colour already carries the active row (amber),
  progress (green), overage (burnt red) and notes (amber). Four more hues is a
  lot of meaning in colour alone, and colour-only distinctions fail in bright
  sunlight and for some kinds of colour blindness.
- Worth considering a small **prefix character** instead of or alongside
  colour — `Σ` before the sums would separate them from the per-row figures
  unambiguously and cost nothing. See 30.2.

**Open questions — to answer before building:**

| # | Question | Leaning | Answer |
|---|---|---|---|
| 30.1 | Are six hues too many? | Four plus prefixes | **Four distinct colours built.** The two clock modes (idea 31) will share a fifth and differ by prefix. |
| 30.2 | Prefix characters as well as colour? | Yes | **`Σ` built** on both sum modes. `@` and `~` follow with idea 31. |
| 30.4 | Do prefixes cost too much width? | Check on device | **Yes at the old width.** Resolved in v34 by widening the column rather than shrinking the type: the split moved from 38/40/22 to **34/40/26**, taking 4% from the label. Every figure stays 22sp. |
| 30.3 | Header button colour matches the column? | Yes | **Yes**, and the totals row now matches too. |

**Built in v32, corrected in v33:**

v32 shipped four separate colours, which was wrong — with a distinct colour per
mode the sigma carries no information. v33 collapses to two:

| Mode | Colour | Prefix |
|---|---|---|
| Goal | light mint `#9FD8A8` | — |
| SGoal | light mint `#9FD8A8` | `Σ` |
| Rem | blue `#6FAFC4` | — |
| SRem | blue `#6FAFC4` | `Σ` |

The goal colour moved from the bar's own green to a lighter mint, so a filled
row no longer has green text sitting on a green bar. Red is still reserved for
a passed goal in Rem mode, and the header button and totals row both follow
the column's colour.

---

## 31. Projected clock times — Start and ETA  ✅ built in v35

**What:** Two more column modes, both showing clock times rather than
durations, bringing the total to six.

| Button | Shows |
|---|---|
| `Goal` | each label's own goal |
| `Rem` | each label's time remaining |
| `SGoal` | goals summed down the rows |
| `SRem` | remaining summed down the rows |
| `Start` | **the clock time each label would start**, counting from a start time you set |
| `ETA` | **the clock time you'd reach each label from now**, using time already recorded |

`Start` is a fixed plan: set 8:00 and the rows lay out from there regardless of
what's happened. `ETA` recomputes from the current moment and what's actually
been done, so it answers "given where I am, when will I get to this?"

```
  Home Yard    00:00     8:00
  Fam Kaitlyn  00:00     8:20
  Work         00:00     8:35
```

The start time is set at the top left — where the session time used to sit —
via a tap-to-open clock picker.

**Why:** It turns the list from a set of targets into a schedule. Stack things
up in an order and you can see immediately whether the plan collides with a
2pm appointment, without doing the arithmetic.

**Notes:**
- Cheap to build: it's the cumulative goal already computed in
  `computeCumulative`, added to a start time and formatted as a clock.
- `TimePickerDialog` gives a touch clock with no extra work.
- The header lost its session time in v28 to make room for the column picker;
  this puts something more useful back in that space.
- **Both flavours, as separate modes.** `Start` is stable and readable against
  a calendar; `ETA` moves as the day slips but answers the more urgent
  question. Having both means neither has to compromise.
- `ETA` recomputes every tick, so its figures shift while you watch. That's
  correct behaviour but worth expecting — it's the one mode that isn't still.
- Labels with no goal add nothing, so they'd repeat the previous row's time.

**Open questions — to answer before building:**

| # | Question | Leaning | Answer |
|---|---|---|---|
| 31.1 | Static or live? | Both | **Both, as separate modes** — `Start` and `ETA`. |
| 31.6 | Does a label past its goal contribute to `ETA`? | Nothing | **Nothing.** Remaining floors at zero, so a finished label doesn't push later ones out. |
| 31.7 | Are `Start` and `ETA` the right labels? | Yes | **Yes.** |
| 31.2 | Does the start time persist across days? | Persist | **Persists**, defaulting to 6:00. Most days begin at roughly the same hour. |
| 31.3 | Does `Start` use goals or remaining? | Goals | Goals, since it's a plan laid out from scratch. `ETA` uses remaining by definition. |
| 31.4 | Past midnight? | Roll over | **Rolls over** and shows the next day's clock time. Not separately marked — worth revisiting if it proves confusing in practice. |
| 31.5 | Mark a row whose projected time has passed? | Dim it | **Dimmed**, in `Start` mode only. In `ETA` every figure is in the future by definition. |

**How it turned out:**
- The header shows `Start  6:00a` in place of the date, and tapping it opens a
  clock picker. The date was redundant — it's on the status bar.
- Clock figures use a compact form (`@6:00a`, `~2:30p`). The full `6:00 AM`
  wouldn't fit the column, and 24-hour would read oddly beside the durations.
- **Fixed in v37:** double-digit hours (`@11:00a`) are seven characters where
  single-digit ones are six, and overflowed the column — the minutes were
  silently cut, so `@11:00a` displayed as `@11:`. The split moved from
  34/40/26 to **30/40/30**, and the column now ellipsises, so any future
  overflow shows as `…` rather than passing for a value.
- `@` marks the planned start, `~` the live estimate, matching the `Σ` used
  for the sums. Both share a colour, since they're the same kind of value.
- Needed exclusive prefix sums as well as the inclusive ones the sums use — a
  row's projected time is everything *above* it, not including itself.

---

## 33. Label popup: larger buttons, coloured goal adjustments  ✅ built in v41

**What:** Two changes to the per-label popup.

1. **Larger type on the adjustment buttons.** They're 12sp, which is small for
   the most-tapped controls in the app. Around 15–16sp with taller buttons.
2. **Colour the goal row by direction** — the `+` buttons green, the `−`
   buttons red — so adding and subtracting are distinguishable without reading
   each one.

**Why:** The buttons are the whole point of the popup and currently read as an
undifferentiated grid. Direction is the thing most worth signalling.

**Notes:**
- Buttons are 42dp tall at 12sp; 48dp at 15sp would sit comfortably and still
  fit the dialog.
- **Red is already in use** for a passed goal in the Rem column and for the
  overage bar. On a button labelled `-15` the meaning is unambiguous, but it's
  worth a look on the device to confirm it doesn't read as a warning.
- The greens and reds should be muted enough not to fight the dialog —
  something like `#7DBE7A` and `#C97064`, both already in the palette.

**Open questions — to answer before building:**

| # | Question | Leaning | Answer |
|---|---|---|---|
| 33.1 | Time row treated the same? | Yes | **Blue**, distinct from the goal row's green and red. Direction matters less there — the row is a correction either way. |
| 33.2 | Text or background? | Text | **Text.** Coloured fills would dominate the dialog. |
| 33.3 | Delete still red beside red − buttons? | Yes | **Yes**, and it's now half width beside Done, two rows below, so they don't crowd. |

**Built in v41:**
- `+30` / `−30` removed, leaving four per row — which is what gave the larger
  type room without the dialog growing sideways.
- Buttons 12sp → 18sp, height 42dp → 52dp. "Goal" and "Time" 12sp → 17sp.
- Goal adds green `#7DBE7A`, goal subtracts red `#C97064`, the whole Time row
  blue `#6FAFC4`.
- **A `Done` button** beside Delete, so closing no longer means tapping outside
  the dialog and hoping.

---

## 35. Delete a logged run  ✅ built in v38

**What:** Remove an individual entry from a label's history, from the Notes
screen — either the Delete button in the edit dialog, or a long-press on the
entry.

**Why:** A run started by mistake, or against the wrong label, currently stays
in the log forever. Editing its note doesn't remove the time.

**How it turned out:**
- The confirmation names the entry exactly — date, time, duration and note —
  since one run looks much like another in a list.
- **A run from today also comes off today's counters**, so the main screen and
  the log agree. Totals are kept as running counters rather than derived from
  the CSV, so without this they would silently disagree.
- **An older run only loses its line.** The day it belonged to rolled over long
  ago and has no counter left to adjust. The confirmation says which case
  applies rather than leaving it to be inferred.
- Any adjustment recorded against the run is included in what's subtracted.
- Deleting shifts every line index after it, so the screen re-reads the file
  rather than reusing what it held.

---

## 36. Show how far past a goal, not a floored zero  ✅ built in v38

**What:** In `Rem` mode, a label past its goal showed `0:00`. It now shows the
overage as a negative — `-0:15` — in a bright peach `#FFAE73`.

**Why:** `0:00` says only "no time left". `-0:15` says how far past, which is
the more useful figure and the one that was missing.

**Notes:**
- The colour had to clear the burnt-red overage bar behind it, so another red
  would have blended. Peach is lighter and warmer than the bar.
- `0:00` exactly — goal met precisely — keeps the old red, since it's neither
  a shortfall nor an overage.
- **The clock modes needed no change.** `ETA` counts from now, so an overrun
  has already moved "now" later and every projection with it. `Start` is a
  fixed plan by definition and shouldn't reflect what's happened.
- `SRem` still floors each label at zero before summing, so being over on one
  label doesn't cancel time still owed on another.

---

## 37. Deleting a run left the time behind  ✅ fixed in v39

**The bug:** A 17-second run plus a −5-minute adjustment left a label at
−4:43. Deleting the run from the Notes screen changed nothing.

**Two causes, compounding:**

1. **The log stored whole minutes.** A 17-second run was written as `0`
   minutes, so deleting it subtracted `0 × 60000 = 0` ms. The counters keep
   exact milliseconds; the log was rounding them away, so a run under 30
   seconds was worth nothing to delete.
2. **Standalone adjustments were hidden.** `readRunsForLabel` deliberately
   skipped rows with zero duration and a non-zero adjustment. The row actually
   holding the −5 was never on screen and couldn't be deleted — or even seen,
   which is why the total looked inexplicable.

So the only visible row was worth zero, and the row that mattered was
invisible.

**A third problem found while fixing it:** both note writers rebuilt a row from
its first six fields, which would have silently discarded the new `duration_ms`
column every time a note was saved.

**The fix:**
- A seventh CSV column, `duration_ms`, carrying exact elapsed time.
  `duration_minutes` stays for readability. Existing files have their header
  upgraded in place; existing rows keep six fields and fall back to the rounded
  value, which is the best available for time already logged.
- Adjustments are listed, marked `adjustment -5m` in a distinct colour, and can
  be deleted like anything else. They show regardless of the "runs with no
  note" filter, since they never carry a note and hiding them is what made the
  original problem unexplainable.
- Deletion subtracts `duration_ms + adjustment`, so it exactly reverses what
  the row contributed.
- Both note writers now preserve every column after the note.

**Worth noting:** rows logged before v39 have no exact millisecond figure, so
deleting one subtracts its rounded minutes. Nothing can recover precision that
was never written down.

---

## 38. No projected time for a label with no goal  ✅ built in v40

**What:** In `Start` and `ETA`, a label whose goal is zero now shows nothing
in the right-hand column.

**Why:** It contributed nothing to the projection, so it repeated whatever the
row above showed — which read as though it were scheduled at that time. Blank
says the truth: it isn't part of the plan.

**Notes:**
- Applied to `ETA` as well as `Start`. The reasoning is identical, and having
  one blank while the other repeated a time would be inconsistent.
- Matches how the other modes already behave: `Goal` and `Rem` are blank
  without a goal.
- `SGoal` and `SRem` still show the running total on such a row, which is
  correct — a sum is about everything above it, not the row itself.
- Goals cannot be negative: floored at zero when adjusted in the popup, when
  read from `labels.txt`, and when restored from a layout.

---

## 39. Larger type on the Notes screen  ✅ built in v42

**What:** The notes list was 12sp for the timestamp and 14sp for the note.
Both up roughly 60%: **19sp** and **22sp**. "adjustment" abbreviated to
"adj".

**Why:** The note text is the content of that screen and was the smallest type
in the app.

**Notes — what the larger size forced:**
- At 19sp monospace, the timestamp line ran past the screen edge. Rather than
  hold the font down, the string got shorter: `6:41 AM` → `6:41a`, the
  separator from `   ·   ` to ` · `, and the adjustment from `  (+15m)` to
  ` +15m`.
- Worst case — a run over an hour that also carries an adjustment — is still
  marginally wide and will wrap to a second line. It doesn't clip, and the
  combination is rare.
- The empty-state message, input caption, note input and filter label were all
  scaled to match, or they'd have looked tiny beside 22sp entries.

---

## 40. A more visible progress bar  ✅ built in v43

**What:** Both bar segments brightened.

| | Was | Now | Contrast against the track |
|---|---|---|---|
| Progress | `#2A4A2E` | `#3A6841` | 1.47 → 2.25 |
| Overage | `#4A2A18` | `#8C4726` | 1.14 → 2.12 |

**Why:** A contrast ratio of 1.0 means indistinguishable. The overage red was
at **1.14** against the track it sat on — so passing a goal barely changed the
row's appearance, which defeated the point of having the bar at all.

**Notes:**
- The ceiling here is text legibility, not taste. Everything on the row sits
  *on* the bar, so brightening it costs contrast for the label, timer and
  right-hand figure. These values keep the 32sp timer above the 3:1 needed for
  large text and the label above 5:1.
- **Found while measuring:** the "goal exactly met" colour `#C97064` scored
  1.85 on the brighter bar — unreadable. Both the at-goal and past-goal cases
  now use the peach `#FFAE73`, which reads at 3.5+. The text already
  distinguishes them (`0:00` versus `-0:15`), so the colour needn't.
- If these still read as too subtle, there's room to go brighter, but the
  timer's contrast is what will give first.

---

## 41. A second figure under the label  ✅ built in v44

**What:** A fifth pill, leftmost in the header, choosing a second smaller
figure that sits beneath the label. Any of the six column modes, or `None`.

```
  Fitness                00:00     @9:30a
  Σ1:30

  Home Org               00:00    @11:00a
  Σ2:15
```

**Why:** The pairings answer questions a single column can't. `Start` against
`ETA` shows the slip between plan and reality without toggling. `Goal` against
`Rem` shows target and remainder together.

**Notes:**
- **Position marks it as secondary**, not colour. The pill's colour follows
  what it's showing, exactly like the primary, so colour keeps meaning "what
  kind of figure this is" rather than "which pill this is".
- The header lost the word "Start" before the time to make room — `6:00a`
  rather than `Start 6:00a`. The pill needed about 44dp and that bought it.
- **Rows get a taller minimum (52dp, was 44dp) when a second figure shows.**
  Two lines need roughly 43dp, so the old floor would have clipped on a short
  screen. Fewer rows fit before scrolling, which is the trade for turning it
  on.
- With `None`, the second view is `GONE` and the label centres exactly as
  before.
- When a row has a value but the figure is empty — a goalless label in `Goal`
  mode — the line stays in the layout so the label doesn't sit at a different
  height row to row.

**Worth noting:** this forced a useful refactor. The column rendering was
inline in `bindValues`; it's now a single `columnFigure(mode, entry, position)`
returning text and colour, shared by both figures. They can't drift apart in
formatting or colour, and the running totals are computed on both bases every
tick rather than only for the selected mode.

---

## 42. A lone minus sign in Rem  ✅ fixed in v46

**The bug:** A label with a 21-minute goal sitting at 21:53 showed only `-` in
the Rem column.

**Cause:** `fmtGoal` returns an empty string for zero, which is correct for
"this label has no goal" and wrong for a measured value. 53 seconds past the
goal divides to 0 minutes, so the row rendered the minus sign and nothing
after it.

**The same fault elsewhere:** the `Σ` sums used the same formatter behind a
`running > 0L` guard, so a running total under a minute would have rendered a
lone sigma. Found while checking the first fix, not reported.

**The fix:** one `fmtMeasuredMs` that always renders, zero included. Through
the crossover, with a 21:00 goal:

| Elapsed | Shows | Colour |
|---|---|---|
| 20:59 | `0:00` | blue |
| 21:00 | `0:00` | peach |
| 21:53 | `0:00` | peach |
| 22:00 | `-0:01` | peach |
| 23:00 | `-0:02` | peach |

No sign while the overage rounds to zero — `-0:00` reads as a mistake, and the
colour already says you're past. The sign appears at a full minute over.

**Also improved:** with a minute or less remaining, the column showed nothing
at all. It now shows `0:00` in blue, so a label about to reach its goal looks
different from one with no goal set.

---

## 43. Four gesture zones, with dividers  ✅ built in v45

**What:** Each column of a row now does something distinct, and fine vertical
lines mark where one ends and the next begins.

| Zone | Tap | Long-press |
|---|---|---|
| Note icon | Notes screen | Drag to reorder |
| Label | Move above another | Drag to reorder |
| Timer | Start, or stop if running | — |
| Right column | Goal & time popup | Slide to set the goal |

**Why:** Dividers were the original request, but the zones didn't warrant them
— the timer and right column behaved identically, and long-press was uniform
across the whole row. Giving each column its own meaning made the lines worth
drawing.

**The goal slider:**
- Long-press the right column, then slide. Up adds, down subtracts.
- **The ladder is 5, 15, 30, 45**, then 15 more for each further step. The
  first step is small for nudging a goal a few minutes; the gaps open up so a
  long drag covers a working day without much thumb travel.
- A step is 24dp of travel.
- **Nothing is written until you lift.** The column shows the pending value in
  amber meanwhile, which puts the preview exactly where the finger already is
  rather than needing a floating tooltip.
- The list is held still for the duration, via
  `requestDisallowInterceptTouchEvent`, or the row would fight the scroll.
- On release, if the order depends on goals — Goals or Remain sorting — the
  list re-sorts and the row moves to its new place.
- Floored at zero, as everywhere else.

**Notes:**
- Tapping a running timer now **stops** it. Previously tapping a running row
  did nothing.
- A long-press with no movement changes nothing and doesn't open the popup —
  the release path deliberately skips `performClick`, or every slide would end
  with the popup appearing.
- Dividers are `#4A6360` at 1dp, inset 9dp top and bottom so they read as
  separators rather than a grid. They sit on the bar, so they had to work
  against both the dark track and the green fill.

---

## 44. Show at a glance that nothing is running  ✅ built in v46

**What:** When no timer is running anywhere, the timer column carries a
translucent red wash — on every row, not just one.

**Why:** With ten labels and a scrolling list, the only sign a timer was
running could be off screen. Nothing said "you are not currently tracking
anything", which is the state most worth noticing.

**Notes:**
- On **every** row deliberately. Marking one row wouldn't help when the list
  is scrolled away from it; this is a property of the app, not of a label.
- 22% opacity, so the progress bar still reads through. Over the dark track it
  gives a warm grey, over the green bar a muted olive — visible in both cases
  without hiding what's underneath.
- The timer column became full height so the wash fills the column rather than
  banding around the text.
- `bindValues` runs for every row each tick, so the wash appears and clears
  immediately on a start or stop.
- **`getActiveLabel` never returns null** — it returns a sentinel — so the
  obvious check would silently never fire. `isRunning` already existed for
  this.

---

## 45. Export one label from its Notes screen  ✅ built in v50

**What:** An Export button beside Done on the Notes screen, writing a CSV of
that label's rows only, through the same share sheet and location dialog as
the Settings export.

**Why:** The Settings export is everything, for backup. This is one label for
looking at — a month of one kind of work, in a spreadsheet, without filtering
thousands of rows by hand first.

**Notes:**
- **The screen's filter applies.** With "Show runs with no note" off, the file
  contains only runs with notes, so what you send matches what you were
  looking at. The location dialog says which of the two you got.
- Written beside the main log as `Focus_<label>.csv`, so the existing
  FileProvider path covers it with no manifest change. Overwritten each time
  rather than accumulating.
- Same header as the main log, so both open identically.
- Nothing matching produces a message rather than an empty file.

**Also changed here — adjustments now obey the filter.** v39 made them always
visible, on the reasoning that an invisible adjustment is what made a wrong
total impossible to explain. In use that was wrong: with the filter on, rows
with no note kept appearing. They now hide like anything else without a note.
The trade is that finding a stray adjustment means turning the filter on —
which the empty-state message now says.

---

## 46. Elapsed-time slider, and a flat 5-minute step  ✅ built in v49

**What:**
- **Long-press the timer column and slide** to correct recorded time, the same
  gesture the right column uses for goals.
- **Both sliders now step a flat 5 minutes.** The accelerating ladder was
  replaced.
- The timer auto-sizes, so an hour-long value fits.

**Why the flat step:** with 5, 10, 15, 30… you had to remember how far you'd
come to know what the next step would add, which made the value hard to aim.
A flat rate is slower over long distances and far more predictable.

**How the time slider records it:** through `TimerStore.adjust`, which already
handles both cases — on the running label the correction folds into that run's
row; on an idle one it writes an adjustment of its own. Exactly the behaviour
the popup's ±5/±15 buttons have always had, now available without opening
anything.

**Notes:**
- Floored so a label can't go below zero for the day.
- The timer shows the pending total in amber while sliding; nothing is written
  until release.
- **Auto-sizing replaced a fixed 32sp.** At an hour the string grows from
  `09:07` to `1:09:07` and overflowed — the screenshot showed `1:09:0`. It now
  scales between 20 and 32sp, so only rows that need it shrink and short
  timers stay large. This removes the whole class of problem rather than
  rebalancing column widths again.

---

## 50. Popup header shows two different units  ✅ built in v51

**What:** In the label popup the goal reads `0:45` and today's time reads
`00:00` — minutes in one, seconds in the other. Make them consistent.

**Why:** Two adjacent figures in different formats invite misreading.

| # | Question | Leaning | Answer |
|---|---|---|---|
| 50.1 | Both to h:mm, or both with seconds? | h:mm — seconds aren't useful when setting a goal | |

**Built in v51:** both read h:mm. Today's time was using the seconds
formatter shared with the main screen's timer, where seconds do belong.

---

## 51. Make the no-timer state harder to miss  ✅ built in v51

**What:** Strengthen the idle wash built in idea 44. Currently 22% alpha on
the timer column; it isn't registering in use.

**Why:** Scrolled away from the running row, there's no way to tell whether
anything is running — which is the whole point.

**Notes:**
- Options: raise the alpha, wash the whole row rather than one column, or
  something that moves, since motion catches the eye where colour doesn't.
- The constraint is the same as always: text sits on top of it.

| # | Question | Leaning | Answer |
|---|---|---|---|
| 51.1 | Stronger wash, whole row, or something animated? | Try a stronger wash across the full row first | |
| 51.2 | Should the header show it too, so it's visible without any row? | Yes — that's the one thing always on screen | |

**Built in v51, half of it reverted in v53:**
- The header shows a red dot before the start time when nothing is running —
  the header being the one thing on screen at any scroll position (51.2).
  **This is the part that works**, and on its own it's enough.
- The whole-row wash at 35% was a mistake. The wash sits *on top of* the
  progress bars, so with nothing running every row flattened to the same
  olive and the green/red distinction vanished — the app lost its colour
  language to gain an indicator it didn't need.
- Reverted to the original 22% on the timer column alone.
- **v67: the timer column alone, at three times the intensity** — alpha 0x38
  → 0xA8. Confined to one column it can be far stronger without flattening
  the row, which is what made the whole-row version wrong.
- At that strength the muted timer text falls to about 2:1, under the 3:1
  large text needs, so it brightens to near-white while the wash is on —
  back above 3.8 against both the dark track and a green bar.

**The lesson:** contrast maths said the text was still readable, and it was.
What it couldn't say was that the wash destroyed the meaning of everything
underneath it. Readability isn't the only thing an overlay can break.

---

## 53. Slightly larger label font  ✅ built in v51

**What:** Label text up from 17sp. The second line stays 13sp.

**Notes:** Labels already ellipsise at 30% width, so a larger font truncates
sooner. Worth checking "Social Building" before committing.

**Built in v51:** 17sp → 19sp, second line unchanged at 13sp.

Labels over about eight characters ellipsise a little sooner. “Fam Kaitlyn”,
“Stewardship” and “Social Building” were already truncating at 17sp, so
nothing newly truncates — they lose a character or two more.

---

## 55. Reminder sound is unreliable  ✅ fixed in v57

**What:** The interval reminder's sound doesn't play dependably.

**The cause, confirmed:** exactly that. `createNotificationChannel` on an id
that already exists does nothing — Android fixes sound and importance at
creation. The code chose between two channel ids for heads-up versus quiet, but
applied the *sound* toggle to the same id either way. So whatever the setting
was the first time the app ever ran is what it stayed, and the toggle has never
done anything.

**The fix:** one channel per combination, chosen at send time rather than
edited. Three now — pop-up with sound, pop-up silent, quiet — with `_v2` ids so
existing installs get fresh ones. The originals are deleted, so they don't
linger in Android's notification settings. Explicit `AudioAttributes` too, which
some launchers want before they'll play anything.

---

## 59. Notification quality  ✅ built in v57

**What:** The ongoing notification should name the label, and the additional
timers listed under it need a sensible order — "Service" currently leads for
no clear reason.

**Built in v57.** It was library order — which is to say, no order. Now:
1. the running label
2. labels with a goal, most time remaining first
3. everything else, most time recorded first

**Labels with neither a goal nor any recorded time are left out entirely.**
They were most of the list and said nothing — twenty-odd entries reading
`00:00` pushed the ones that mattered off the end.

The title already names the running label; it was the body that had no order.

**v58: the body shows time remaining, not elapsed.** A list of elapsed times
says what's been done; remaining says what's left, which is what you'd open a
notification to find out. Past a goal it reads as a negative, so overrunning
looks different from finishing. Labels with no goal have nothing to remain and
still show what's recorded.

Figures are h:mm there — it's a planning view and seconds are noise. The title
keeps seconds, being the one figure that visibly ticks.

---

## 64. Clear a note, and larger type for writing one  ✅ built in v54

**What:**
- A **Clear note** action in the note editor, between Cancel and Save.
- The note input 50% larger: 18sp → 27sp on the Notes screen, and the editor's
  own field at 22sp.

**Why:** Emptying the text and saving worked but took two deliberate steps, and
the field you actually write in was among the smallest type in the app.

**Notes:**
- **Clear is not Delete.** Clear keeps the run and its recorded time and
  removes only the words; Delete removes the row and its time. Both are in the
  same dialog, so the distinction is stated in each confirmation.
- **The editor is now a custom layout.** `AlertDialog` offers three buttons and
  this needed four — Delete, Cancel, Clear note, Save.
- Clearing asks first, since typed notes can't be recovered.
- **With the filter on, clearing makes the row disappear** — a run with no note
  isn't listed. The confirmation says so rather than leaving it to surprise.

---

## 65. Show the delta while sliding  ✅ built in v55

**What:** While either slider is in use, the running total of the change —
`+10`, `-15` — appears to the left of the figure being changed, clear of the
finger. It disappears on release.

**Why:** Sliding 5 or 10 minutes moves the figure by less than the fingertip
covers, so the first couple of steps were invisible. You could feel the drag
working but not see it.

**Where each one goes:**

| Sliding | Figure shown | Delta appears in |
|---|---|---|
| Goal (zone 4) | the right column | the timer column, immediately left |
| Recorded time (zone 3) | the timer column | the label line, immediately left |

**Notes:**
- The label's own text is replaced for the duration rather than appended to —
  you know which row you're touching, and appending would truncate.
- **The second line under the label looked like the obvious home for the time
  delta and isn't.** With no secondary figure chosen that line is hidden and
  the row minimum is 44dp; making it appear mid-drag needs ~45dp, so the row
  would clip for as long as the finger was down. The label line costs no
  height at all.

---

## 66. Three totals, and the ±5m buttons removed  ✅ built in v59

**What:** The `−5m` / `+5m` pair at the bottom left is gone. In its place, a
third total — the same figure for whatever the second line under each label is
showing.

The bottom row now has one total under each column:

| Under | Shows |
|---|---|
| Label column | total for the secondary figure |
| Timer column | total time recorded |
| Right column | total for the primary figure |

**Why:** Long-pressing a row and sliding replaced the buttons, which only ever
adjusted the running timer and needed a running timer to do it. Three aligned
totals are a better use of the space.

**The clock modes needed a decision.** Adding two times of day is meaningless,
so `Start` and `ETA` can't sum. They show **when you would finish** instead —
the day's start plus every goal, or now plus everything still to do. That's the
figure the column is building toward on its last row, so the total is its
natural conclusion rather than an invented one.

**Notes:**
- Prefixes and colours carry through, so a total reads the same way as the
  column above it: `Σ8:35` in mint, `~2:15p` in grey-blue.
- Blank when the secondary is set to None.
- `adjustActive` had no caller left and was removed.

---

## 67. Dialog colours, and four task-editor refinements  ✅ built in v63

**The bug:** four dialogs — Edit task, New label, Save layout, Rename layout —
put the app's cream text on `AlertDialog`'s white background. Effectively
invisible. Reported on one; the other three had it too.

**Cause:** those fields were built in code with dark-theme colours and handed
to a plain `AlertDialog`, which draws white. The dialogs that looked right —
the label popup, the task editor, the note editor — each carry their own
background.

**The fix:** one shared `dialog_text_input.xml` with a title, one or two
fields, and Cancel / Save plus an optional Delete. All four now use it, so they
match the rest of the app and there is one place to change rather than four.
This is most of what idea 61 was about.

**Three refinements alongside:**
- **Done saves a part-typed task.** Typing a task and tapping Done added
  nothing. It now saves, with its estimate. Same trap as idea 4's unsaved
  label box.
- **Larger status markers** — 15sp → 22sp in the editor, and the row's task
  text 14sp → 17sp. It's the control you tap to advance a task and it was the
  smallest thing on the row.
- **Times reversed, with a percentage:** `30m / 300m 10%` — what it has taken,
  what was estimated, how much of the estimate that uses. Past 100% keeps
  counting and turns peach, since how far over is the useful part. With no
  estimate there's nothing to be a percentage of, so it shows the actual
  alone.

---

## 68. Two-line tasks, and the totals row clipping  ✅ built in v64

**The clipping:** the three totals were cut off at the bottom — the descender
on `@8:30p` lost its tail and the digits looked shaved.

`includeFontPadding="false"` on a `wrap_content` height. That attribute removes
the space a font reserves for ascenders and descenders, which is right in the
rows where vertical space is fought over and wrong here, where the row is 48dp
and nothing is competing. The three totals now take the full row height and
keep their font padding.

**Two-line tasks:**

```
▸ 30m / 300m  10%
  RDM-1608 - Ryan uploaded not licensed detail
```

Marker and figures on the first line, description beneath with the full width
to wrap into. Long titles were unreadable sharing a line with the times.

**Notes:**
- **The description wraps rather than truncating**, so a task can be three or
  four lines and the running row's height varies with its content. The 0–5
  show count is the dial; 2 or 3 is likely the practical setting now.
- Figures show even at zero — `0m / 300m 0%` — so the shape doesn't change
  when a task is started. Only a task with no estimate differs, having nothing
  to be a percentage of.
- The row block is still **one TextView**, with spans making the figure lines
  smaller and dimmer than the description. No nested list, no recycling.
- One `taskTimes` formatter shared by the row and the editor, so they can't
  drift apart.

---

## 69. Task count in the label popup  ✅ built in v65

**The bug:** setting a label's task count to zero hid the row's task block —
and the edit icon with it — leaving no way to reach the editor or raise the
count again. The label was stranded short of clearing app data.

Exactly the trap idea 24 removed for hidden labels. I guarded the case of a
label with *no tasks*, which keeps its icon, and missed the identical case of a
count of zero.

**The fix:** a Tasks row in the label popup, beside Goal and Time:

```
Tasks   −   [3]   +     Edit…
```

Long-press any row's right column to reach it. Neither the count nor the editor
now depends on the block that disappears.

**The editor's own count control was removed.** Two controls for one setting is
how they drift, and there'd be no telling which you last used. The popup is the
single place.

**Worth generalising:** twice now a control has been the only route to
something it can hide. Anything that can be switched off needs its switch
somewhere that switching off doesn't affect.

---

## 70. Screen names, and the task count in both places  ✅ built in v66

**What:** Every screen and dialog except the main one now says what it is.

| Screen | Heading |
|---|---|
| Settings | `Settings` |
| Notes | `Notes — Work` |
| Label popup | `Label — Work` |
| Task editor | `Tasks — Work` |
| Layout picker | `Layouts` |

Confirmations keep their question titles — `Delete “Work”?` tells you more than
a screen name would.

**Also: the task count is back in the editor**, alongside the copy in the label
popup added in v65.

Removing it was a mistake on a bad argument. I said two controls for one
setting would drift apart; they can't, because both read and write the same
stored value and both show it. The real problem in v65 was different — the
*only* control was in a place it could hide — and that was fixed by adding one,
not by removing one.

---

## 71. Done tasks unreadable on the green bar  ✅ fixed in v68

**The bug:** completed tasks were greyed to `#5C736E`, which on the green
progress bar scores **1.28** contrast. Effectively invisible.

**Why dimming can't work here:** the task block sits on the progress bar, so
its background is green on a row with time against it and near-black on one
without. Anything faint enough to read as "finished" on the dark track
disappears on the green. Measured: `#8FA39E` gives 2.44, `#A9BDB8` 3.29 —
still under the 4.5 normal text needs.

**The fix:** mark done by **striking the text through**, not by dimming it.
A line says finished at any brightness, so the colour is free to be legible.
Done tasks now use `#CBD9D5` — 4.46 on green, 10.03 on the track — with a
strikethrough.

The figures line had the same fault at `muted`, and moved to the same colour.

**Also fixed here:** wrapped lines fell back to the left margin, so a long
title's second line started under the status marker instead of under its own
text. A `LeadingMarginSpan` now hangs them, keeping each task one visual
block.

---

## 72. Totals over a date range — on its own screen  ✅ built in v69

**What:** Hours by label between two dates. Default from the previous Sunday
midnight to now, with a date picker to change the start — a fortnight, a
month, whatever.

**Why:** "How many hours did I put in this week" is the obvious question the
app can't currently answer, despite having every figure needed.

**Why not on the main screen** — tried and set aside, because a range breaks
three things there that a separate screen doesn't have to care about:

- **Goals are daily.** Over a week, is a 6-hour goal 6:00 or 42:00? Scaling by
  days makes remaining meaningful but assumes you work every day, which drags
  in idea 7's scheduling questions. Leaving it daily makes the comparison
  meaningless.
- **The bar** would be 700% over on every row, so uniformly red and useless.
- **`Start` and `ETA`** plan a single day. They mean nothing across a
  fortnight.

On its own screen, none of these arise: there is no bar, no goal column and no
projection to reconcile.

**Notes:**
- **The data comes from `timer_log.csv`**, not the preference counters. Those
  hold today only. Everything needed is in the log — `run_start`,
  `duration_ms`, `adjusted`, per label.
- Sum completed days from the log once and cache; add today's live counters on
  top. Then it recomputes on a date change or a midnight crossing, not every
  second.
- **Show h:mm, not seconds.** A week is `41:23:07` — eight characters, and
  seconds mean nothing over that span.
- Natural home for things that would crowd the main screen: week against week,
  where the unattributed gap between label and task time went, a label's share
  of the total.

**Open questions — to answer before building:**

| # | Question | Leaning | Answer |
|---|---|---|---|
| 72.1 | Reached from where? | Settings | **Settings**, beside Export. The main screen stays a working view; this is a reviewing one. |
| 72.2 | Does the default recompute? | Recompute | **Recomputed each time the screen opens** — the most recent Sunday at midnight, today included. A picked date holds until you leave. |
| 72.3 | Configurable week start? | Yes | **Not yet** — Sunday, changed by picking a date. Worth a setting if you change it often. |
| 72.4 | Task totals too? | Toggle, off | **Not built, and it can't be yet.** `TaskStore` keeps a lifetime `actualMs` per task with no timestamps, so there is no way to know how much fell inside the dates. A figure that ignored the range on a range screen would mislead. Needs task time logged with timestamps first — idea 27.12. |
| 72.5 | Labels with no time in the range? | Shown | **Shown**, dimmed, sorted to the bottom. Absence is information — a column of zeros says which labels you carry but don't use. No prompt to delete them. |


**How it turned out:**
- Sorted by hours, so the answer is the first line. Percentage of the range's
  total beside each.
- **The bar is a share of the largest label**, not progress toward a goal.
  Goals are daily and mean nothing over a fortnight; a share always means
  something. Scaled against the largest rather than the total, or every bar is
  a sliver once there are a dozen labels.
- **`48:55` needs its context**, so a line underneath gives the day count and
  the daily average. The same total across a week and a fortnight are very
  different facts.
- h:mm throughout. Seconds are noise across a week.
- **Where the figures come from:** completed runs from `timer_log.csv`, but
  only up to the start of today. Today's time is still only in TimerStore's
  counters and is added separately — the log and the counters describe the
  same time for today, so reading both would double it.
- Older log rows have no exact millisecond figure and fall back to their
  rounded minutes.
