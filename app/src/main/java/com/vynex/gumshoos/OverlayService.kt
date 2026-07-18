package com.vynex.gumshoos

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

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

        /** Must match the exit animation in [OverlayContent] or the view vanishes mid-fade. */
        const val EXIT_ANIMATION_MILLIS = 160L

        fun intent(context: Context, action: String, source: String? = null): Intent =
            Intent(context, OverlayService::class.java).apply {
                this.action = action
                source?.let { putExtra(EXTRA_SOURCE, it) }
            }
    }

    private lateinit var windowManager: WindowManager
    private lateinit var composeView: ComposeView
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

    private var strategy = PrewarmStrategy.PREDRAWN
    private var pendingShowStart = 0L
    private var pendingShowSource = "unknown"
    private var hideRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        strategy = Prefs.prewarmStrategy(this)

        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)

        createNotificationChannel()
        buildOverlayView()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        // Unconditionally, and first. The old code only promoted to foreground in the `else`
        // branch, so a cold start carrying ACTION_SHOW never called this and the system killed us
        // with ForegroundServiceDidNotStartInTimeException.
        startForeground(NOTIFICATION_ID, buildNotification())

        // Captured as early as possible: everything after this is work we're trying to measure.
        val entry = SystemClock.uptimeMillis()

        when (intent?.action) {
            ACTION_SHOW -> show(entry, intent.getStringExtra(EXTRA_SOURCE) ?: "unknown")
            ACTION_HIDE -> hide()
            else -> attachIfNeeded()
        }
        return START_STICKY
    }

    // ---- window lifecycle -------------------------------------------------------------------

    private fun buildOverlayView() {
        composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@OverlayService)
            setViewTreeSavedStateRegistryOwner(this@OverlayService)
            setViewTreeViewModelStoreOwner(this@OverlayService)
            setViewTreeOnBackPressedDispatcherOwner(this@OverlayService)
            setContent {
                OverlayContent(
                    visible = overlayVisible,
                    onDismiss = { hide() },
                )
            }
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

        // While hidden the window must not take input focus, or it would steal keystrokes from
        // whatever app is actually in front.
        composeView.visibility = if (strategy == PrewarmStrategy.PREDRAWN) View.VISIBLE else View.GONE
        composeView.alpha = if (strategy == PrewarmStrategy.PREDRAWN) 0f else 1f

        try {
            windowManager.addView(composeView, layoutParams)
            attached = true
        } catch (e: Exception) {
            Log.e(TAG, "attach failed — overlay permission missing?", e)
            return
        }

        if (strategy == PrewarmStrategy.PREDRAWN) {
            // Let exactly one real frame go through measure/layout/draw, then hide. The hypothesis
            // is that this makes the first *visible* frame cheaper than the GONE path.
            onNextFrameCommitted {
                composeView.visibility = View.GONE
                composeView.alpha = 1f
                Log.i(TAG, "prewarm: first frame committed, view now hidden")
            }
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

        if (overlayVisible) return

        val coldAttach = neverShown
        pendingShowStart = entryMillis
        pendingShowSource = source

        composeView.visibility = View.VISIBLE
        layoutParams.flags = SHOWN_FLAGS
        windowManager.updateViewLayout(composeView, layoutParams)
        overlayVisible = true

        onNextFrameCommitted {
            val elapsed = SystemClock.uptimeMillis() - pendingShowStart
            val sample = ShowSample(
                source = pendingShowSource,
                strategy = strategy,
                millis = elapsed,
                coldAttach = coldAttach,
            )
            Latency.record(sample)
            Log.i(
                TAG,
                "show: ${elapsed}ms  source=$source  strategy=$strategy" +
                    if (coldAttach) "  (first show — attach included, ignore this one)" else "",
            )
        }
        neverShown = false
    }

    private fun hide() {
        if (!overlayVisible) return
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
     */
    private fun onNextFrameCommitted(block: () -> Unit) {
        val observer = composeView.viewTreeObserver
        var callback: Runnable? = null
        callback = Runnable {
            block()
            callback?.let { observer.unregisterFrameCommitCallback(it) }
        }
        observer.registerFrameCommitCallback(callback)
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
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

/** Focusable so the IME and Back reach us; NOT_TOUCH_MODAL so touches outside still land. */
private const val SHOWN_FLAGS =
    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
