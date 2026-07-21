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
  nothing — the shape is drawn opaque and then cleared back out with a `CLEAR` xfermode, leaving the
  part that fell outside it.
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

Settings → Advanced has a probe that lists what each package declares, with exported flags, and where
each candidate intent really resolves. Use it before changing a chain, and order chains by **what
cannot fail silently** — a launcher intent is the app's own MAIN/LAUNCHER activity and cannot
start-and-vanish, so it leads; deeplinks and exported trampolines go last.

The probe cannot answer the question that actually matters — *did anything appear* — so **Entry point
tester**, next to it, fires one candidate at a time to be watched. It reads
`WidgetActionTargets.candidates`, which the shipped chain filters with `inChain`, so a rung tested is
the rung that ships rather than a copy that has drifted. Before it, each candidate cost a build, a
sideload and a trace read; ten Lens candidates were settled in one sitting with it.

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

The home list is Permissions, Appearance, Manage apps, Search, Behaviour and Advanced, each with a
leading icon. **Search is a hub**, not a screen of controls: Matching, Instant answers, Contacts and
Providers hang off it. Manage apps is the merge of what were Custom names and Hidden apps.

Shared pieces live in `SettingsComponents.kt`:

- `SettingsScaffold`, and `SettingsScaffoldWithHeader` for screens whose preview must stay put while
  the controls scroll under it.
- `fullBleedRow()` — the list applies its inset to the scrolling column, so a row's ripple would
  otherwise stop where the text starts. This measures the row wider, draws it shifted back out, and
  reports the original width. Safe only because both lists pad *inside* `verticalScroll`. Rows inside
  a card must **not** use it; the ripple belongs within the rounded bounds.
- `SettingsCard` / `SettingsCardShape` / `settingsCardColour`, shared so cards don't drift apart.
- `SettingsGroupCard` / `SettingsGroupDivider` — the home list's rounded groups. Rows inside pass
  `CategoryRow(fullBleed = false)`, which is the rule above rather than an exception to it.
- `ColorPickerAccordion`, used by both the widget bar and the search window.

**The M3 `SearchBar` is not usable as an inline filter, and this was checked in its source rather
than guessed.** `DockedSearchBar` hardcodes `.width(SearchBarMinWidth)` — 360dp — so it cannot fill a
column, and `SearchBarDefaults.InputField` passes `container = {}`, painting no background of its own,
so it is not usable standalone either. It is a search *destination* that expands to cover the screen.
Manage apps' filter is therefore a `TextField` styled as a search input: pill shape, leading
magnifier, `surfaceContainerHigh`, transparent indicator. Reference docs give the signature; only the
source gives behaviour like a hardcoded width.

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

`EventLog` and `CrashLog` write to files that survive process death and surface in Settings →
Advanced. Both exist because silent failures have repeatedly been indistinguishable from working
code — `runCatching { startActivity(...) }` around a refused intent looks exactly like success.
Prefer recording an outcome over swallowing it.

Two conventions that read as inconsistencies unless you know why:

- On/off lists store the **disabled** set (`disabledProviders`), so a provider added in a later
  version defaults on rather than being invisible to anyone with settings already saved. Widget
  actions invert this and store the **enabled** set, because the layout has only three slots and a
  fourth action must not shove itself into a bar someone has already arranged.
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

## Icon pipeline

`design/convert-icons.sh` runs svgo, then `design/prepare-svg.py`, then svg2vectordrawable. The
middle stage is not optional — the converter silently drops `gradientTransform`, reads CSS colour
names as black, and leaves a fill-less path undrawn where SVG would paint it black. All three
failures produce a successful build and a wrong icon. `verify-icons.py` renders the generated
drawable back to SVG and compares it against the source, which is the only check that exercises the
converter rather than the preprocessing.

**The pipeline handles filled brand logos only.** Feather and Lucide outline icons carry
`stroke="currentColor"` on the root `<svg>` rather than on each path, and `make_fills_explicit` only
inspects the `<path>` — so it sees neither a fill nor a stroke there, defaults the path to black, and
emits a filled blob with no `android:tint`. It says so, quietly, as `WARNING path had no fill`.

Every UI icon is therefore **hand-authored** as a stroke drawable — `fillColor="#00000000"`,
`strokeColor`, `strokeWidth="2"`, round caps and joins, `android:tint` — and *re-running
`convert-icons.sh` clobbers them all*, because their SVGs still sit in `design/icons/`. The set:
`ic_provider_at_sign`, `_edit`, `_edit_app`, `_eye_on`, `_eye_off`, `_info`, `_trash`, `_moon`,
`_search_code`, `_terminal`, `_text_search`, `_wrench`, `_hat_glasses`. Teaching `prepare-svg.py` to
propagate a root-level stroke down to its paths would fix this properly; until then, regenerate the
brand logos by hand-picking files rather than running the script over the whole directory.
