# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Gumshoos is an Android launcher-search overlay: Nova Launcher's home-screen search, usable with any
launcher, aimed at Raycast-level polish. Personal app, not a Play Store release — so special
permissions and a `specialUse` foreground service are acceptable where Play review would object.

**The overriding requirement is that the search window appears in near-instant time.** Almost every
architectural decision below follows from it.

## Commands

```bash
./gradlew assembleDebug                 # APK -> app/build/outputs/apk/debug/app-debug.apk
./gradlew lintDebug                     # see warning below
./gradlew testDebugUnitTest             # JVM unit tests
./gradlew installDebug                  # requires a connected device

# a single class, or a single method
./gradlew testDebugUnitTest --tests "com.vynex.gumshoos.AppMatcherTest"
./gradlew testDebugUnitTest --tests "com.vynex.gumshoos.answers.MathAnswerProviderTest.adds"

# provider brand icons: design/icons/*.svg -> res/drawable/*.xml
./design/convert-icons.sh
python3 design/verify-icons.py           # needs cairosvg, numpy, pillow
```

**`assembleDebug` does not run lint.** Two `NewApi` crashes have shipped because of this. Always run
`assembleDebug lintDebug testDebugUnitTest` together before claiming a change is good.

`minSdk 31`, `targetSdk 36`, Java 11. **No third-party Gradle dependencies** — five instant-answer
modules and an HTTP client have been built without adding one, using `HttpURLConnection`, `org.json`
and a hand-written back stack instead. Treat adding one as a decision needing justification.

## The latency architecture

`OverlayService` holds a `ComposeView` **pre-attached to `WindowManager`** and merely flips its
visibility. Showing search is a visibility flip plus one frame (~20ms measured), not an activity
launch. The service runs in the foreground purely to keep the process — and that warm view —
resident.

**Never put work on the show path.** Everything expensive happens at warm time: the app index and
its icons, contacts, usage stats, exchange rates, provider availability. `EventLog.record` writes to
disk on a background thread for the same reason. If a feature needs data, load it at warm and read
it from memory.

Invoke paths, fastest first:
- **Widget** (`SearchWidgetProvider`) — a `PendingIntent` straight into the running service. The only
  home-screen target that can reach a service; shortcuts cannot.
- **QS tile**, **pinned shortcut / second drawer icon** — the latter go through
  `LaunchSearchActivity`, a translucent trampoline, because a shortcut can only launch an activity.
- **Launcher icon** — opens settings, deliberately not search.

`OverlayService` hosts an `AbstractComposeView` subclass (`ComposeView` is final) so it can route
`KEYCODE_BACK` into its own `OnBackPressedDispatcher` and observe focus loss. A service-hosted
window gets none of an Activity's plumbing for free: all four ViewTree owners are set by hand.

State resets when the card is **fully hidden**, via a `resetToken` the service bumps after the exit
animation. Resetting at either end of an animation is visible as a stutter.

## Matching

`AppMatcher` is pure logic and the most heavily tested code here. It indexes a label once into an
`Indexed` — folded text, word initials, camel-case capitals, and **index maps back to the original
string**. Matching is accent-insensitive (`poke` finds `Pokémon`), and the index maps are what let
the UI embolden exactly the characters the winning tier matched on. One source character can fold to
several (`ß` → `ss`), so highlights drift without them.

Tiers, strongest first: exact label, exact word initials, exact capitals, label prefix, initials
prefix, word-boundary substring, substring, fuzzy subsequence. Recency breaks ties.

## Instant answers (`answers/`)

`AnswerProvider.answer()` returns `null` unless confident. **This is the whole design** — the failure
mode is not a wrong answer but hijacking an app search. A bare number is never an answer, which is
what keeps `2048` a game. Tests assert the negatives as carefully as the positives.

`AnswerContext` carries the device's locale, measurement system, currency, zone and clock **as data,
passed in**. Providers never read `Locale.getDefault()` or the system clock themselves. This is what
makes the locale defaults testable — rules like "an Indian device answers `5ft` in metres" could
otherwise only be verified on a device set to that locale.

It also keeps providers on plain JVM types. **`android.icu` is unavailable to unit tests**, which is
why `MeasurementSystem` is a local enum rather than ICU's, and why `AnswerContextFactory` — the only
Android-dependent piece — lives in its own file.

## Configuration and diagnostics

`GumshoosConfig` is a singleton of `mutableStateOf` values backed by SharedPreferences, loaded in
`GumshoosApp.onCreate` because the activity, the service and the widget provider can each be first to
run in the process. Keys are stable and independent of labels, so settings survive renames.

`EventLog` and `CrashLog` write to files that survive process death and surface in Settings →
Advanced. Both exist because silent failures have repeatedly been indistinguishable from working
code — `runCatching { startActivity(...) }` around a refused intent looks exactly like success.
Prefer recording an outcome over swallowing it.

## Testing reality

Unit tests cover the pure logic: `AppMatcher`, and every answer provider against a pinned
`AnswerContext`. That is where new logic should go.

Not unit-testable, and verified on-device instead: anything holding an `ImageBitmap` (`AppEntry`,
`ContactEntry`), the overlay window itself, and the settings UI.

The developer does not usually connect ADB — builds are sideloaded and tested by hand. Compensate by
running lint, unit-testing pure logic, and **stating plainly which claims are unverified**. For
animation problems, ask for a slowed screen recording; frames can be measured with `ffmpeg` and the
current timings were derived that way.

## Icon pipeline

`design/convert-icons.sh` runs svgo, then `design/prepare-svg.py`, then svg2vectordrawable. The
middle stage is not optional — the converter silently drops `gradientTransform`, reads CSS colour
names as black, and leaves a fill-less path undrawn where SVG would paint it black. All three
failures produce a successful build and a wrong icon. `verify-icons.py` renders the generated
drawable back to SVG and compares it against the source, which is the only check that exercises the
converter rather than the preprocessing.
