package com.vynex.gumshoos

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.graphics.PixelFormat
import android.os.IBinder
import android.os.SystemClock
import android.os.UserHandle
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.AbstractComposeView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.vynex.gumshoos.answers.AnswerContextFactory
import com.vynex.gumshoos.answers.CurrencyRates
import com.vynex.gumshoos.widget.WidgetAction
import kotlinx.coroutines.launch

/**
 * Holds the search window resident in WindowManager so that showing it costs a visibility flip and
 * one frame, rather than an activity launch plus window creation.
 *
 * Two jobs, and they're separate on purpose:
 *  - host the pre-attached overlay window
 *  - stay in the foreground so the process (and this warm view) survives between invocations
 */
class OverlayService :
    LifecycleService(),
    SavedStateRegistryOwner,
    ViewModelStoreOwner,
    OnBackPressedDispatcherOwner {

    companion object {
        const val ACTION_SHOW = "com.vynex.gumshoos.SHOW"
        const val ACTION_HIDE = "com.vynex.gumshoos.HIDE"
        const val ACTION_WARM = "com.vynex.gumshoos.WARM"

        /** Free-text label for where the show came from, so the readout can distinguish paths. */
        const val EXTRA_SOURCE = "source"

        const val TAG = "GumshoosLatency"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "gumshoos_overlay"

        /** How long the entrance takes to settle; focus changes before then are not dismissals. */
        private const val ENTRANCE_SETTLE_MILLIS = TotalEnterMillis.toLong()

        /**
         * Written out rather than taken from [Intent], where the constant is deprecated — for
         * *senders*, which we are not. Receiving the system's own is still supported and is the
         * only way this app learns that a navigation gesture happened.
         */
        private const val ACTION_CLOSE_SYSTEM_DIALOGS = "android.intent.action.CLOSE_SYSTEM_DIALOGS"

        /** `PhoneWindowManager.SYSTEM_DIALOG_REASON_KEY`, which is not public. */
        private const val EXTRA_CLOSE_REASON = "reason"

        /**
         * The reasons that mean the user asked to leave.
         *
         * `recentapps` is what the swipe-up gesture reports, and it reports it whether the gesture
         * ends at Recents or at Home — both were captured. `homekey` is the Home button's, kept for
         * three-button navigation, where nothing here has been measured.
         *
         * Deliberately not listed: `dream` and `lock`, which arrive alongside the screen turning
         * off and are already handled by ACTION_SCREEN_OFF; and `assist`, because Gumshoos can be
         * the assistant, and closing on the broadcast that announces our own launch is a race with
         * no upside.
         */
        private val GESTURE_REASONS = setOf("recentapps", "homekey")

        /** Derived from the content's own exit timing so the two can't drift apart. */
        const val EXIT_ANIMATION_MILLIS = TotalExitMillis + 20L

        fun intent(context: Context, action: String, source: String? = null): Intent =
            Intent(context, OverlayService::class.java).apply {
                this.action = action
                source?.let { putExtra(EXTRA_SOURCE, it) }
            }
    }

    private lateinit var windowManager: WindowManager
    private lateinit var composeView: OverlayRootView
    private lateinit var layoutParams: WindowManager.LayoutParams

    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override val viewModelStore = ViewModelStore()

    override val onBackPressedDispatcher = OnBackPressedDispatcher { /* nothing below us */ }

    /**
     * Compose state, not a plain Boolean — the previous version read a raw `var` inside setContent,
     * so nothing ever recomposed and every animation was inert.
     */
    private var overlayVisible by mutableStateOf(false)

    /**
     * Bumped once the card is fully hidden, telling the content to clear itself.
     *
     * The reset cannot happen at either end of an animation: on hide it reflows underneath the
     * closing card, and on show the entrance renders a frame of the previous state before clearing.
     * Doing it here — after the exit has finished and before the next entrance begins — is the only
     * moment nothing is on screen to disturb.
     */
    private var resetToken by mutableStateOf(0)

    /** Whether the window is currently attached to WindowManager (independent of visibility). */
    private var attached = false

    /** True until the first successful show, so we can tell warm shows from the very first one. */
    private var neverShown = true

    /** When the current show began, so the entrance can be excluded from focus handling. */
    private var shownAt = 0L

    private var pendingShowStart = 0L
    private var pendingShowSource = "unknown"
    private var hideRunnable: Runnable? = null

    /**
     * Screen off, and the navigation gestures.
     *
     * Window focus used to be the only signal that something else had taken over, and on gesture
     * navigation it never arrives at all. Measured on Android 16: the overlay keeps window focus
     * through the whole swipe-up gesture, keeps it while Recents animates in, and still holds it
     * with Recents settled in front of it — so the card sat on top of a screen it no longer
     * belonged to until it was dismissed by hand. Swiping Home behaves the same way. Focus loss was
     * observed exactly once in a long capture, when the screen turned off. This was never the
     * "sluggish" problem an earlier comment here described; it was no signal at all.
     *
     * ACTION_CLOSE_SYSTEM_DIALOGS is what the system does tell us. Android 12 restricted *sending*
     * it, not receiving, so the system's own still reaches a runtime-registered receiver.
     *
     * It is not early, and nothing earlier exists: the window receives no touch, no insets change,
     * no configuration change and no trim-memory during the gesture, which was watched for rather
     * than assumed. Measured from the finger landing —
     *
     *   flick to Home                256ms
     *   swipe and hold into Recents  631ms
     *
     * — because it tracks the transition rather than the touch, so a longer gesture reports later.
     * That is late enough to be visible and early enough to matter: the alternative is a card that
     * never leaves.
     *
     * **An AccessibilityService is slower than this, and was measured rather than argued about.**
     * It is the obvious next idea — it watches the system's windows instead of our own, and the
     * shell starts animating Recents at +70ms — so it is worth knowing that a probe service with
     * `notificationTimeout=0` on `typeWindowsChanged|typeWindowStateChanged` got its first event
     * **53ms after this broadcast on the Recents gesture and 45ms after it going Home**, on the same
     * two gestures in the same capture. Nothing arrives during the swipe at all: the events land in
     * a single burst at one timestamp, because that is when the window list is recomputed and
     * published, which happens after the transition rather than as it starts. So it buys nothing,
     * for a permission that reads "full control of your device" and, on a sideloaded build, a
     * restricted-setting unblock as well. Don't rebuild it.
     *
     * **Immersive mode does nothing from here either**, and that was tried twice. Asking this
     * window's `WindowInsetsController` to hide the navigation bars with
     * `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` — the thing games use, whose gesture-blocking is a
     * side effect of the bars being hidden rather than a gesture API — is accepted without
     * complaint and changes nothing: the bars stay put, `onApplyWindowInsets` never fires, and the
     * gesture behaves identically. The obvious objection to the first run was that this window lacks
     * `FLAG_LAYOUT_NO_LIMITS` and so was never edge-to-edge, which is a precondition for hiding the
     * bars — so it was rerun *with* that flag, genuinely edge-to-edge, and the result was identical:
     * `requested HIDE navigationBars` logged, `onApplyWindowInsets` never fired once. A
     * service-hosted overlay is simply not granted insets control, and no error says so.
     * `setSystemGestureExclusionRects` is not an alternative: it reaches only the back gesture at
     * the left and right edges, and the bottom strip is in `getMandatorySystemGestureInsets`, which
     * is documented as not overridable.
     *
     * **A window covering the gesture strip never sees the swipe, and this is the one to know**,
     * because it is what everyone suggests and it looks like it ought to work. A second overlay
     * window over the bottom strip, `FLAG_LAYOUT_NO_LIMITS` so it genuinely covers the navigation
     * area, receives *taps* there — and never once received a swipe that became a navigation
     * gesture. Every real gesture in that capture still dismissed through the broadcast above.
     *
     * The tell is in the timestamps rather than the behaviour: on the taps it did receive, DOWN and
     * UP arrived **1–2ms apart**, which no finger can do. The events were buffered and replayed
     * after the fact. So the system defers the whole stream, decides whether it is a navigation
     * gesture, and only releases it to the window below if it decided *no*. A window there can
     * therefore only ever be told about touches that were already ruled out as gestures — which
     * means the gesture cannot be observed early **or blocked**, at any strip size, with any flags.
     * That also disposes of the "invisible overlay blocks the home swipe" claim: an app doing that
     * is re-asserting itself after the gesture, not preventing it.
     *
     * The cost of trying was visible too — a strip that swallows taps closes the window whenever
     * anything is touched near the bottom edge, keyboard included.
     */
    private val systemUiReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    EventLog.record("screen off")
                    hide()
                }

                ACTION_CLOSE_SYSTEM_DIALOGS -> {
                    val reason = intent.getStringExtra(EXTRA_CLOSE_REASON)
                    if (reason !in GESTURE_REASONS) return
                    if (!GumshoosConfig.hideOnFocusLoss) return
                    if (SystemClock.uptimeMillis() - shownAt < ENTRANCE_SETTLE_MILLIS) return
                    EventLog.record("system ui takeover ($reason) -> hide")
                    // The ordinary animated exit, and that was tried both ways on the phone. The
                    // argument for skipping it — the system is already animating, so ours would be
                    // the only thing on screen still moving — is sound in the abstract and wrong in
                    // practice: cutting the card dead reads as it having crashed out, where the
                    // animation reads as it getting out of the way. Watch it before changing it.
                    hide()
                }
            }
        }
    }

    /**
     * Keeps the app index current as packages come and go.
     *
     * The index was previously built once here and never again, so an app installed after the
     * service warmed didn't appear, and an uninstalled one kept showing, until the process next
     * died. LauncherApps is the API meant for this — it reports per package rather than as a
     * blanket "something changed", which is what makes an incremental update possible.
     *
     * Registered at runtime rather than in the manifest, which is also the only option: the package
     * broadcasts are implicit, and Android 8+ won't deliver those to a manifest-declared receiver.
     * This service is resident to keep the overlay warm, so there is always something listening.
     */
    private val packageCallback = object : LauncherApps.Callback() {
        override fun onPackageAdded(packageName: String, user: UserHandle) =
            reindex(packageName, "added")

        override fun onPackageChanged(packageName: String, user: UserHandle) =
            reindex(packageName, "changed")

        override fun onPackageRemoved(packageName: String, user: UserHandle) {
            EventLog.record("app index removed $packageName")
            refreshWidgetsIfActionPackage(packageName)
            lifecycleScope.launch {
                runCatching { AppRepository.removePackage(packageName) }
                // Only here, not in onPackagesUnavailable: a genuine uninstall's settings can never
                // be reached again to clear them by hand, so prune them rather than leave a count
                // no row explains. removePackage runs first so the pruned aliases index nothing.
                if (GumshoosConfig.forgetPackage(this@OverlayService, packageName)) {
                    EventLog.record("settings forgotten for $packageName")
                }
            }
        }

        // Fired when a package becomes reachable or not without being installed or uninstalled —
        // external storage remounting, or a work profile being turned on and off.
        override fun onPackagesAvailable(
            packageNames: Array<out String>,
            user: UserHandle,
            replacing: Boolean,
        ) = packageNames.forEach { reindex(it, "available") }

        override fun onPackagesUnavailable(
            packageNames: Array<out String>,
            user: UserHandle,
            replacing: Boolean,
        ) {
            packageNames.forEach { name ->
                lifecycleScope.launch { runCatching { AppRepository.removePackage(name) } }
            }
        }

        /**
         * Re-pushes the bar when an app one of its icons depends on comes or goes.
         *
         * A placed widget holds whatever RemoteViews it was last given, so uninstalling Lens would
         * otherwise leave its icon on the bar until some unrelated change re-pushed it — tappable,
         * and by then able to do nothing at all. The reverse matters as much: installing the app has
         * to bring an icon that is already switched on back into view.
         *
         * Cheap enough to run unconditionally on the packages that matter: [WidgetAction] names two
         * at most, and the comparison is a string.
         */
        private fun refreshWidgetsIfActionPackage(packageName: String) {
            if (WidgetAction.entries.none { it.requiredPackage == packageName }) return
            EventLog.record("widget action package $packageName changed, re-pushing bar")
            runCatching { SearchWidgetProvider.refreshAll(this@OverlayService) }
                .onFailure { EventLog.record("bar re-push FAILED ${it.javaClass.simpleName}") }
        }

        private fun reindex(packageName: String, reason: String) {
            EventLog.record("app index $reason $packageName")
            refreshWidgetsIfActionPackage(packageName)
            lifecycleScope.launch {
                runCatching { AppRepository.reindexPackage(this@OverlayService, packageName) }
                    .onFailure { Log.w(TAG, "reindex $packageName failed", it) }
                // The "Search using" row is gated on Spotify and ChatGPT being installed, and was
                // resolved once at warm for the same reason the app index was. Two package lookups.
                runCatching { SearchProviders.refreshAvailability(this@OverlayService) }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        ContextCompat.registerReceiver(
            this,
            systemUiReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(ACTION_CLOSE_SYSTEM_DIALOGS)
            },
            // Both are protected system broadcasts, so this cannot be NOT_EXPORTED.
            ContextCompat.RECEIVER_EXPORTED,
        )

        runCatching {
            getSystemService(LauncherApps::class.java)?.registerCallback(packageCallback)
        }.onFailure { EventLog.record("package callback FAILED ${it.javaClass.simpleName}") }

        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)

        createNotificationChannel()
        buildOverlayView()

        // Built once, here, while the process is warming — never on the show path. Icon decoding for
        // a full app list costs hundreds of milliseconds and would destroy the sub-20ms show.
        lifecycleScope.launch {
            runCatching { AppRepository.load(this@OverlayService) }
                .onFailure { Log.e(TAG, "app index load failed", it) }
            runCatching { UsageRepository.refresh(this@OverlayService) }
                .onFailure { Log.e(TAG, "usage stats refresh failed", it) }
            runCatching { ContactsRepository.load(this@OverlayService) }
                .onFailure { Log.e(TAG, "contacts load failed", it) }
            runCatching { ContactsRepository.loadActionIcons(this@OverlayService) }
                .onFailure { Log.e(TAG, "contact action icons failed", it) }
            runCatching { SearchProviders.refreshAvailability(this@OverlayService) }
                .onFailure { Log.e(TAG, "provider availability failed", it) }
            runCatching {
                CurrencyRates.refresh(
                    this@OverlayService,
                    AnswerContextFactory.forDevice(this@OverlayService).currencyCode,
                )
            }.onFailure { Log.e(TAG, "rate refresh failed", it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        // Captured as early as possible: everything after this is work we're trying to measure.
        val entry = SystemClock.uptimeMillis()

        // Logged before anything else can fail, so the trace always shows that the intent arrived.
        // Previously the first log line came after startForeground, which meant a failure there was
        // indistinguishable from the intent never being delivered at all.
        EventLog.record("onStartCommand ${intent?.action?.substringAfterLast('.') ?: "null"}")

        // Unconditionally, and first among the work. The old code only promoted to foreground in
        // the `else` branch, so a cold start carrying ACTION_SHOW never called this and the system
        // killed us with ForegroundServiceDidNotStartInTimeException.
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (t: Throwable) {
            // Background FGS starts are restricted on Android 12+. Record it rather than dying
            // silently: the window itself doesn't need foreground status to be shown.
            EventLog.record("startForeground FAILED ${t.javaClass.simpleName}")
            Log.e(TAG, "startForeground failed", t)
        }

        // START_STICKY turns any throw in here into a restart loop: the system revives the service,
        // it throws again, and because the activity shares this process the app becomes unopenable.
        // Record and stand down instead — CrashLog still captures the trace for diagnosis.
        try {
            when (intent?.action) {
                ACTION_SHOW -> show(entry, intent.getStringExtra(EXTRA_SOURCE) ?: "unknown")
                ACTION_HIDE -> hide()
                else -> attachIfNeeded()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "onStartCommand failed; stopping rather than restart-looping", t)
            CrashLog.recordHandled(this, Thread.currentThread(), t)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    // ---- window lifecycle -------------------------------------------------------------------

    /**
     * ComposeView that actually feeds the back dispatcher and notices losing focus.
     *
     * Neither happens for free in a service-hosted window. We own an OnBackPressedDispatcher and set
     * it as the ViewTree owner, but nothing was ever invoking it — so BackHandler could never fire,
     * which is why Back never closed the overlay.
     */
    private inner class OverlayRootView(context: Context) : AbstractComposeView(context) {

        @Composable
        override fun Content() {
            OverlayContent(
                visible = overlayVisible,
                resetToken = resetToken,
                onDismiss = { hide() },
            )
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) onBackPressedDispatcher.onBackPressed()
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        /**
         * An overlay window floats above app windows by design, so nothing removes it when another
         * app comes forward — tapping a notification would leave the search card sitting on top of
         * whatever opened. Losing window focus is the signal that something else took over.
         *
         * It cannot distinguish *what* took over: a notification tap and the shade opening look the
         * same from here, and that is fine, because the response to both is the same.
         *
         * What it does **not** cover is the navigation gestures, and an earlier version of this
         * comment had that wrong — it claimed Home "reports late". It does not report at all. The
         * window keeps focus through the whole swipe, keeps it while Recents animates in, and still
         * holds it once Recents has settled in front of it; the same going Home. That is measured,
         * on Android 16 with gesture navigation. [systemUiReceiver] is what covers those, and this
         * is left for everything else — which is most of it.
         *
         * Changes during the entrance are ignored. The window is still settling then, and hiding on
         * one would mean the card slamming shut just as it opened.
         *
         * Switchable in settings, since which half of that trade matters more is a preference.
         */
        override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
            super.onWindowFocusChanged(hasWindowFocus)
            if (hasWindowFocus || !overlayVisible) return
            if (!GumshoosConfig.hideOnFocusLoss) return
            if (SystemClock.uptimeMillis() - shownAt < ENTRANCE_SETTLE_MILLIS) return
            EventLog.record("focus lost -> hide")
            hide()
        }
    }

    private fun buildOverlayView() {
        composeView = OverlayRootView(this).apply {
            setViewTreeLifecycleOwner(this@OverlayService)
            setViewTreeSavedStateRegistryOwner(this@OverlayService)
            setViewTreeViewModelStoreOwner(this@OverlayService)
            setViewTreeOnBackPressedDispatcherOwner(this@OverlayService)
        }
    }

    /**
     * Adds the window once and leaves it there. Everything after this is a visibility flip.
     */
    private fun attachIfNeeded() {
        if (attached) return

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            HIDDEN_FLAGS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP }

        // Hidden, and non-focusable via the layout flags, so it can't steal keystrokes from
        // whatever app is actually in front.
        //
        // Measured on-device at under 20ms either way, so the extra warm frame the PREDRAWN variant
        // used to force bought nothing; GONE is simpler and skips a wasted draw at attach time.
        composeView.visibility = View.GONE

        try {
            windowManager.addView(composeView, layoutParams)
            attached = true
        } catch (e: Exception) {
            Log.e(TAG, "attach failed — overlay permission missing?", e)
        }
    }

    private fun show(entryMillis: Long, source: String) {
        attachIfNeeded()
        if (!attached) return

        // Cancel a pending hide so a fast hide->show cycle can't detach the view underneath us.
        // The old code's 300ms postDelayed removeView could race a re-show and permanently break
        // the overlay (addView on an already-added view, swallowed by a catch).
        hideRunnable?.let { composeView.removeCallbacks(it) }
        hideRunnable = null

        if (overlayVisible) {
            EventLog.record("show IGNORED (already visible)")
            return
        }

        val coldAttach = neverShown
        pendingShowStart = entryMillis
        pendingShowSource = source

        composeView.visibility = View.VISIBLE
        layoutParams.flags = SHOWN_FLAGS
        windowManager.updateViewLayout(composeView, layoutParams)
        overlayVisible = true

        onNextFrameCommitted {
            val elapsed = SystemClock.uptimeMillis() - pendingShowStart
            Latency.record(
                ShowSample(
                    source = pendingShowSource,
                    millis = elapsed,
                    coldAttach = coldAttach,
                )
            )
            Log.i(
                TAG,
                "show: ${elapsed}ms  source=$source" +
                    if (coldAttach) "  (first show — attach included, ignore this one)" else "",
            )

            // Refreshed after the window is already on screen, never before it. Recency needs to be
            // current, but querying a month of usage stats costs tens of milliseconds and must not
            // land on the show path.
            lifecycleScope.launch {
                runCatching { UsageRepository.refresh(this@OverlayService) }
                // Picks up a permission granted since warm-up, and any contacts added since.
                if (ContactsRepository.contacts.isEmpty()) {
                    runCatching { ContactsRepository.load(this@OverlayService) }
                    runCatching { ContactsRepository.loadActionIcons(this@OverlayService) }
                }
            }
        }
        neverShown = false
    }

    private fun hide() {
        if (!overlayVisible) return
        EventLog.record("hide")
        overlayVisible = false

        // Drop input focus immediately so the app underneath becomes interactive again without
        // waiting out the exit animation.
        layoutParams.flags = HIDDEN_FLAGS
        if (attached) windowManager.updateViewLayout(composeView, layoutParams)

        val runnable = Runnable {
            if (!overlayVisible && attached) {
                composeView.visibility = View.GONE
                // Nothing is visible now, so clearing the query and any open menu costs no frames.
                resetToken++
            }
            hideRunnable = null
        }
        hideRunnable = runnable
        composeView.postDelayed(runnable, EXIT_ANIMATION_MILLIS)
    }

    /**
     * Fires once, after the next frame is actually committed to the display — as close to
     * "the user can see it" as we can get without a system trace.
     *
     * The ViewTreeObserver must never be cached. Before the view is attached, [View.getViewTreeObserver]
     * hands back a floating observer that ViewRootImpl merges and then kills on attach; touching that
     * stale reference afterwards throws "ViewTreeObserver is not alive". windowManager.addView does
     * not attach synchronously, so registering straight after it hits exactly that case.
     *
     * This is instrumentation, so it degrades to "no measurement" rather than taking the service down.
     */
    private fun onNextFrameCommitted(block: () -> Unit) {
        val view = composeView

        val register = Runnable {
            val observer = view.viewTreeObserver
            if (!observer.isAlive) {
                Log.w(TAG, "observer not alive at registration; skipping measurement")
                return@Runnable
            }
            var callback: Runnable? = null
            callback = Runnable {
                block()
                // Re-fetch rather than closing over `observer`: it may have been replaced since.
                view.viewTreeObserver
                    .takeIf { it.isAlive }
                    ?.let { live -> callback?.let(live::unregisterFrameCommitCallback) }
            }
            runCatching { observer.registerFrameCommitCallback(callback) }
                .onFailure { Log.w(TAG, "could not register frame callback", it) }
        }

        // View.post before attachment queues the action to run once the view is attached, which is
        // the earliest point a real, live observer exists.
        if (view.isAttachedToWindow) register.run() else view.post(register)
    }

    // ---- foreground plumbing ----------------------------------------------------------------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Search overlay",
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = "Keeps the search window warm for instant access"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            // Read off the app name rather than written out, so a debug build's ongoing
            // notification says which build it belongs to. Both can run at once.
            .setContentTitle("${getString(R.string.app_name)} is ready")
            .setContentText("Search window kept warm")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    /**
     * Re-pushes the search bar widget when the system theme flips.
     *
     * An adaptive bar resolves its colour against night mode at draw time, so it needs redrawing to
     * follow a light/dark switch — and ACTION_CONFIGURATION_CHANGED cannot be manifest-registered on
     * modern Android, so a widget provider can't hear it on its own. This service is resident to keep
     * the overlay warm, which makes it the one component that does hear it.
     *
     * If the service isn't running the bar keeps its previous colours until the next update. That is
     * the accepted edge; the alternative is a receiver that can't be registered.
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        runCatching { SearchWidgetProvider.refreshAll(this) }
            .onFailure { EventLog.record("widget refresh on config change FAILED ${it.javaClass.simpleName}") }
        runCatching { SearchWidgetFinderProvider.refreshAll(this) }
            .onFailure { EventLog.record("finder refresh on config change FAILED ${it.javaClass.simpleName}") }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(systemUiReceiver) }
        runCatching {
            getSystemService(LauncherApps::class.java)?.unregisterCallback(packageCallback)
        }
        hideRunnable?.let { composeView.removeCallbacks(it) }
        if (attached) {
            try {
                windowManager.removeView(composeView)
            } catch (e: Exception) {
                Log.w(TAG, "removeView on destroy failed", e)
            }
            attached = false
        }
        viewModelStore.clear()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }
}

/** Not focusable, not touchable — invisible to input while the window sits warm in the background. */
private const val HIDDEN_FLAGS =
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE

/**
 * Focusable so the IME and Back reach us; NOT_TOUCH_MODAL so touches outside still land.
 *
 * FLAG_LAYOUT_NO_LIMITS is deliberately absent: without it the system lays the window out inside
 * the system bar insets, so the card sits below the status bar without needing inset dispatches,
 * which overlay windows don't deliver reliably.
 */
private const val SHOWN_FLAGS =
    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
