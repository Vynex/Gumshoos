package com.vynex.gumshoos

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vynex.gumshoos.ui.theme.GumshoosTheme

// --- Geometry. Tuned against the Nova reference; all cheap to adjust. --------------------------

private val CardCornerRadius = 24.dp
private val CardSideMargin = 12.dp
private val HeaderHeight = 58.dp
private val TabRowHeight = 48.dp
private val CellHeight = 92.dp
private val GridVerticalPadding = 10.dp
private val GridRowSpacing = 8.dp

private const val GridColumns = 5
private const val GridRows = 2
const val MaxGridEntries = GridColumns * GridRows

/** Derived rather than hardcoded so changing a row height can't desync the animation target. */
private val CardExpandedHeight =
    HeaderHeight + TabRowHeight + (GridVerticalPadding * 2) +
        (CellHeight * GridRows) + GridRowSpacing

/** The collapsed state is exactly the header — the search bar the animation starts from. */
private val CardCollapsedHeight = HeaderHeight

/** Starting width of the entrance, as a fraction of the settled width. */
private const val CollapsedWidthFraction = 0.88f

private const val CardSurfaceAlpha = 0.94f

// --- Timing ------------------------------------------------------------------------------------

private const val EnterWidthMillis = 130
private const val EnterAlphaMillis = 110
private const val EnterHeightMillis = 200
private const val EnterHeightDelayMillis = 70

private const val ExitWidthMillis = 120
private const val ExitAlphaMillis = 140
private const val ExitHeightMillis = 150

/** Longest exit leg; [OverlayService.EXIT_ANIMATION_MILLIS] must not undercut this. */
const val TotalExitMillis = 140

@Composable
fun OverlayContent(
    visible: Boolean,
    onDismiss: () -> Unit,
) {
    GumshoosTheme(darkTheme = true, dynamicColor = true) {
        val transition = updateTransition(targetState = visible, label = "overlay")

        // Three staged legs, matching the reference: the bar widens and fades in first, then the
        // card grows downward. Height lags slightly so the widening reads as a separate beat.
        val widthFraction by transition.animateFloat(
            transitionSpec = {
                if (targetState) tween(EnterWidthMillis, easing = LinearOutSlowInEasing)
                else tween(ExitWidthMillis, easing = FastOutLinearInEasing)
            },
            label = "width",
        ) { shown -> if (shown) 1f else CollapsedWidthFraction }

        val cardAlpha by transition.animateFloat(
            transitionSpec = {
                if (targetState) tween(EnterAlphaMillis, easing = LinearOutSlowInEasing)
                else tween(ExitAlphaMillis, easing = FastOutLinearInEasing)
            },
            label = "alpha",
        ) { shown -> if (shown) 1f else 0f }

        val cardHeight by transition.animateDp(
            transitionSpec = {
                if (targetState) {
                    tween(
                        durationMillis = EnterHeightMillis,
                        delayMillis = EnterHeightDelayMillis,
                        easing = LinearOutSlowInEasing,
                    )
                } else {
                    tween(ExitHeightMillis, easing = FastOutLinearInEasing)
                }
            },
            label = "height",
        ) { shown -> if (shown) CardExpandedHeight else CardCollapsedHeight }

        BackHandler(enabled = visible) { onDismiss() }

        // No scrim: the reference leaves the wallpaper at full brightness.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                ) { onDismiss() },
        ) {
            Surface(
                shape = RoundedCornerShape(CardCornerRadius),
                color = MaterialTheme.colorScheme.surface.copy(alpha = CardSurfaceAlpha),
                shadowElevation = 12.dp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(horizontal = CardSideMargin, vertical = 8.dp)
                    .fillMaxWidth()
                    .height(cardHeight)
                    // Width and alpha run in the draw phase, not layout. fillMaxWidth(fraction)
                    // would change the width constraint every frame, remeasuring the grid and
                    // re-running text line-breaking for all ten labels — far too expensive for a
                    // 8ms budget at 120Hz. As a graphicsLayer transform it costs the GPU nothing.
                    .graphicsLayer {
                        scaleX = widthFraction
                        alpha = cardAlpha
                        transformOrigin = TransformOrigin(0.5f, 0f)
                    }
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                    ) { /* swallow taps so they don't reach the dismiss layer */ },
            ) {
                // requiredHeight ignores the shrinking constraint, so the content stays laid out at
                // full size and Surface's shape clip reveals it. That's what makes the grid appear
                // already-rendered as the card grows, rather than fading in behind it.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .requiredHeight(CardExpandedHeight),
                    contentAlignment = Alignment.TopStart,
                ) {
                    SearchPanel(visible = visible, onDismiss = onDismiss)
                }
            }
        }
    }
}

@Composable
private fun SearchPanel(visible: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf(SearchTab.FREQUENT) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(visible) {
        if (visible) {
            query = ""
            tab = SearchTab.FREQUENT
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }

    val results = AppRepository.search(query, tab)

    Column(Modifier.fillMaxSize()) {
        SearchHeader(
            query = query,
            onQueryChange = { query = it },
            focusRequester = focusRequester,
        )

        TabRow(
            selectedTabIndex = tab.ordinal,
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.height(TabRowHeight),
            indicator = { positions ->
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(positions[tab.ordinal]),
                    height = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            },
            divider = {},
        ) {
            SearchTab.entries.forEach { entry ->
                Tab(
                    selected = tab == entry,
                    onClick = { tab = entry },
                    text = {
                        Text(
                            text = entry.label,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 0.6.sp,
                            maxLines = 1,
                        )
                    },
                    selectedContentColor = MaterialTheme.colorScheme.onSurface,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

        AppGrid(
            entries = results,
            emptyMessage = emptyMessageFor(tab),
            onLaunch = { entry ->
                AppRepository.launch(context, entry)
                onDismiss()
            },
        )
    }
}

private fun emptyMessageFor(tab: SearchTab): String = when {
    !AppRepository.loaded -> "Loading apps…"
    tab == SearchTab.NEW_UPDATED ->
        "Nothing installed or updated in the last ${GumshoosConfig.newUpdatedWindowHours}h"
    else -> "No matches"
}

@Composable
private fun SearchHeader(
    query: String,
    onQueryChange: (String) -> Unit,
    focusRequester: FocusRequester,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(HeaderHeight)
            .padding(horizontal = 16.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(16.dp))

        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester),
            textStyle = TextStyle(
                fontSize = 17.sp,
                color = MaterialTheme.colorScheme.onSurface,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            singleLine = true,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { inner ->
                if (query.isEmpty()) {
                    Text(
                        text = "Search apps",
                        fontSize = 17.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                inner()
            },
        )

        Spacer(Modifier.width(12.dp))
        Icon(
            imageVector = Icons.Default.ShoppingCart,
            contentDescription = "Search the Play Store",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * Fixed two rows, never scrollable. A fixed row count is also what lets the card have a known
 * expanded height, which the reveal animation depends on.
 */
@Composable
private fun AppGrid(
    entries: List<AppEntry>,
    emptyMessage: String,
    onLaunch: (AppEntry) -> Unit,
) {
    if (entries.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = emptyMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = GridVerticalPadding),
        verticalArrangement = Arrangement.spacedBy(GridRowSpacing),
    ) {
        entries.take(MaxGridEntries).chunked(GridColumns).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { entry ->
                    AppCell(
                        entry = entry,
                        onClick = { onLaunch(entry) },
                        modifier = Modifier.weight(1f),
                    )
                }
                // Keep a short final row left-aligned instead of stretching its cells.
                repeat(GridColumns - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun AppCell(
    entry: AppEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .height(CellHeight)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            )
            .padding(vertical = 4.dp, horizontal = 2.dp),
    ) {
        Image(
            bitmap = entry.icon,
            contentDescription = entry.label,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = entry.label,
            fontSize = 12.sp,
            lineHeight = 14.sp,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
