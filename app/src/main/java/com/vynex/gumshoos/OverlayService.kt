package com.vynex.gumshoos

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

class OverlayService : LifecycleService(), SavedStateRegistryOwner {
    companion object {
        const val ACTION_SHOW_OVERLAY = "com.vynex.gumshoos.SHOW_OVERLAY"
        const val ACTION_HIDE_OVERLAY = "com.vynex.gumshoos.HIDE_OVERLAY"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "gumshoos_overlay_channel"
    }

    private lateinit var windowManager: WindowManager
    private lateinit var composeView: ComposeView
    private var isOverlayVisible = false

    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override fun onCreate() {
        super.onCreate()

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)

        createNotificationChannel()
        createOverlayView()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_SHOW_OVERLAY -> {
                if (!isOverlayVisible) {
                    showOverlay()
                }
            }
            ACTION_HIDE_OVERLAY -> {
                if (isOverlayVisible) {
                    hideOverlay()
                }
            }
            else -> {
                // First time service start - start foreground service
                startForeground(NOTIFICATION_ID, createNotification())
                if (intent?.action == ACTION_SHOW_OVERLAY) {
                    showOverlay()
                }
            }
        }
        return START_STICKY // Keep service running
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Gumshoos Search Overlay",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps search overlay ready for instant access"
            setShowBadge(false)
        }
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Gumshoos Search")
            .setContentText("Tap app icon for instant search")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun createOverlayView() {
        composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@OverlayService)
            setViewTreeSavedStateRegistryOwner(this@OverlayService)
            setContent {
                AnimatedFloatingSearchOverlay(
                    isVisible = isOverlayVisible,
                    onClose = { hideOverlay() }
                )
            }
        }
//        composeView = ComposeView(this).apply {
//            setViewTreeLifecycleOwner(this@OverlayService)
//            setViewTreeSavedStateRegistryOwner(this@OverlayService)
//            setContent {
//                FloatingSearchOverlay(onClose = { hideOverlay() })
//            }
//        }
    }

    private fun showOverlay() {
        if (isOverlayVisible) return

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
//            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        )

        layoutParams.gravity = Gravity.TOP

        try {
            windowManager.addView(composeView, layoutParams)
            isOverlayVisible = true
        } catch (e: Exception) {
            // Handle permission issues gracefully
            isOverlayVisible = false
        }
    }

    private fun hideOverlay() {
        if (!isOverlayVisible) return
        isOverlayVisible = false

        // Delay removing the view to allow fade-out animation
        composeView.postDelayed({
            try {
                if (!isOverlayVisible) { // Double-check we're still meant to be hidden
                    windowManager.removeView(composeView)
                }
            } catch (e: Exception) {
                // View might already be removed
            }
        }, 300) // Match the animation duration

//        try {
//            windowManager.removeView(composeView)
//        } catch (e: Exception) {
//            // View might already be removed
//        }
//        isOverlayVisible = false
//        // Don't stop the service, keep it running for next overlay show
    }

    @Composable
    private fun AnimatedFloatingSearchOverlay(
        isVisible: Boolean,
        onClose: () -> Unit
    ) {
        var searchQuery by remember { mutableStateOf("") }
        val focusRequester = remember { FocusRequester() }
        val keyboardController = LocalSoftwareKeyboardController.current
        val focusManager = LocalFocusManager.current

        // Animation states
        val backgroundAlpha by animateFloatAsState(
            targetValue = if (isVisible) 0.5f else 0f,
            animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
            label = "background_alpha"
        )

        val searchBarAlpha by animateFloatAsState(
            targetValue = if (isVisible) 1f else 0f,
            animationSpec = tween(
                durationMillis = 300,
                delayMillis = if (isVisible) 100 else 0, // Slight delay on show
                easing = FastOutSlowInEasing
            ),
            label = "searchbar_alpha"
        )

        val searchBarScale by animateFloatAsState(
            targetValue = if (isVisible) 1f else 0.95f,
            animationSpec = tween(
                durationMillis = 300,
                delayMillis = if (isVisible) 100 else 0,
                easing = FastOutSlowInEasing
            ),
            label = "searchbar_scale"
        )

        // Results container animation - appears after search bar
        val resultsHeight by animateDpAsState(
            targetValue = if (isVisible && searchQuery.isNotEmpty()) 200.dp else 0.dp,
            animationSpec = tween(
                durationMillis = 250,
                delayMillis = if (isVisible) 400 else 0, // Delay for expansion effect
                easing = FastOutSlowInEasing
            ),
            label = "results_height"
        )

        val resultsAlpha by animateFloatAsState(
            targetValue = if (isVisible && searchQuery.isNotEmpty()) 1f else 0f,
            animationSpec = tween(
                durationMillis = 200,
                delayMillis = if (isVisible) 450 else 0,
                easing = FastOutSlowInEasing
            ),
            label = "results_alpha"
        )

        // Handle focus and keyboard when visible changes
        LaunchedEffect(isVisible) {
            if (isVisible) {
                searchQuery = ""
                focusRequester.requestFocus()
                keyboardController?.show()
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = backgroundAlpha))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) {
                    if (isVisible) onClose()
                }
                .onKeyEvent { keyEvent ->
                    if (keyEvent.key == Key.Escape || keyEvent.key == Key.Back) {
                        if (keyEvent.type == KeyEventType.KeyUp && isVisible) {
                            onClose()
                        }
                        true
                    } else {
                        false
                    }
                }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(top = 120.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Search Bar
                AnimatedVisibility(
                    visible = isVisible,
                    enter = fadeIn(
                        animationSpec = tween(600, delayMillis = 100, easing = FastOutSlowInEasing)
                    ) + scaleIn(
                        animationSpec = tween(600, delayMillis = 100, easing = FastOutSlowInEasing),
                        initialScale = 0.95f
                    ),
                    exit = fadeOut(
                        animationSpec = tween(250, easing = FastOutSlowInEasing)
                    ) + scaleOut(
                        animationSpec = tween(250, easing = FastOutSlowInEasing),
                        targetScale = 0.95f
                    )
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) { /* Prevent click through */ },
                        shape = RoundedCornerShape(16.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp)
                                .focusRequester(focusRequester)
                                .onKeyEvent { keyEvent ->
                                    if (keyEvent.key == Key.Escape || keyEvent.key == Key.Back) {
                                        if (keyEvent.type == KeyEventType.KeyUp) {
                                            onClose()
                                        }
                                        true
                                    } else {
                                        false
                                    }
                                },
                            textStyle = TextStyle(
                                fontSize = 18.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            keyboardOptions = KeyboardOptions(
                                imeAction = ImeAction.Search
                            ),
                            keyboardActions = KeyboardActions(
                                onSearch = {
                                    focusManager.clearFocus()
                                }
                            ),
                            singleLine = true,
                            decorationBox = { innerTextField ->
                                if (searchQuery.isEmpty()) {
                                    Text(
                                        "Search apps, contacts, and more...",
                                        style = TextStyle(
                                            fontSize = 18.sp,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                        )
                                    )
                                }
                                innerTextField()
                            }
                        )
                    }
                }

                // Results Container - Expands when there's a search query
                AnimatedVisibility(
                    visible = isVisible && searchQuery.isNotEmpty(),
                    enter = fadeIn(
                        animationSpec = tween(200, delayMillis = 450, easing = FastOutSlowInEasing)
                    ) + expandVertically(
                        animationSpec = tween(250, delayMillis = 400, easing = FastOutSlowInEasing)
                    ),
                    exit = fadeOut(
                        animationSpec = tween(150, easing = FastOutSlowInEasing)
                    ) + shrinkVertically(
                        animationSpec = tween(200, easing = FastOutSlowInEasing)
                    )
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .height(200.dp) // About 2 rows of apps
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) { /* Prevent click through */ },
                        shape = RoundedCornerShape(16.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        // Placeholder for search results (replace with your actual results)
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "Search results for: \"$searchQuery\"",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Results will appear here...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isOverlayVisible) {
            hideOverlay()
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }
}