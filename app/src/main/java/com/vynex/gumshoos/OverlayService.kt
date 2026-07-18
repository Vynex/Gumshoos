package com.vynex.gumshoos

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.IBinder
import android.os.SystemClock
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
     * Screen-off only. Home and Recents detection was removed deliberately: neither is delivered to
     * apps as a key event, and the available proxies (ACTION_CLOSE_SYSTEM_DIALOGS, window focus
     * loss) both arrive late enough that dismissal felt sluggish rather than snappy.
     */
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                EventLog.record("screen off")
                hide()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        ContextCompat.registerReceiver(
            this,
            screenOffReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_EXPORTED,
        )

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
         * It cannot distinguish *what* took over: a notification tap, the shade opening and Home
         * all look the same from here. The Home gesture reports late, which is why it felt sluggish
         * before; that is the cost of covering the rest.
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
            .setContentTitle("Gumshoos is ready")
            .setContentText("Search window kept warm")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenOffReceiver) }
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
