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

Compose comes from the BOM, currently `2026.06.01` — **Material3 1.4.0**, the first stable release of
the Expressive components, and Compose UI 1.11.4. Two things to know before touching it:

- Material3 1.4 **stopped pulling in `material-icons-core` transitively**, so it is named explicitly
  in the catalog. Ten stock icons are used across the app; the BOM still pins the artifact, so it
  can't drift on its own. The library itself is frozen at 1.7.8 and no longer developed — if it is
  ever dropped from the BOM, hand-author the ten as drawables rather than chasing a version.
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

Settings → Advanced has a probe that lists what each package declares, with exported flags, and where
each candidate intent really resolves. Use it before changing a chain, and order chains by **what
cannot fail silently** — a launcher intent is the app's own MAIN/LAUNCHER activity and cannot
start-and-vanish, so it leads; deeplinks and exported trampolines go last.

The probe cannot answer the question that actually matters — *did anything appear* — so **Entry point
tester**, next to it, fires one candidate at a time to be watched. It reads
`WidgetActionTargets.candidates`, which the shipped chain filters with `inChain`, so a rung tested is
the rung that ships rather than a copy that has drifted. Before it, each candidate cost a build, a
sideload and a trace read; ten Lens candidates were settled in one sitting with it.

Two things about diagnosing this cost a round trip each, and both generalise:

- **A rung's number is not its name.** The chain is built with `listOfNotNull`, so a rung the device
  lacks drops out and every rung behind it renumbers. A trace reading `rung1` was read here as the
  launcher intent when on that phone it was the deeplink two places down, and the wrong conclusion
  followed from it. Rungs are logged by action, data and component now. Anything logged by position
  is only meaningful beside the source of the build that wrote it.
- **Diagnose on the phone that has the bug.** "I can't reproduce it" was the most informative fact in
  the report and was nearly discarded as noise: this phone had the standalone Lens app and theirs did
  not. Get the probe and the trace from *their* device before theorising. Note also that reproducing
  it here by uninstalling was not quite the same state — a preinstalled app that is "uninstalled"
  stays present with its launcher activity disabled, which the probe shows as `1 of 2 declared match
  / internal LensLauncherActivity` rather than as absent.

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

The screens are being ported to a single M3 vocabulary, module by module. The root list set the
pattern; Manage apps and Hidden contacts set the long-list variant of it. The rest still carry the
older idiom — an accent `SettingsSection` heading over loose rows separated by `SettingsDivider`
hairlines — which is a Material 2 / preference-screen layout. **Grouping is a container, not a
heading and a line.** New work uses `SettingsGroup`; the old pair survives only where a screen has
not been ported yet.

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
- `fullBleedRow()` — the list applies its inset to the scrolling column, so a row's ripple would
  otherwise stop where the text starts. This measures the row wider, draws it shifted back out, and
  reports the original width. Safe only because both lists pad *inside* `verticalScroll`. Rows inside
  a card must **not** use it; the ripple belongs within the rounded bounds. It has no future: once
  every screen is grouped, nothing is loose enough to need it.
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
  line between every row would cut them into unrelated entries.
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
- `SettingsSection` — the last of the old accent headings, alive only on the screens module 8 has
  yet to reach. `SettingsSubSection` and `SettingsCard` are already gone, both superseded by
  `SettingsGroup`.
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

**When only a human eye can see the answer, build the instrument rather than shipping guesses.** Each
Lens candidate cost a build, a sideload, a tap and a trace read, one candidate per cycle, and four
were spent that way before anything was learned — because the failure is a component that starts,
reports success and draws nothing, which no API distinguishes from a launch. The entry point tester
turned the remaining ten into one sitting. The tell that it is time to build one: the next step is a
guess, and the only way to grade it is to look.

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
