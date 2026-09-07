# Focus Ledger — Work/Other Timer (persistent tracking)

An Android app with two toggle timers (Work / Other, mutually exclusive), a
Reset button with a confirmation prompt, and a **persistent notification**
that keeps showing and controlling the timer even when the app isn't open.

## How the "stays running" part works
When you start a timer, the app starts a foreground service — the same
mechanism music players and navigation apps use to keep running in the
background. It shows an ongoing notification with live totals and
**Start/Stop Work** and **Start/Stop Other** buttons right on it, so you can
toggle without opening the app at all. Android is strongly discouraged from
killing a foreground service, and even if the process is ever killed by the
OS, the elapsed time is calculated from stored timestamps, not from the
service staying alive in memory — so totals stay accurate either way.
The notification (and the service) automatically go away once both timers
are stopped.

One realistic caveat: some phone brands (Samsung, Xiaomi, Huawei, OnePlus,
etc.) have aggressive battery-optimization settings that can still kill
background services despite Android's API. If you notice tracking stop
unexpectedly, go to Settings → Apps → Focus Ledger → Battery, and set it to
"Unrestricted" / disable battery optimization for this app.

## Get a compiled APK — no software install required

This project builds itself in the cloud via GitHub Actions. You only need a
free GitHub account and a web browser.

1. **Create a free account** at https://github.com/join (skip if you have one).
2. **Create a new repository**: click the "+" top-right → "New repository" →
   name it anything (e.g. `focus-ledger`) → Public or Private, either works →
   **Create repository**. Leave it empty — don't add a README here.
3. **Upload this project**: on the new repo's page, click
   **"uploading an existing file"** → drag in *every file and folder* from
   inside this `FocusLedgerWidget` folder (including the hidden `.github`
   folder — if your file picker hides it, drag the whole extracted folder
   onto the page and GitHub will pick up everything). Commit the upload.
4. **Watch it build**: click the **Actions** tab. A "Build APK" run starts
   automatically (~3–5 minutes). Green check = done, red X = failed — if it
   fails, copy the error text here and I'll fix the file.
5. **Download the APK**: once green, click into that run → **Artifacts** at
   the bottom → download `FocusLedger-debug-apk` (a zip containing
   `app-debug.apk`).
6. **Install on your phone**: transfer the `.apk` to your phone, tap it, and
   allow "install from this source" if prompted.
7. **First launch**: the app will ask for notification permission — allow
   it, that's what lets the persistent tracker show up.

## How it works
- Tapping **Work** starts the work timer and stops Other if it was running.
- Tapping the running button again stops it.
- Same two actions are available directly on the notification.
- Totals persist and keep counting correctly even if the app or service
  gets killed by the OS — they're computed from timestamps, not a live loop.
- **Reset totals** asks "Are you sure?" before clearing both timers.

## Files of note
- `TimerStore.kt` — all timer state (SharedPreferences), shared by the
  activity and the service.
- `MainActivity.kt` — the app screen.
- `TimerForegroundService.kt` — the persistent notification/tracker.
- `.github/workflows/build.yml` — the cloud build recipe.

## Known limits of this v1
- Single running total until you reset it — no automatic daily rollover yet.
- This is a debug build (unsigned, fine for installing on your own phone,
  not for Play Store distribution).
