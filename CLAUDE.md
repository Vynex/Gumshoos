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
./gradlew assembleRelease               # signed + R8, see below

# a single class, or a single method
./gradlew testDebugUnitTest --tests "com.vynex.gumshoos.AppMatcherTest"
./gradlew testDebugUnitTest --tests "com.vynex.gumshoos.answers.MathAnswerProviderTest.adds"

# provider brand icons: design/icons/*.svg -> res/drawable/*.xml
./design/convert-icons.sh
python3 design/verify-icons.py           # needs cairosvg, numpy, pillow
```

**`assembleDebug` does not run lint.** Two `NewApi` crashes have shipped because of this. Always run
`assembleDebug lintDebug testDebugUnitTest` together before claiming a change is good.

**Renaming a resource *directory* needs a `clean`.** Incremental resource merging does not notice it:
renaming `mipmap-anydpi-v26` to `mipmap-anydpi` while also editing the file inside left the adaptive
icon out of the APK entirely, with a green build and no warning — every density silently fell back to
the legacy `ic_launcher.png`. Caught only by listing the APK's contents. A build succeeding says
nothing about a renamed resource folder having been picked up.

**Release builds are signed and minified.** `assembleRelease` emits `app-release.apk` — if it ever
emits `app-release-*unsigned*.apk`, signing silently degraded and the build printed a warning saying
so at configure time. `lintVitalRelease` runs as part of it, so the `NewApi` class of crash is
covered on that path too.

Signing reads `app/keystore.properties`, which is **gitignored**; the keystore itself lives outside
the working tree, at `~/keystores/gumshoos-release.jks`. `keystore.properties.template` is committed
and documents both, including the `keytool` invocation. A properties file rather than environment
variables because Android Studio's Build menu does not inherit a shell's exports, so GUI release
builds would fail in a way that looks like broken config. If the file or the keystore is missing the
build **degrades to unsigned rather than failing**, which keeps a fresh clone buildable — the
configure-time warning is what stops that being silent, since an unsigned APK looks identical until
install refuses it.

`versionCode`/`versionName` are handled by the user.

Debug builds carry `applicationIdSuffix = ".debug"` so a release build can sit on the phone beside a
test one. This is safe because the manifest's `.SearchShortcut` and `.AssistTrigger` aliases expand
against `namespace`, not `applicationId`, and both are addressed as
`ComponentName(context.packageName, <absolute class name>)`. Nothing else hardcodes the app's own id
— check that before adding anything that does.

The id suffix never touches a **label**, so the two builds were indistinguishable everywhere a name
is drawn. `app/src/debug/res/values/strings.xml` overrides the five strings the system shows as names
— app, drawer shortcut, assistant entry, and the two widget picker entries — with a `(Debug)` suffix.
A debug source set rather than `resValue` or a manifest placeholder, so the real names stay written
once in `main` and stay readable to `getString`, which `SearchTileService` and `SearchShortcuts` both
use. The cost is that renaming one in `main` needs mirroring there. Descriptions and in-app prose are
deliberately left alone: the picker entry above a description already carries the suffix, and "Offer
Gumshoos (Debug) as an assistant" reads as a typo rather than as information.

`minSdk 31`, `targetSdk 36`, Java 11. **One third-party Gradle dependency in the APK**, plus one that
never reaches it — five instant-answer modules, two web clients and a hand-written back stack were
built without any, using `HttpURLConnection` and `org.json`. Treat adding another as a decision
needing justification.

The test-only one is **`org.json:json`**, and the reason is worth knowing before deleting it as
redundant: `org.json` ships *in the Android framework*, so on a JVM unit test it resolves to the
stubbed `android.jar` where every method throws `Stub!` — and a parser that catches its own
exceptions, which both of ours must, turns that into an empty result rather than a failure. So the
tests pass while proving nothing. That is exactly how `CurrencyRates.parse` went untested for its
whole life. The dependency puts a real implementation of the *same* API under the test, so what runs
is the shipping code path rather than a reimplementation; the alternative was hand-rolling a JSON
reader, string escapes included. Verified absent from the release APK — its dex references only
`JSONArray` and `JSONObject`, the framework classes, with none of the implementation's other types.
This is the same class of problem as `android.icu` being unavailable to unit tests.

The one is `sh.calvin.reorderable`, for drag-to-reorder on the Providers and widget Actions lists.
The case for it, since the same reasoning should be demanded of the next one: it covers the
**non-lazy** `ReorderableColumn` these lists actually are, and the parts it brings — auto-scroll at
the screen edge, interrupted drags, variable-height rows — are the whole difficulty rather than the
part that was ever going to be written well by hand. It is published as Compose Multiplatform, so it
adds ~24 `org.jetbrains.compose.*` alias modules to the graph; **those redirect to the androidx
artifacts the BOM already pins and move no version of anything**, which was checked by resolving
`debugRuntimeClasspath` with and without it rather than assumed. It compiles against Compose ~1.7
and runs against 1.11.4, which is the one thing the graph cannot vouch for. Apache-2.0 and small
enough to vendor if it is ever abandoned.

Compose comes from the BOM, currently `2026.06.01` — **Material3 1.4.0**, the first stable release of
the Expressive components, and Compose UI 1.11.4. Two things to know before touching it:

- Material3 1.4 **stopped pulling in `material-icons-core` transitively**, so it is named explicitly
  in the catalog. Eight distinct stock icons are left — `ArrowBack`, `KeyboardArrowRight`,
  `KeyboardArrowDown`, `Search`, `Close`, `Check`, `CheckCircle`, `Warning` — and the BOM still pins
  the artifact, so it can't drift on its own. The library is frozen at 1.7.8 and no longer developed;
  if it is ever dropped from the BOM, hand-author the eight rather than chasing a version. `Refresh`
  was a ninth until every reset and rebuild action moved to the Lucide `ic_provider_rotate_ccw`, and
  that is the direction to keep going in — the pipeline below makes a drawable cheaper than a
  dependency.
- **What 1.4.0 exposes is uneven, and the only way to know is to read it.** Not usable: the theme
  entry point `MaterialExpressiveTheme`, every flexible app bar, and the subtitle-bearing
  `TopAppBar` are all `internal`; the brand-new components — `ButtonGroup`, `LoadingIndicator`,
  `FloatingToolbar`, `SplitButton`, wavy progress — have no source file at all, only design tokens.
  But the **existing** components did take the Expressive redraw where their tokens moved:
  `Slider` is now a 4dp x 44dp bar thumb with a track gap and stop indicators, not the classic 20dp
  circle. So there is no opt-in switch to throw, and no single sentence covering the release.
- Both halves of that were got wrong here first, in the same session. The docs were read and the
  release assumed to match them; then the *file list* was read and "Slider.kt exists" taken to mean
  the slider was unchanged. **A file name is not an implementation.** Open the sources jar and read
  the tokens before saying what anything looks like.

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

### Dismissal, and why the navigation gestures need their own path

An overlay window floats above app windows, so nothing removes it when something else comes forward.
Losing window focus covers most of that — a notification tap, the shade — and is switchable in
settings.

**It does not cover the navigation gestures, because on gesture navigation focus never moves.**
Measured on Android 16: the window keeps focus through the whole swipe, keeps it while Recents
animates in, and still holds it with Recents settled in front of it. Swiping Home is the same. In a
four-minute capture, focus was lost exactly once — when the screen turned off. So the card sat on
top of a screen it no longer belonged to until it was dismissed by hand. An earlier comment in the
source described this as a signal arriving *late*; it does not arrive.

The one thing the system tells us is `ACTION_CLOSE_SYSTEM_DIALOGS`, whose `reason` extra reads
`recentapps` for the swipe-up gesture **whether it ends at Recents or at Home**. Android 12
restricted *sending* it, not receiving. Measured from the finger landing: **256ms** for a flick home,
**631ms** for a swipe-and-hold into Recents — it tracks the transition, not the touch, so a longer
gesture reports later. That is visibly late, and it is the floor.

**Everything else was measured and is worse. Do not re-derive these** — each cost a build, a
sideload and a capture, and the list exists so the next idea can start from the end of it:

| route | result |
|---|---|
| window focus, insets, touch, `onTrimMemory`, config change | nothing arrives during the gesture at all |
| `AccessibilityService` (`notificationTimeout=0`) | first event **45–53ms *later*** than the broadcast |
| immersive / `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` | accepted and silently ignored; insets never change |
| `setSystemGestureExclusionRects` | back gesture only; the bottom is `mandatorySystemGestureInsets` |
| an overlay window covering the gesture strip | receives taps, **never a swipe** |

The last one is the one to know, because it is what everyone suggests — including a Reddit thread
that appears to describe exactly this working. It doesn't. A strip window gets `DOWN` and `UP`
**1–2ms apart**, which no finger can do: the events are buffered and replayed *after* the system has
decided the touch was not a gesture. So such a window is only ever told about touches already ruled
out, which means the gesture can be neither observed early **nor blocked**, at any size, with any
flags. An app that appears to block the home swipe is re-asserting itself afterwards, not preventing
it. The cost of trying is real, too: a strip that takes taps closes the window whenever anything near
the bottom edge is touched, keyboard included.

The exit stays **animated** on this path. Skipping it — the system is already animating, so ours is
the only thing still moving — is sound in the abstract and wrong on the phone: cutting the card dead
reads as a crash, where the animation reads as getting out of the way. It was tried both ways. Watch
it before changing it.

State resets when the card is **fully hidden**, via a `resetToken` the service bumps after the exit
animation. Resetting at either end of an animation is visible as a stutter.

`resetToken` bumps *after* an exit, which makes it the wrong key for anything a setting feeds. A
value remembered against it is the one read at the end of the previous close, so a setting changed
while the window was shut did not apply until the open after next. Cache against it only what is
genuinely expensive to read — the system animator scale, a ContentResolver query — and read
`GumshoosConfig`'s own snapshot values live, which costs nothing and is always current.

The card's geometry is no longer measured constants: rows, icon size and label size are settings, and
cell/grid/card heights derive from them. `overlaySettledHeight(rows, icon, label)` is a pure function
for that reason — the settings preview asks it how tall the tallest card would be without mutating
anything, and `OverlayGeometryTest` pins the shipped defaults to the measured 84dp cell and 302dp
card. It has drifted once already, by 0.08dp, from a line-height ratio that looked harmless.

**Compose animation values settle one frame after their target changes**, `snap()` included. That is
invisible for something that only moves as the window opens, and very visible for something that
moves while it is on screen: changing the row count left the grid a frame at its old height under a
header that was already correct. So when `animate` is false, read targets **directly** rather than
through the `Transition` or `animateDpAsState` — see the `sectionHeight` helper. This matters in the
real window too, for anyone who has animations off system-wide.

**`LaunchedEffect` is a frame of lag for the same reason**, and it is the bigger source of it. The
query lives in `OverlayContent`, and the tab row, grid, answer and contacts heights are all *derived*
from it in that same composition. They used to live in `SearchPanel`, which reported each one back up
through a `LaunchedEffect` — so the panel drew its new contents a frame before the card knew how tall
to be. Two lags stacked (effect, then animation settle) and typing took three frames to land: apps
filtered under a tab row still standing, then contacts arriving under it, then finally correct.
Erasing took four. Animation hid all of it. **State that layout depends on must be derived, never
reported upward from a child.** Anything seeded for an animation has the same problem — initialise it
at its settled value when animations are off rather than snapping it in an effect, as the context
menu's `appear` does.

## Web suggestions

Live query completions from Google or DuckDuckGo, in a section between Contacts and "Search using".
**Off by default and staying that way** — switching it on sends what is typed here to a search
engine, which is a decision only the person typing can make, and the settings copy says so plainly
rather than burying it.

This is the **only network call on an interactive path** in the app; the exchange-rate fetch is
deliberately at warm time. Four things keep it off the show path, and all four are load-bearing:
nothing runs until the window is already open, nothing is sent below
`WebSuggestions.MIN_QUERY_LENGTH` (5) characters, `DEBOUNCE_MILLIS` (400) outlasts a gap between
keystrokes so one burst of typing sends one request, and the section does not exist until an answer
arrives.

- **One parser serves both engines, and that was measured rather than assumed.** Google with
  `client=firefox` and DuckDuckGo with `type=list` both answer `["query", ["a", "b", …]]`; Google
  appends two more elements, which is why `parse` reads element 1 rather than expecting a length.
  Drop either parameter and the shape changes — Google returns XML, DuckDuckGo returns
  `[{"phrase":…}]`. Both are asserted, because a wrong parameter is silent.
- **Neither endpoint is documented or promised to us**, so every malformed and unexpected shape has
  to come out as an empty list rather than an exception. The negative tests are the point of the
  suite; the fixtures are real captured replies rather than hand-written, or they would agree with
  the belief instead of testing it.
- **Rows are held until replaced, never cleared because the query changed.** The first
  implementation matched the rows against the live query and dropped them the moment it moved, on
  the theory that a completion one keystroke behind is a lie. On a phone that reads as a fault:
  typing on past a first set of suggestions folds the section away and unfolds it a moment later,
  so every pause in typing costs two layout animations. Holding them makes the common case — three
  rows becoming three other rows — a text swap at an unchanged height, with no animation at all.
  The trade is the right way round: a completion for a *prefix* of what is typed is still a
  completion, and tapping a row searches for the text on it rather than for the query, so nothing
  it offers is untrue. A reply for an abandoned query cannot overwrite a newer one — the effect is
  cancelled when the query changes and cancellation is checked on the way back to the main thread,
  so a slow request that already left simply never publishes. `clear()` is therefore only for the
  cases where no query these rows could belong to exists at all: window closed, feature switched
  off, engine changed, or the query fell back under the character floor.
- **A failed fetch leaves the previous rows alone; an empty successful one replaces them.** A
  dropped connection is not the engine saying there is nothing.
- **Tapping searches with the engine that suggested it**, falling back to the first enabled provider.
  Handing a Google completion to whatever happens to sit first in "Search using" would answer a
  different question from the one the row appeared to ask.

The section's height derives in `OverlayContent` like every other, and is threaded into
`SearchPanel` rather than reported up from it — see the rule about `LaunchedEffect` lag. The
network is the one thing that genuinely cannot be derived, which is why the effect writes to state
that the height is then derived *from*, in the same composition.

**The incognito keyboard needs an interceptor, because Compose has no route to `EditorInfo`.**
`IME_FLAG_NO_PERSONALIZED_LEARNING` is the platform's only mechanism for asking a keyboard not to
learn from what is typed, and none of `KeyboardOptions`, `ImeOptions` or `PlatformImeOptions` can
carry it — the last holds a `privateImeOptions` *string* and nothing else, and
`TextInputServiceAndroid.update` is what actually writes `EditorInfo.imeOptions`. That came out of
the 1.11.4 artifact with `javap`; the documentation describes neither the gap nor the way round it.

The way round it is `InterceptPlatformTextInput` with a `PlatformTextInputInterceptor`, still
`@ExperimentalComposeUiApi` in 1.11.4 despite what several write-ups say. Three things about
`IncognitoKeyboard` in `OverlayContent`:

- **The flag is OR-ed in *after* delegating to `createInputConnection`.** That call is where Compose
  fills the `EditorInfo`, so a flag set first is overwritten and the feature silently does nothing —
  which is indistinguishable from a keyboard that ignored it.
- **It wraps the header, not the field.** The interceptor is a composition local and its content
  lambda is not a `RowScope`, so wrapping `BasicTextField` alone would cost it its `weight()`. The
  header holds the window's only text field, so the reach is identical.
- **Nothing is recorded when it applies.** Normally this app prefers recording an outcome to
  swallowing it, but the interceptor runs when the keyboard opens, which is the show path, and there
  is no outcome to record anyway: Gboard and SwiftKey honour the flag, others ignore it, and no API
  reports which happened. The setting's copy says so rather than the log.

Related, and settled by research rather than code: **a "private browsing" toggle for the web
providers is not implementable.** Chromium deliberately removed third-party incognito-with-a-URL —
the only public entry point, `org.chromium.chrome.browser.incognito.OPEN_PRIVATE_TAB`, cannot carry
a URL at all — and Fenix dropped Fennec's `private_browsing` extra with no replacement. Anything
built on an ignored extra opens a *normal* tab while `startActivity` reports success, so the failure
is a broken privacy promise that looks exactly like success. Don't rebuild it.

## Matching

`AppMatcher` is pure logic and the most heavily tested code here. It indexes a label once into an
`Indexed` — folded text, word initials, camel-case capitals, and **index maps back to the original
string**. Matching is accent-insensitive (`poke` finds `Pokémon`), and the index maps are what let
the UI embolden exactly the characters the winning tier matched on. One source character can fold to
several (`ß` → `ss`), so highlights drift without them.

Tiers, strongest first: exact label, exact word initials, exact capitals, label prefix, initials
prefix, word-boundary substring, substring, fuzzy subsequence. Recency breaks ties.

Tiers 2–3 and 5–8 can be switched off, and an app can carry aliases (custom names, comma separated).
Two rules there are load-bearing and were both got wrong first:

- A disabled tier is **skipped**, not scored to zero. Zeroing keeps the match at that tier and still
  returns it ahead of a weaker tier that is enabled.
- An alias wins only on a strictly **stronger tier**, never on score. Score carries the brevity
  bonus and an alias is nearly always shorter, so comparing on it let `insta` beat Instagram at the
  same tier and cost the cell its highlight. When an alias does win the row draws unhighlighted —
  the label holds none of the matched characters, so emboldening any would be a lie.

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

## Widgets

Two providers share `SearchWidgetProviderBase`: the wide search bar and the app icon widget.
`RemoteViews` is not Compose and its constraints are unforgiving. Each of these cost a shipped bug:

- **Only classes annotated `@RemoteViews.RemoteView` inflate.** `View` and `Space` are *not* among
  them, despite both looking like obvious spacers. A rejected class fails the whole layout in the
  *launcher's* process — the user sees "An error occurred when loading widget", nothing reaches our
  logcat, and no `runCatching` of ours can see it. `WidgetLayoutTest` guards the permitted set, and
  `res/layout` is a declared test input in `build.gradle.kts` so the check isn't cached away on
  exactly the change it exists to catch.
- **Resource ids are assigned alphabetically and shift when you add one.** The launcher caches
  RemoteViews and records actions by *numeric* id, so adding an id to a widget layout silently
  breaks widgets already on the home screen while a freshly added one works. `WidgetRefreshReceiver`
  re-pushes everything on `MY_PACKAGE_REPLACED` — one of the few broadcasts still allowed a manifest
  receiver. It must not warm the service: that would be a background FGS start.
- **Icons go in as bitmaps, never as resource ids.** `R.drawable` is a *separate* id space from
  `R.id`, so adding a drawable leaves view ids alone but renumbers every drawable sorting after it.
  `setImageViewResource` hands the launcher a bare integer, and the launcher re-resolves that integer
  against *its own* cached copy of our resources — stale after an update, so the id that meant Google
  lands on the magnifier, Lens on Spotify, Voice on nothing. Re-pushing correct ids does **not** heal
  it: the resolution happens on their side, which is why "refresh widgets" appeared to do nothing.
  `rasterizeIcon` draws the drawable into a bitmap that travels inside the RemoteViews, where there
  is no id left to go stale. Fit each drawable's own aspect ratio centred in the square rather than
  forcing the bounds — Voice Search is a 300×429 mark and stretches wide otherwise.
- **No elevation, no `setBackgroundDrawable`, no shadow.** The bar's background is therefore a
  bitmap drawn on a *software* canvas, since `Paint.setShadowLayer` is the only remotable route to a
  drop shadow. Skia derives that shadow from the alpha actually drawn, so a transparent paint casts
  nothing — the shape goes down opaque and its own area is then **clipped out**, leaving the part of
  the blur that fell outside it. Erasing it instead, with a `CLEAR` xfermode, is what shipped first
  and it fringes: both passes are antialiased, so a pixel holding `c` of black loses only `c` of
  itself and keeps `c(1-c)`, a ring of speckle peaking at half coverage that reads as a jagged edge
  wherever nothing is painted over it. An antialiased clip applies the same coverage on the way in
  and leaves nothing to subtract. **Any draw-then-erase pair has this bug**; reach for a clip.
- **A drop shadow needs room reserved for it, and the reserve belongs in the geometry.** The blur
  ran into whatever margin was there and was sliced off square by the bitmap's edge where there
  wasn't enough — at a zero margin, most of it. `WidgetBarMetrics.insetHDp`/`insetVDp` floor the
  configured margin at three sigma plus the offset, symmetric per axis so the bar stays centred, and
  the floor is a `max` rather than a sum so a margin already wider than the shadow decides the size
  by itself. Everything drawing inside the bar — the widget's content padding, the settings preview
  — must read the bar's edge through those, never off `paddingHDp`, or the logo sits outside it.
- **A border in the fill colour is not invisible.** The filled bar drew one for a long time on that
  assumption; the stroke is painted *over* the fill, so the two antialiased coverages composite
  along the whole rounded edge and every opacity below 100% shows the seam as a darker rim. Checked
  at full opacity, where it very nearly is invisible. Only outline mode has a border now.
- **Updates are per widget id.** The bar is resizable, so two placed copies differ in width and one
  shared bitmap stretched across both gives visibly elliptical corners.

Action icons go through `WidgetActionActivity` because a PendingIntent holds exactly one Intent and
these destinations need a fallback chain. Request codes are written out per action, never derived
from `ordinal`: PendingIntent equality ignores extras, so an ordinal would reshuffle live intents the
day someone reorders the enum.

### Launching Google's own features

**A successful `startActivity` proves nothing.** Several Google components are exported, resolve
cleanly, start, return success — and show nothing. At the head of a fallback chain each one hides
every working rung behind it. This cost four iterations on Lens and three on voice search, every one
of them an attempt to reason the right component out from its name.

Order chains by **what cannot fail silently** — a launcher intent is the app's own MAIN/LAUNCHER
activity and cannot start-and-vanish, so it leads; deeplinks and exported trampolines go last.

Two instruments established that and **have since been deleted**: a probe that listed what each
package declares with exported flags and where each candidate intent really resolves, and an *entry
point tester* that fired one candidate at a time to be watched — because the probe cannot answer the
question that actually matters, *did anything appear*. Both lived in Settings → Advanced and went in
the pass that cut that screen back to what ships.

**Their findings survive as `WidgetActionTargets.candidates`.** That list deliberately keeps every
rung already ruled out by watching a screen, not only the ones that work, and `chain()` filters it by
`inChain`. It is the record, and it is why a chain must never be re-derived from class names: doing
that is what cost four iterations on Lens in the first place. If a chain genuinely needs changing,
rebuild an instrument — git history has both — rather than reasoning from a component name.

Two things about diagnosing this cost a round trip each, and both generalise:

- **A rung's number is not its name.** The chain is built with `listOfNotNull`, so a rung the device
  lacks drops out and every rung behind it renumbers. A trace reading `rung1` was read here as the
  launcher intent when on that phone it was the deeplink two places down, and the wrong conclusion
  followed from it. Rungs are logged by action, data and component now. Anything logged by position
  is only meaningful beside the source of the build that wrote it.
- **Diagnose on the phone that has the bug.** "I can't reproduce it" was the most informative fact in
  the report and was nearly discarded as noise: this phone had the standalone Lens app and theirs did
  not. Get evidence off *their* device before theorising. Note also that reproducing it here by
  uninstalling was not quite the same state — a preinstalled app that is "uninstalled" stays present
  with its launcher activity disabled, which the probe reported as `1 of 2 declared match / internal
  LensLauncherActivity` rather than as absent. With the probe gone, an install/uninstall difference
  like that is no longer visible from inside the app at all, which is the cost of having removed it.

That sitting closed Lens: on a phone without the standalone `com.google.ar.lens` app, **every** entry
point the Google app exposes starts, returns success and draws nothing — both `MainActivity` forms,
both `LensExportedActivity` forms, both deeplinks, the share and image ones — except
`DirectLensYoutubeActivity`, which opens Lens onto YouTube rather than the camera. So Lens has no
fallback: `WidgetAction.requiredPackage` names the app it cannot work without, `isAvailable` asks for
a *launch intent* rather than mere installation (uninstalling a preinstalled Lens leaves the package
present with its launcher activity disabled), and the widget skips an unavailable action at render
time without touching its saved state. The settings screen moves it to an **Uninstalled** group with
a Play Store link instead of a checkbox that could not do anything.

**Anything the widget draws from a device fact needs three ways to converge, not one.** A placed
widget keeps the RemoteViews it was last handed, so an install or uninstall has to push new ones, and
the first attempt pushed them only from `OverlayService`'s package callback — which is dead whenever
the service is, so installing Lens left the bar without its icon until an unrelated setting was
toggled. The service callback is the fast path; `MainActivity.onResume` re-pushes unconditionally as
the backstop, because opening the app is when someone is looking to fix exactly this; and
`WidgetRefreshReceiver` also takes `PACKAGE_FULLY_REMOVED`, which is on the implicit-broadcast
exception list where `PACKAGE_ADDED` is not, so the uninstall direction works with nothing of ours
running. Settings reads availability through `rememberOnResume` for the same reason a permission does
— the Play Store is somewhere we send the user and get no result back from, and a plain read taken
during composition leaves the Uninstalled group surviving the install made from it.

## Settings UI

Screens are a hand-rolled back stack in `SettingsHost`; adding one touches three places (the `Screen`
sealed interface, the `when`, and a `CategoryRow`). A `Screen` may carry arguments — `ManageApps` is
a `data class` holding the component key to highlight — so the `when` matches it with `is`.

The home list is Permissions, Launching, Appearance, Manage apps, Search and Behaviour, with Advanced
in a card of its own below them; every row carries a leading icon. **Search is a hub**, not a screen
of controls: Matching, Instant answers, Contacts and Providers hang off it. Manage apps is the merge
of what were Custom names and Hidden apps.

**Launching is a top-level destination, not a corner of Advanced**, which is where it began. The
launcher icon deliberately opens settings rather than search, so *how do I bring this up* is the
question a fresh install arrives with — and the answer was three levels down, under Advanced →
Launching Gumshoos. It holds the drawer icon, the assistant gesture, and an **Add to home screen**
group that pins the search shortcut or either widget.

Pinning goes through `SearchShortcuts`, and two things about it generalise:

- **Shortcut and widget pinning are separate capabilities.** `isRequestPinShortcutSupported` and
  `isRequestPinAppWidgetSupported` are asked separately because a launcher can support one and refuse
  the other; a single combined check greys out a control that would have worked.
- **A pin request's outcome is surfaced, never assumed.** Both calls return a boolean, both record to
  `EventLog`, and a refusal raises a warning. A refused pin and an accepted one look identical from
  the settings screen, and the innocent reading — that it landed on a home-screen page nobody is
  looking at — is exactly what a silent failure hides behind. Acceptance is still all we can see:
  where it lands, and whether the launcher then drops it, is the launcher's business.

The drawer icon and the pinned shortcut are **independent**, and were nearly coupled on the
assumption that one *was* the other. The pin builds an intent straight at `LaunchSearchActivity`,
which is enabled by default; the drawer switch toggles the separate `.SearchShortcut` activity-alias.
A pinned icon therefore works with the drawer icon off, and disabling one alongside the other would
have been a dead control justified by a fact that isn't true.

**Advanced holds only what a user has any business touching**: Opening apps, Maintenance, then the
two window settings, then the crash card and (in debug only) the event trace. What was removed is
worth recording so it isn't rebuilt by accident — the entry-point probes and tester (above), *Compare
launch paths* (a deliberately slow show, for timing against the widget), and *Start & pre-warm*,
which the boot receiver and placing a widget both do already, making a manual button a recovery
action for a state that recovers itself.

`LaunchMethod` is down to `STANDARD` and `LAUNCHER_API`. `NO_TASK_RESET` and `NO_ANIMATION` were
levers on the widget-relaunch problem, which was settled launcher-side, and an unreachable enum
branch is worse than a deleted one. Dropping entries is safe because load falls back to `STANDARD` on
an unknown saved id. What remains is a real choice rather than a diagnostic: `LAUNCHER_API` is the
only path that opens a work-profile app correctly.

Every screen is on one M3 vocabulary. **Grouping is a container, not a heading and a line** — the
accent-heading-over-loose-rows idiom the screens started from is a Material 2 / preference-screen
layout, and nothing uses it now. `SettingsSection`, `SettingsSubSection`, `SettingsCard` and
`fullBleedRow` were all deleted once the last screen stopped needing them. `SettingsDivider` survives
only where a line is a **real separator** rather than a grouping device: in `SearchablePicker`,
between the "follow device" row and the several hundred alternatives, and on Advanced between its
sections — the one screen whose groups are genuinely unrelated to each other rather than facets of a
single thing. Nowhere else has lines between cards, and that difference is the point.

**First-run setup and the Permissions screen are two composables, not one.** They were the same one,
which is why opening Permissions from the root list arrived at a screen headed "Set up Gumshoos"
with a Done button on it. `PermissionsScreen` is an ordinary subscreen — shared bar, back arrow,
nothing to finish, because it is a list of states rather than a sequence of steps. `SetupScreen`
keeps its own heading and no app bar on purpose: it runs before there is a back stack to belong to.
Both render the same rows from `PermissionGroups`, so the list cannot drift between them, and
`rememberPermissionState` holds the one piece they genuinely share — what is granted, and how to
ask.

Shared pieces live in `SettingsComponents.kt`:

- `SettingsTopBar` — the **one** app bar, on every screen below the root: small, pinned, back arrow.
  `SettingsScaffold`, `SettingsScaffoldWithHeader`, Manage apps, Hidden contacts and
  `SearchablePicker` all take it, so no subscreen can drift from another. Large bars belong to
  top-level destinations; everything here is a subscreen.
- `SettingsRootScaffold` — the root has **no app bar**, just a `displaySmall` heading that scrolls
  with the list. The root differing from the screens under it reads as a hierarchy; the subscreens
  differing from *each other* was the actual defect.

`LargeTopAppBar` was tried across the settings screens and reverted the same day. Three separate
things were wrong with it and none showed up until it was on a phone:

- It is a **two-row** component. The top row is a fixed 64dp for the navigation icon and actions, and
  the large title lives in the row beneath. A screen with neither — the root — shows 64dp of nothing
  above its title. That is the component's structure, not spacing to tune.
- `exitUntilCollapsed` reads the **gesture, not the content**, so on a screen that already fits it
  lets the title be dragged away and leaves a gap under it. `TopAppBarScrollBehavior`'s `canScroll`
  hook stops that, but only by trading it for a bar that never collapses — and three of these screens
  never fill a window, so a large bar there is 152dp that never earns itself back.
- A collapsing bar makes the app bar behave **differently screen to screen** for a reason the user
  cannot see, which is a sharper version of the inconsistency the port exists to remove.

The M3 spec has since replaced the fixed sizes with **flexible** app bars, which would suit the root
exactly — a large title, a subtitle, no wasted leading row. They are not usable: in Material3 1.4.0,
the current stable, `LargeFlexibleTopAppBar`, `MediumFlexibleTopAppBar` and the subtitle-bearing
`TopAppBar` are all declared **`internal`**. They exist in the binary and are not callable; only
`1.5.0-alpha` exposes them. `LargeTopAppBar` itself is *not* deprecated in 1.4.0 — the `@Deprecated`
on it is `HIDDEN`-level, on an old overload kept for binary compatibility. All of that came out of
the sources jar, which is the only place it is written down.
- `SettingsCardShape` / `settingsCardColour`, shared so cards don't drift apart.
  The colour is `surfaceContainer` — a **tonal role, never a translucent `surfaceVariant`**. A
  half-alpha colour is a guess that composites against whatever is behind it, so the same card came
  out one shade over the background and another over a preview. `ActionSquircle` sits a step above at
  `surfaceContainerHighest`: it used to match the card exactly, which worked only while the rows it
  sits on were on the bare background and made it invisible the moment a list was grouped.
- `SettingsGroup` — caption, rounded container of rows, supporting text. The **note goes under the
  card**: copy written as a preamble is explaining controls the reader has not reached yet. A
  screen-level intro before the first group is the exception and stays a plain `SettingsNote`.
  It wraps itself in a `Column` so it is **one** node: the lists arrange with `spacedBy(4.dp)`,
  which would otherwise be inserted between a group's own caption, card and note, making its
  internal spacing depend on the list it was dropped into.
  **Dividers inside a group are a judgement, not a default.** Separate destinations get them — the
  root list, the Search hub — because each row goes somewhere else. One set of related choices does
  not: Matching's four tiers and the Providers list are each a single thing being configured, and a
  line between every row would cut them into unrelated entries. The same judgement governs lines
  *between* groups, which is why only Advanced has them.
  A divider must also have something under it: on Advanced the one before the event trace sits
  **inside** the `BuildConfig.DEBUG` branch, or a release build would close on a rule with nothing
  beneath it, and the crash card gets none at all because it draws nothing in the usual case.
- `SettingsGroupCard` / `SettingsGroupDivider` — the container itself. Rows inside pass
  `fullBleed = false`, which is the rule above rather than an exception to it.
- `SettingsActionRow` — a row that *does* something. What every `OutlinedButton(fillMaxWidth())`
  should have been: a stack of full-width buttons is a form, and six down the Advanced screen gave
  equal maximum weight to six things of very unequal importance. No trailing chevron, because
  nothing opens. A real `Button` is then free to mean "this is the action here".
- `SettingsChoiceRow` — a short exclusive set as a row of equal-width buttons, for ranges where
  every option fits on screen (grid rows and columns run three each). It is **not** M3's
  `SingleChoiceSegmentedButtonRow`, which was tried and reverted: that applies
  `width(IntrinsicSize.Min)` *after* the caller's modifier, so it sizes to its content and silently
  ignores a `fillMaxWidth`, leaving three one-digit buttons huddled at the left edge of a full-width
  card. There is no parameter for it and `SegmentedButton` cannot be used outside that row's scope,
  so filling the width means not using the component.
- `SettingsSliderRow` — title, value read off the row, slider beneath. Five near-identical copies of
  this existed, each pairing a bare `Slider` with a note reading "Icon size: 42dp", which puts the
  value in prose above the control you are currently dragging. `valueText` formats it because a
  value is not always a number — a zero corner radius reads "square". The readout has a floor width
  so the title beside it doesn't shift as digits are gained. **Every control row's title is
  `bodyMedium`** — switch, radio, slider, choice — because they share cards; only `CategoryRow`, a
  destination rather than a control, is `bodyLarge`. It holds local state for the drag but
  still has to follow the value when a "Reset to defaults" moves it, and `remember(value)` is the
  wrong way to do that: it also re-seeds mid-drag from the write the row itself just made. That is
  harmless only while `Slider` snaps to ticks and hands back exactly the integer we reported, which
  it does **only when `steps > 0`** — and `steps` is derived from the range here, so a range shorter
  than two of its own steps would quietly make it continuous and fight its own drag. It compares
  against the last value *it* reported instead, so an external change moves the thumb and its own
  echo never does.
- `SettingsNote` and `SettingsWarning`. A warning gets its own `errorContainer` row with an icon: a
  note merely recoloured to `error` is the same shape as every other note on the screen and gets
  skimmed past, which is what happened to the line saying search cannot open at all.
- `SettingsRadioRow` is the **only** radio row. Three others had grown alongside it, one per screen
  that wanted a leading picture the shared one didn't offer, and each had drifted in padding, text
  style and whether the label was clickable. It takes a drawable or an `ImageBitmap`.
- `ProviderRow` — a mark, a toggle, and a **grip to drag the row by**, used by the Providers list
  and the widget bar's Actions. It replaced a pair of up/down arrow buttons, which cost the row 96dp
  of width and made a move of three places six taps. The arrows' real job survives as **custom
  accessibility actions** on the row, because a drag is available to neither TalkBack nor a
  keyboard, and both routes now go through the same `SearchProviders.reorderVisible` — when they
  didn't, "move down" past an uninstalled action swapped with a row that isn't on screen and
  appeared to do nothing. `draggedRow` marks the row being carried with a **tonal step**, not a
  shadow: `SettingsGroupCard` clips its children, so an elevation would simply be cut off at the
  card's rounded edge.
  The handle modifier comes from `ReorderableItem`'s scope, so the row takes it as a parameter — it
  is bound to the item's index within the column, which only the call site knows.
- `ProviderModeDialog` — whether one provider opens its own app or hands the query to a browser,
  behind the gear on its row. A dialog rather than a second switch, because the row's switch already
  means "show this at all" and two switches side by side read as halves of one setting. Only
  providers with `canUseBrowser` get the gear: Google and DuckDuckGo, whose web page runs the *same*
  search their app does. Maps, YouTube, Spotify and ChatGPT have a web page too, but it is what you
  get when the app is absent rather than something anyone would pick, so offering the switch would
  be inventing a decision.
  Three things hold this together and each was a way to get it wrong:
  - **Browser mode has to open the gate.** `requiresPackage` hides a provider whose app is missing;
    a DuckDuckGo switched to the browser doesn't need that app at all. `SearchProviders.needsMissingApp`
    is the single rule, and it takes both the absent set and the browser set as **parameters** — the
    settings screen passes the availability it just resolved, and the tests pass a browser set they
    could otherwise never write, since config is only writable through a Context.
  - **The gear has to be on the Uninstalled row too.** Without the app, DuckDuckGo's ordinary row
    isn't drawn, so the browser choice would have been locked behind installing the app it exists to
    avoid — reachable only by doing the thing you were trying not to do.
  - **Google's app route is not its web route.** `ACTION_WEB_SEARCH` resolves to whichever *search
    app* the phone prefers, so browser mode skips it entirely rather than letting it win first.
- `MissingAppRow` — the mark, the name and an **Install** button, for a row whose app isn't on the
  phone. The widget bar's Actions and the Providers list both need it, and the second was a
  disabled row reading "Not installed, so it stays hidden" until they were made to agree. Availability
  is read through `rememberOnResume` on both screens, which is why `SearchProviders` has a
  main-thread `refreshAvailabilityNow` beside its suspending refresh: the global `missing` starts out
  pessimistic for the overlay's sake, so a suspending read would put every gated provider under
  **Uninstalled** for a frame and then move it up.
- `ColorPickerAccordion`, used by both the widget bar and the search window.
- `SettingsFilterField` and `ActionSquircle`, the filter input and the row-action tile of the long
  list screens — Manage apps and Hidden contacts. Both started life private to Manage apps and were
  hoisted when Hidden contacts was brought up to the same design; that screen had been left on an
  `OutlinedTextField` and checkboxes, which read as a different app once the list beside it changed.

`GumshoosTheme` is **always** the wallpaper-derived dynamic scheme. `minSdk` is 31 and dynamic colour
arrives with 31, so the template's purple fallback could never be selected on any device this runs
on — a scheme nobody can reach is a scheme nobody checks. `darkTheme` stays a parameter only because
the overlay forces it on.

**Write the copy for someone who has never seen the code.** The developer is not the audience even
though he is currently the only user, and the drift is always the same: a note that explains the
*mechanism* rather than the effect, or an identifier leaking into a label. The widget bar's
Uninstalled group shipped with a three-line note about the Google app's silently-succeeding entry
points and a row reading "Needs the com.google.ar.lens app" — both were rewritten to "These need an
app that isn't on this phone" and, for the row, nothing at all. Reasoning belongs in the source
comment, where it already was. Package names, class names and setting keys never appear on screen.

**Length is the other half of that, and it fails later.** Copy here is written accurate first and
shortened afterwards, and the shortening keeps removing the same thing: a second sentence explaining
the mechanism that the first sentence's effect already implied. "Dismisses the search window when
another app takes over — a notification, or the shade. This also covers the Home gesture." became
"Closes search when you switch away or swipe home." Both are true; only one gets read. Two rules came
out of that pass:

That row later went further, and the way it was wrong is the more useful lesson: it was titled
"Close search when focus is lost", which names the *mechanism* — and then the mechanism changed,
because the navigation gestures stopped going through focus at all. A label written after the
implementation goes stale when the implementation moves. It reads "Close when you switch away" now,
which was true before that work and is still true after it. The setting key never changed.

- **A group's supporting note is optional and usually wrong.** "Your launcher asks before adding
  anything, and decides where it goes" was deleted outright — the launcher's own confirmation dialog
  says it better a second later. Keep a note only for the state the user cannot otherwise discover,
  which for that group is the launcher that refuses to add anything at all.
- **A caveat that reads as an excuse goes in the source.** "Some phones only offer their own
  assistant here, in which case this can't be set" is true and occasionally the real explanation, but
  the reader's next action — open assistant settings and look — is identical either way.

A related trap in the same row: **don't dim what isn't disabled.** Its mark and label were drawn at
the 0.38 alpha `ProviderRow` uses for a switched-off control, copied without noticing that the
meaning didn't carry. An app you don't have yet is not a setting turned off, and greying the name of
the thing a row exists to offer says the opposite of what the row is for.

**The M3 `SearchBar` is not usable as an inline filter, and this was checked in its source rather
than guessed.** `DockedSearchBar` hardcodes `.width(SearchBarMinWidth)` — 360dp — so it cannot fill a
column, and `SearchBarDefaults.InputField` passes `container = {}`, painting no background of its own,
so it is not usable standalone either. It is a search *destination* that expands to cover the screen.
`SettingsFilterField` is therefore a `TextField` styled as a search input: pill shape, leading
magnifier, `surfaceContainerHigh`, transparent indicator. Reference docs give the signature; only the
source gives behaviour like a hardcoded width.

`SettingsTextField` is its sibling for a value being *typed* rather than a list being filtered:
filled on `surfaceContainerHighest`, 28dp corners to match the dialog, no indicator. Three styles
were tried on the alias dialog and the failures are the useful part:

- **A tonal fill is legible only relative to what it sits on, which a shared component cannot see.**
  The first attempt copied the filter field wholesale, `surfaceContainerHigh` and a transparent
  indicator. That is exactly a dialog's own `DialogTokens.ContainerColor`, so the field was the same
  colour as its background with no underline either: no boundary of any kind, and the placeholder
  appeared to float on the dialog. The identical styling is correct on the filter field, which sits
  on the screen background. One tonal step up is the whole difference.
- **A component's parts are load-bearing together.** The stock filled field's 1dp
  `OnSurfaceVariant` indicator and square-bottomed `CornerExtraSmallTop` are one design — an
  underline exists to sit flush against a straight edge, so keeping the fill while rounding all four
  corners means dropping the indicator too.
- **The notch is the Material 2 tell, not the border.** The `OutlinedTextField` this replaced was
  not dated for being outlined; it was the floating `label` animating into a notch cut in its own
  border. `ColorPicker`'s hex input keeps an outline and looks current without one.

Any shape has to be passed deliberately — the outlined default is `CornerExtraSmall`, **4dp**, and
the filled default is square-bottomed, because **the Expressive redraw did not reach the text-field
shape tokens.** Neither default suits a `CornerExtraLarge` dialog.

Supporting text is laid out by the component, not handed to the field's `supportingText` slot: that
slot uses `SupportingTopPadding` of **4dp** and starts at `TextFieldPadding`, **16dp**, which aligns
the sentence with the *typing cursor* rather than the edge of the field. Under a full-width field
the indent reads as a mistake.

Placeholders are instructions, not samples. The alias field showed `insta, ig`, which over a dialog
headed *Spotify* reads as leftover junk; it says `Nickname` now.

The search window's **Edit** action deep-links into Manage apps. Settings is `singleTask`, so an
already-open instance receives it through `onNewIntent`, never a second `onCreate` — the request is
Compose state read inside `setContent`, not a one-shot. It carries a nonce so tapping Edit twice on
the *same* app re-fires instead of being coalesced away by an unchanged key, and the extra is cleared
once read so a configuration-change recreate doesn't re-jump.

**`animateScrollToItem` composes every row it passes**, so scrolling to an app far down Manage apps
crawled. Jump with `scrollToItem` to a few rows above the target, then animate the remainder: the
landing still reads as a glide and never measures more than a screenful. The highlight that follows
is a real ripple — a `PressInteraction` emitted on a hoisted `MutableInteractionSource` wired to that
one row — and it has to come *after* the scroll settles, because a `LazyColumn` only composes what is
on screen and an unseen row cannot show one.

**Previews render the real thing.** The search window preview calls `OverlayContent` itself with
`interactive = false`; the widget bar preview calls the same `WidgetBarRenderer.toBitmap` the widget
does. A hand-built imitation agrees on the day it is written and drifts on the first change nobody
thinks to mirror — which is exactly what happened before the search window preview was replaced.
`interactive = false` suppresses focus, keyboard, back interception, dismissal and animation; almost
nothing about how the overlay *draws* depends on it, which is what makes the preview trustworthy.

Calling the real renderer is not enough on its own: **a preview must also land on the pixels 1:1.**
The widget bar preview rasterised at a capped 2x and let `ContentScale` stretch it back up, to keep
the allocation down while a slider is dragged. On a 3x phone that is a 1.5x bilinear upscale, and the
shadow is the one part fine enough to show it — a gradient a few pixels wide facets, and the corners
come out visibly stepped. Two rounds were spent on the shadow *drawing* before the report came back
that the jaggedness was only ever in the preview. It now rasterises at `constraints` and falls back
to a scale only past `MAX_AREA_PX`, with the density scaled to match. **A preview showing a defect
the real thing doesn't have costs more than no preview**, and the tell is that it can't be reproduced
on the home screen.

The single exception is the IME corner swap, and it earns it. The overlay window does not resize
under the keyboard — it is `MATCH_PARENT` with no `softInputMode`, so the IME is just another window
on top — and a tall card runs on behind it. That is intended: the card reads as a sheet tucked under
the keyboard and its content returns whole when the keyboard goes down. The corners are the part
that doesn't survive, clipped part-way through their arc, which reads as damage rather than as
continuation. So the bottom corners square off while `cardTop + height` exceeds `maxHeight -
imeHeight`, and the card meets the keyboard on a straight edge.

That comparison is only valid where the composable *is* the window. In the preview it is a ~302dp
strip in a scrolling list, on a screen whose colour picker has a hex field: raising that keyboard
would convince the preview its card runs past the bottom and square off corners nothing is covering.
Mixing a window-level inset with a local constraint is the trap; anything else needing a window
property needs the same guard.

Adaptive-versus-chosen colour is stored as **two independent values**, never as one sealed type whose
colour lives only in its `Solid` case. That form discards the pick the moment adaptive is switched
on, and both the widget bar and the search window shipped that bug before it was fixed.

The device wallpaper bitmap is **not obtainable**: `WallpaperManager.peekDrawable` and `getDrawable`
require `MANAGE_EXTERNAL_STORAGE` or signature-level `READ_WALLPAPER_INTERNAL`. Lint catches the
attempt. `getWallpaperColors` needs no permission and is what the preview backdrops use.

## Configuration and diagnostics

`GumshoosConfig` is a singleton of `mutableStateOf` values backed by SharedPreferences, loaded in
`GumshoosApp.onCreate` because the activity, the service and the widget provider can each be first to
run in the process. Keys are stable and independent of labels, so settings survive renames.

`EventLog` and `CrashLog` write to files that survive process death. Both exist because silent
failures have repeatedly been indistinguishable from working code — `runCatching { startActivity(...) }`
around a refused intent looks exactly like success. Prefer recording an outcome over swallowing it.

**They surface differently, and deliberately.** The crash card is always on Advanced: it draws
nothing when there is no crash, and someone who can hit Copy and send a trace is worth a great deal
given ADB is rarely attached. The event trace *viewer* is behind `if (BuildConfig.DEBUG)`, because a
monospace log dump means nothing to anyone else — which is what `buildConfig = true` in
`buildFeatures` is for, AGP 8 having stopped generating `BuildConfig` unless asked. **`EventLog`
still records in a release build**; only the viewer goes away, and the recording is what makes a
crash report readable when one arrives. With R8 on, the gate now removes the code rather than merely
not calling it — the trace's own strings are absent from the release dex, checked rather than
assumed.

**R8 runs with `-dontobfuscate`, and that is deliberate.** Shrinking is what was wanted: 24MB to
3.4MB, and dead code genuinely gone. Renaming adds nothing to that and would break the diagnostic
this app leans on hardest — `mapping.txt` lands in `app/build/outputs/mapping/release/`, which is
gitignored and destroyed by every clean build, so a trace copied off a phone weeks later would be
permanently unreadable. If obfuscation is ever re-enabled, archive `mapping.txt` beside every APK
that gets installed. `isShrinkResources` is off for a related reason, spelled out in
`proguard-rules.pro`: widget drawables ought to survive it, but if that reasoning is wrong the
failure is a blank icon rendered in the *launcher's* process where nothing of ours can observe it.

Two conventions that read as inconsistencies unless you know why:

- On/off lists store the **disabled** set (`disabledProviders`), so a provider added in a later
  version defaults on rather than being invisible to anyone with settings already saved. Widget
  actions invert this and store the **enabled** set, because the layout has only three slots and a
  fourth action must not shove itself into a bar someone has already arranged. `browserProviders`
  follows the first rule for the same reason: it stores the ids sent to a browser, so the app stays
  the default and a provider that later gains the choice starts where every other one already is.
- `preferredBrowser` is a package name, and **null is a real choice** rather than an unset value —
  "Automatic" means let the phone decide, which with several browsers and no default means its
  chooser, and someone may want exactly that.

**`queryIntentActivities` narrows a web query to the app that would open the link, and the flag to
stop it is `MATCH_ALL`.** A phone with Chrome and Firefox listed **only Firefox** — the default
browser. Passing `0` gets the same preferred-handler resolution the *launch* path uses, which is the
right answer to "who should open this link" and the wrong answer to "which browsers exist". The
filtering happens after the match, so no amount of rephrasing the intent gets past it.

That last part cost two rounds. Three differently-phrased probes were added first, on the theory that
browsers disagree about `CATEGORY_BROWSABLE` — and all three came back with the same single app,
which was the evidence that the intent had never been the problem. **When several genuinely different
inputs produce one identical wrong answer, stop editing the input.** The probes were kept as belt and
braces, but `BrowserApps.probes` says in as many words that they are not what fixed it, because a
comment that credits the wrong change is how the next person re-derives the wrong lesson.

Two things that remain true about the design:

- **The host does the filtering, not the category.** `example.com` is reserved by RFC 2606 precisely
  so nothing can claim it, so an app matching it matches the web in general — which is what a browser
  is. That is what lets a probe drop `BROWSABLE` without becoming "every app with a deep link", and
  it is why probing a real address is wrong: a google.com URL lists the Google app.
- **Package visibility is not the suspect here, though it looks like one.** The manifest's `<queries>`
  declares MAIN/LAUNCHER, so every app with a launcher icon is already visible and a browser has one.
  Worth knowing because visibility filtering never fails loudly — it returns fewer rows, which reads
  exactly like an app that genuinely didn't match. Each probe records what it found to `EventLog`, by
  scheme and category rather than by position, and the resolved default is recorded beside them: it
  is the one answer guaranteed to be a browser, so it is what a suspicious list gets compared against.
- Ordered lists are a comma-joined string, never a `StringSet`: SharedPreferences sets are unordered
  and order is the entire content of the value. Unlisted ids sort last, for the same
  forward-compatibility reason.

The app index is kept current by a `LauncherApps.Callback` registered in `OverlayService` — it
reports per package, which is what makes an incremental re-index possible. A full `load()` decodes an
icon for every installed app, so reacting to a Play Store batch that way would be ruinous; it also
records its own cost to the trace. Settings → Advanced has manual rebuilds for both the index and the
widgets.

**An `AppEntry` is a launcher activity, not an app.** A few packages — Amazon among them — declare
several, so `AppRepository.apps` holds more than one entry sharing a package name, routinely with the
same label. Custom names and hidden apps are keyed by `componentKey`
(`ComponentName.flattenToShortString()`) so those entries stay separately addressable; keying either
list by package put a duplicate key in a `LazyColumn` and crashed the moment the list scrolled far
enough to compose both. Two rules hold that together:

- Reads fall back to a bare **package** key. `GumshoosConfig` loads in `GumshoosApp.onCreate`, before
  the index exists, so there is nowhere to migrate values saved by the builds that keyed by package.
- Writes **expand** a package-wide value onto the siblings before letting one component diverge.
  Without that, the read fallback keeps applying and unchecking one row of a package hidden as a
  whole appears to do nothing at all.

`componentKey` is a stored `val`, not a getter: `visible` recomputes on every access and sits on the
type-ahead path, so flattening per app per keystroke would put a string allocation there.

Uninstalling prunes that package's alias and hidden keys (`GumshoosConfig.forgetPackage`) — an orphan
is unreachable, since no row exists to clear it, and it inflates a count nothing on screen explains.
It hangs off `onPackageRemoved` **only**: `onPackagesUnavailable` shares `AppRepository.removePackage`
and fires when a work profile is switched off, whose apps are coming back and expect their settings
intact. The cost is that settings no longer survive an uninstall/reinstall round trip.

## Testing reality

Unit tests cover the pure logic: `AppMatcher`, and every answer provider against a pinned
`AnswerContext`. That is where new logic should go.

Not unit-testable, and verified on-device instead: anything holding an `ImageBitmap` (`AppEntry`,
`ContactEntry`), the overlay window itself, and the settings UI.

The developer does not usually connect ADB — builds are sideloaded and tested by hand. Compensate by
running lint, unit-testing pure logic, and **stating plainly which claims are unverified**. For
animation problems, ask for a slowed screen recording; frames can be measured with `ffmpeg` and the
current timings were derived that way.

**When only a human eye can see the answer, build the instrument rather than shipping guesses.** Each
Lens candidate cost a build, a sideload, a tap and a trace read, one candidate per cycle, and four
were spent that way before anything was learned — because the failure is a component that starts,
reports success and draws nothing, which no API distinguishes from a launch. An entry point tester
turned the remaining ten into one sitting. The tell that it is time to build one: the next step is a
guess, and the only way to grade it is to look.

**An instrument is allowed to be temporary.** That tester and the probe beside it shipped in Settings
→ Advanced for exactly as long as they were earning their place, and were deleted when the screen was
cut back for release. Build the next one freely and delete it the same way — what has to survive is
the *finding*, which for Lens is `WidgetActionTargets.candidates` and not the tool that produced it.

## Icon pipeline

`design/convert-icons.sh` runs svgo, then `design/prepare-svg.py`, then svg2vectordrawable. The
middle stage is not optional — the converter silently drops `gradientTransform`, reads CSS colour
names as black, ignores inherited presentation attributes, and leaves a fill-less path undrawn where
SVG would paint it black. Every one of those produces a successful build and a wrong icon.
`verify-icons.py` renders the generated drawable back to SVG and compares it against the source,
which is the only check that exercises the converter rather than the preprocessing. Run it after
touching the pipeline; it needs `cairosvg`, `numpy` and `pillow`, which on a managed Python means a
virtualenv rather than `pip install`.

**The pipeline handles outline UI icons as well as filled brand logos**, and there is nothing to
hand-author. It did not always: Feather and Lucide icons carry `stroke="currentColor"` and
`stroke-width` on the root `<svg>` rather than on each path, and the converter reads each `<path>`
alone, so every stroke was dropped and `make_fills_explicit` then defaulted each path to a black
fill — an outline icon shipping as a filled blob, announced only as `WARNING path had no fill`.
Thirteen icons were hand-authored around that, and *re-running the script clobbered them all*.

`propagate_root_presentation` now copies the root's inherited presentation attributes onto each
path before anything else runs, and resolves `currentColor` to white plus an `android:tint`, which
is the arrangement the hand-authored ones used. Three things about it are worth knowing:

- **The tell is in the artwork, not in a folder.** An outline icon is one that draws in
  `currentColor`; `prepare-svg.py` reports that on stdout and `convert-icons.sh` adds the tint. So
  there is no list to maintain and nothing to file in the right place, which is the failure mode a
  `ui/` and `brand/` split would have introduced.
- **It was never only a stroke problem.** `ic_provider_search` is a filled Material Symbols icon
  whose root carries `fill="#e3e3e3"`; that was being lost too, and the icon had been shipping
  black. It is `tintable` in `WidgetBarStyle`, so the wrong colour never reached the screen.
- **Only the root is walked.** A `<g>` carrying its own fill or stroke is reported rather than
  half-handled, because the pipeline's entire problem is failures that build cleanly and draw the
  wrong thing. `ic_provider_duckduckgo` is the first source that hit it — a `<g fill="#14307E"
  opacity=".8">` around the eyes and eyebrows, whose paths carry no fill of their own. **The remedy
  is to flatten the group in the source SVG**, pushing its fill onto each child path and its group
  `opacity` onto each as `fill-opacity`, which is equivalent wherever the children don't overlap.
  Left alone it would not merely have lost the navy: that artwork's root carries `fill="none"`, so
  `propagate_root_presentation` would have handed both paths *that* and the duck's face would have
  drawn as nothing at all. The converter folds `fill-opacity` into the colour — `#CC14307E` — since
  a vector drawable has no separate group alpha.

The converter has a `--tint` option. It accepts any value, including a valid hex, and emits nothing
— which is why the attribute is written in afterwards.

**The SVG's filename becomes the resource name**, via `basename` in `convert-icons.sh`, so it must be
a legal Android resource id — lowercase, digits and underscores only. Lucide downloads arrive
hyphenated (`rotate-ccw.svg`), and a hyphen produces a drawable the build rejects outright. That one
is loud rather than silent, unlike the rest of this pipeline's failure modes, but rename on the way
in.

`verify-icons.py` **could not grade an outline icon at all** before this. It rendered the drawable's
stored white against a source that resolves `currentColor` to black, so all thirteen sat permanently
at 10–33% `CHECK` and a real geometry error would have looked identical. It now renders a tinted
drawable's colours as black, since a tint replaces them anyway. Every icon in the set verifies at
0.0–0.7% except `ic_provider_voice` at 29.1%, which is a pre-existing clip-path the converter drops.
