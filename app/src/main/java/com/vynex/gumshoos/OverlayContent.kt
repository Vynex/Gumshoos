package com.vynex.gumshoos

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vynex.gumshoos.ui.theme.GumshoosTheme

/**
 * Intentionally thin. The POC is measuring how fast a window can appear, so the content is kept
 * cheap — anything expensive here would measure Compose, not the window path.
 */
@Composable
fun OverlayContent(
    visible: Boolean,
    onDismiss: () -> Unit,
) {
    GumshoosTheme {
        // FastOutSlowIn eases in *and* out, which reads as hesitation on something that is supposed
        // to feel like it was already there. Entrances want deceleration: full speed immediately,
        // settling at the end. Alpha is near-instant; the scale spring carries the motion.
        val scrimAlpha by animateFloatAsState(
            targetValue = if (visible) 0.45f else 0f,
            animationSpec = tween(if (visible) 90 else 110, easing = LinearOutSlowInEasing),
            label = "scrim",
        )
        val cardAlpha by animateFloatAsState(
            targetValue = if (visible) 1f else 0f,
            animationSpec = tween(if (visible) 70 else 90, easing = LinearOutSlowInEasing),
            label = "cardAlpha",
        )
        val cardScale by animateFloatAsState(
            targetValue = if (visible) 1f else 0.96f,
            animationSpec = spring(
                dampingRatio = 1f, // critically damped: fast, no overshoot wobble
                stiffness = Spring.StiffnessMedium,
                visibilityThreshold = 0.001f,
            ),
            label = "cardScale",
        )

        BackHandler(enabled = visible) { onDismiss() }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = scrimAlpha))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                ) { onDismiss() },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(top = 110.dp)
                    .alpha(cardAlpha)
                    .scale(cardScale),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                SearchCard(visible = visible)
                LatencyReadout()
            }
        }
    }
}

@Composable
private fun SearchCard(visible: Boolean) {
    var query by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // Focus and IME are requested after the window is already on screen — deliberately not on the
    // critical path, since keyboard latency is accepted and shouldn't hold up first paint.
    LaunchedEffect(visible) {
        if (visible) {
            query = ""
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ) { /* swallow taps so they don't reach the dismiss scrim */ },
    ) {
        BasicTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
                .focusRequester(focusRequester),
            textStyle = TextStyle(
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onSurface,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            singleLine = true,
            cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { inner ->
                if (query.isEmpty()) {
                    Text(
                        text = "Search",
                        style = TextStyle(
                            fontSize = 18.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        ),
                    )
                }
                inner()
            },
        )
    }
}

/** On-device readout so the POC can be judged by tapping, not by reading a trace. */
@Composable
private fun LatencyReadout() {
    val sample = Latency.last ?: return
    val median = Latency.medianMillis()

    Text(
        text = buildString {
            append("${sample.millis} ms")
            if (median != null && Latency.count() > 1) append("   ·   median ${median} ms")
            append("   ·   ${sample.source}")
            if (sample.coldAttach) append("   ·   FIRST SHOW, ignore")
        },
        style = MaterialTheme.typography.labelMedium,
        color = Color.White.copy(alpha = 0.85f),
        modifier = Modifier.padding(top = 12.dp),
    )
}
