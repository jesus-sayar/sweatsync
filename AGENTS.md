# AGENTS.md

Guidance for AI coding agents working on SweatSync.

## What this is

Native Android app (Kotlin, AppCompat + Material 3 components) that reads treadmills and indoor bikes over BLE (FTMS), records a workout, writes a FIT file, and uploads it to Garmin Connect and/or Strava. Package `com.sweatsync`, minSdk 29, compileSdk 34.

## Commands

```
./gradlew assembleDebug        # build the APK
./gradlew testDebugUnitTest    # unit tests
./gradlew lintDebug            # Android lint (must have no errors)
./run.sh                       # build, install and launch on the connected phone
tools/emulator.sh setup|start|install|onboarding|treadmill|bike   # test on the computer (see below)
```

Use JDK 17 (`JAVA_HOME=/opt/homebrew/opt/openjdk@17`). The Android SDK path lives in `local.properties` (not versioned).

### Testing without a phone

The emulator's virtual Bluetooth radio (netsim) can host a virtual FTMS machine: `tools/ftms_simulator.py` (Bumble) advertises service `0x1826` with its machine type, accepts connections and notifies Treadmill or Indoor Bike Data every second, so the app's real scan and GATT code run against it. Typical loop: `tools/emulator.sh start`, `tools/emulator.sh install` (builds, installs, sets a test profile), `tools/emulator.sh treadmill` in another terminal; Ctrl+C switches the machine off (it leaves the list after ~15 s). The emulator runs in Spanish. A real phone is still needed to check range, real machines and Garmin/Strava sign-in.

The Workout screen must show every metric without scrolling: `WorkoutActivity.fitToScreen` scales the big numbers down by one factor when they do not fit (the bike, with four extra metrics, is the tallest case). Check new metrics there.

UI checks with `adb shell uiautomator dump` fail on the Workout screen (it never goes idle while numbers update); use screenshots there.

`docs/workout.png` is the README picture: the Workout screen recording the virtual bike on the emulator, with the status bar in demo mode (`adb shell settings put global sysui_demo_allowed 1`, then `am broadcast -a com.android.systemui.demo -e command enter`), resized to 540 px wide. Refresh it when that screen changes visibly. Never take README pictures from a real phone: they would show personal data.

## Repository

`.gitignore` keeps out build output, `local.properties`, signing keys (`*.jks`, `*.keystore`), `.env` files, APKs and `.fit` files. Before committing, check that no token, cookie, password, e-mail or personal path has entered the sources or docs.

## Architecture

Packages under `app/src/main/java/com/sweatsync/`, each with one job:

| Package | Role |
|---|---|
| `ble` | Bluetooth only: `MachineScanner` (the app's single scanner for FTMS machines, used by the Home screen), `MachineGatt` (one connection), `FtmsParser`, models (`MachineData`, `LiveMetrics`, `MachineDevice`, `MachineType`). |
| `session` | Pure logic, no Android UI: `ActivitySession` (stopwatch with pauses, distance, calories, samples; driven by a `Clock`), `FitWriter`, `Calories`. Fully unit-tested. |
| `service` | `MachineService` (foreground service: keeps the connection and the session alive, publishes an immutable `MachineState`), `MachineClient` (lifecycle-aware binder used by screens). |
| `data` | `Prefs` (profile, last machine, per-platform sync) and `ActivityStore` (SQLite history; only on-screen values, never the `.fit`). |
| `upload` | `UploadTarget` / `UploadManager` / `UploadCenter`, `GarminUploader`, `StravaUploader` (both unofficial), and the shared `Http` toolbox. |
| `ui` | `MainActivity` (bottom navigation host) with `HomeFragment`, `HistoryFragment`, `SettingsFragment`; `WorkoutActivity`, `ActivityDetailActivity`, `PlatformActivity`, `OnboardingActivity`, `StravaLoginActivity`; adapters and small views (`PulseView` radar, `Blink` status dot, `StatGrid` with `item_stat_live` / `item_stat_summary`). |
| `util` | `Background` (shared thread pool), `Formats`, `Perms`, `Downloads`. |

Data flow: Bluetooth events -> `MachineService` -> `MachineState` -> screens through `MachineClient`. Screens send commands to the service (connect, start, pause, resume, finish); they never touch Bluetooth connections directly.

## Conventions

- Screens are `AppCompatActivity` / `Fragment` classes with XML layouts and Material 3 components. Always use standard components (switches, navigation bar, text fields, dialogs); never hand-draw toggles, bars or buttons. No Compose.
- UI strings go in `res/values/strings.xml` (English) and `res/values-es/strings.xml` (Spanish). No hard-coded user-facing text in Kotlin.
- Visual language: Strava's, with a salmon accent. White surfaces, centred bold toolbar titles with salmon back/action icons (Home instead shows the "SWEATSYNC" wordmark on the left in Barlow Black, `Widget.SweatSync.Toolbar.Brand`; on the Workout screen, back leaves directly before Start, and once an activity is under way it opens the same save/discard sheet as Finish), grey uppercase section bands (`Text.Section`), full-width hairlines, pill buttons (`Button.Action` filled salmon with white text, `Button.Outline`, `Button.Dark` for Resume), bottom bar with outlined grey icons and a filled salmon current tab. Live numbers use Barlow Condensed with an uppercase caption above and the unit below; summaries use a grey label above a bold value with its unit. Everything comes from `colors.xml` / `themes.xml`: use `accent` for fills and `accent_strong` for salmon text or icons on white. Fonts: Barlow / Barlow Condensed (OFL, in `res/font`).
- Numbers follow the phone's language through `Formats` (decimal comma in Spanish); never format user-visible numbers with `Locale.US`.
- Screens read `MachineState` through `MachineClient` and send commands to `MachineService`; state lives in the service and in `ActivitySession`, never in a screen.
- Lists use `RecyclerView` + `ListAdapter` + `DiffUtil`. Never rebuild list views on every update: a rebuilt row swallows taps.
- No raw `Thread`s: use `Background.io`. Keep pure logic out of Android classes so it can be unit-tested.
- New machine type: add it to `MachineType`, a parser in `FtmsParser` with tests, its icon/name in `MachineUi.kt`, and its stats in `WorkoutActivity.ensureStats`.
- New upload target: implement `UploadTarget` and register it in `UploadManager.targets`; reuse `Http`.
- Never store passwords. Only tokens or session cookies, in the app's private SharedPreferences.
- Never commit `local.properties` or any secret.

## Gotchas

- Garmin and Strava uploads rely on undocumented endpoints and can break at any time. Failures must degrade gracefully: the `.fit` is always saved to Downloads first.
- The Strava web upload flow (`StravaUploader`) was verified on a real account. It finishes when the progress response reports `workflow: success` or a nested `activity.id`.
- Many machines allow only one BLE connection; another connected app makes the scan or connect fail.
- Android silently blocks an app's BLE scans for 30 s after more than 5 starts in 30 s. `MachineScanner` is a single app-wide instance that keeps a running scan on a new search, lingers a few seconds after `stop`, and postpones starts that would exceed the limit. Do not create other scanners or call `startScan` elsewhere.
- Before Android 12, BLE scans return nothing while location services are off (`Problem.LOCATION_OFF`).
- If a machine reports no distance counter, the session integrates speed to estimate it. Only the part of the machine's counter after Start counts.
- `FtmsParser` flag handling is easy to get wrong: in FTMS, bit 0 means "more data", so speed is present when bit 0 is **0**. Always add a test with hand-built bytes when touching it.
- FIT treadmill sessions use sport 11 (walking) / sub-sport 1; bikes use sport 2 / sub-sport 6.
