package com.vynex.gumshoos

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vynex.gumshoos.ui.theme.GumshoosTheme
import kotlinx.coroutines.launch
import kotlin.math.pow
import kotlin.math.roundToInt

// --- Geometry ---------------------------------------------------------------------------------
// Measured frame-by-frame from a slowed capture of Nova, converted at density 2.75
// (1080px wide -> 393dp). Reference: card 1037x813px, side margin 21px, top 141px, header 125px.

private val CardCornerRadius = 20.dp
private val CardSideMargin = 8.dp
private val CardTopMargin = 12.dp
private val HeaderHeight = 45.dp
private val TabRowHeight = 48.dp
private val CellHeight = 88.dp
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

/** Half the collapsed height, so phase one is a true pill rather than a rounded rectangle. */
private val PillCornerRadius = HeaderHeight / 2

/** Measured 793/1037 at the first frame the card is detectable. */
private const val CollapsedWidthFraction = 0.765f

private const val CardSurfaceAlpha = 0.94f

// --- Timing ------------------------------------------------------------------------------------
// Every leg below is expressed as a percentage of the entrance, measured from the capture. Only
// EnterTotalMillis carries the absolute scale, so if the capture's animator duration scale was not
// 10x, changing that one number rescales the whole sequence coherently.

private const val EnterTotalMillis = 355

// The two phases are strictly sequential: the pill finishes widening before the card begins to
// grow downward. The capture actually overlapped them slightly (height began at 25.9% while width
// ran to 33.6%), but a clean handoff reads better, so height now starts exactly where width ends.
private const val OpacityStartPct = 0.000f
private const val OpacityEndPct = 0.217f
private const val WidthStartPct = 0.000f
private const val WidthEndPct = 0.340f
private const val HeightStartPct = 0.340f
private const val HeightEndPct = 1.000f

private fun durationOf(startPct: Float, endPct: Float) =
    ((endPct - startPct) * EnterTotalMillis).roundToInt()

private fun delayOf(startPct: Float) = (startPct * EnterTotalMillis).roundToInt()

/**
 * The exit is not a reversed entrance. Width, height and opacity all collapse together and
 * proportionally, toward a pivot inside the header rather than the card's centre — the card falls
 * back into the search bar it grew out of.
 */
// Measured at 106ms in the capture; deliberately slower than that by preference.
private const val ExitMillis = 150
private const val ExitAlphaMillis = 135
private const val ExitEndScale = 0.13f

/** Pivot sits ~8.5% down the card, i.e. mid-header, so the top edge drifts down as it collapses. */
private val ExitTopDrift = 25.dp

const val TotalExitMillis = ExitMillis

// --- Easings, fitted against the capture --------------------------------------------------------

/** Opacity accelerates: t^1.5 fitted at rmse 0.021. Notably NOT a decelerate. */
private val AccelerateEasing = Easing { fraction ->
    fraction.toDouble().pow(1.5).toFloat()
}

/** Height follows a gentle symmetric S; smoothstep fitted at rmse 0.031. */
private val SmoothStepEasing = Easing { t -> t * t * (3f - 2f * t) }

/** Width is essentially linear (rmse 0.040 against linear, 0.165 against decelerate). */
private val WidthEnterEasing = LinearEasing

/** The collapse decelerates; 1-(1-t)^2 fitted at rmse 0.027. */
private val ExitEasing = LinearOutSlowInEasing

@Composable
fun OverlayContent(
    visible: Boolean,
    onDismiss: () -> Unit,
) {
    GumshoosTheme(darkTheme = true, dynamicColor = true) {
        val transition = updateTransition(targetState = visible, label = "overlay")

        // Entrance is staged: opacity and width run together up front, then the card grows
        // downward for the remaining three quarters of the timeline. Exit ignores that staging
        // entirely and collapses everything at once.
        // The hidden state is asymmetric: the entrance begins at 76.5% but the exit collapses all
        // the way to 13%. Keyframes pin the entrance's true starting width, which the tween would
        // otherwise inherit from wherever the exit left it. The jump happens while alpha is still
        // ~0, so it is invisible.
        val widthFraction by transition.animateFloat(
            transitionSpec = {
                if (targetState) {
                    keyframes {
                        durationMillis =
                            delayOf(WidthStartPct) + durationOf(WidthStartPct, WidthEndPct)
                        CollapsedWidthFraction at 0 using WidthEnterEasing
                        CollapsedWidthFraction at delayOf(WidthStartPct) using WidthEnterEasing
                        1f at durationMillis
                    }
                } else {
                    tween(ExitMillis, easing = ExitEasing)
                }
            },
            label = "width",
        ) { shown -> if (shown) 1f else ExitEndScale }

        val cardAlpha by transition.animateFloat(
            transitionSpec = {
                if (targetState) {
                    tween(
                        durationMillis = durationOf(OpacityStartPct, OpacityEndPct),
                        easing = AccelerateEasing,
                    )
                } else {
                    tween(ExitAlphaMillis, easing = ExitEasing)
                }
            },
            label = "alpha",
        ) { shown -> if (shown) 1f else 0f }

        val cardHeight by transition.animateDp(
            transitionSpec = {
                if (targetState) {
                    keyframes {
                        durationMillis =
                            delayOf(HeightStartPct) + durationOf(HeightStartPct, HeightEndPct)
                        CardCollapsedHeight at 0 using SmoothStepEasing
                        CardCollapsedHeight at delayOf(HeightStartPct) using SmoothStepEasing
                        CardExpandedHeight at durationMillis
                    }
                } else {
                    tween(ExitMillis, easing = ExitEasing)
                }
            },
            label = "height",
        ) { shown -> if (shown) CardExpandedHeight else CardExpandedHeight * ExitEndScale }

        // Stays a pill for the whole horizontal phase, then relaxes into the card's radius as it
        // grows. Morphing it earlier would blur the boundary between the two phases.
        val cornerRadius by transition.animateDp(
            transitionSpec = {
                if (targetState) {
                    keyframes {
                        durationMillis =
                            delayOf(HeightStartPct) + durationOf(HeightStartPct, HeightEndPct)
                        PillCornerRadius at 0 using SmoothStepEasing
                        PillCornerRadius at delayOf(HeightStartPct) using SmoothStepEasing
                        CardCornerRadius at durationMillis
                    }
                } else {
                    tween(ExitMillis, easing = ExitEasing)
                }
            },
            label = "corner",
        ) { shown -> if (shown) CardCornerRadius else PillCornerRadius }

        // Only meaningful on exit: the collapse pivots inside the header, so the top edge falls.
        // Snapped to zero on enter, where the card is strictly top-anchored.
        val topDrift by transition.animateDp(
            transitionSpec = {
                if (targetState) tween(1) else tween(ExitMillis, easing = ExitEasing)
            },
            label = "topDrift",
        ) { shown -> if (shown) 0.dp else ExitTopDrift }

        BackHandler(enabled = visible) { onDismiss() }

        // No scrim: the reference leaves the wallpaper at full brightness.
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                ) { onDismiss() },
        ) {
            val settledCardWidth = maxWidth - (CardSideMargin * 2)

            Surface(
                shape = RoundedCornerShape(cornerRadius),
                color = MaterialTheme.colorScheme.surface.copy(alpha = CardSurfaceAlpha),
                shadowElevation = 12.dp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = CardTopMargin + topDrift)
                    .width(settledCardWidth * widthFraction)
                    .height(cardHeight)
                    .alpha(cardAlpha)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                    ) { /* swallow taps so they don't reach the dismiss layer */ },
            ) {
                // Width is a real layout change, matching the reference: the search glyph tracks
                // the card's edge rather than sliding under a scaled transform. That is affordable
                // only because the expensive subtree below is pinned — see SearchPanel.
                SearchPanel(
                    visible = visible,
                    onDismiss = onDismiss,
                    pinnedWidth = settledCardWidth,
                )
            }
        }
    }
}

@Composable
private fun SearchPanel(visible: Boolean, onDismiss: () -> Unit, pinnedWidth: Dp) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()

    val pagerState = rememberPagerState(
        initialPage = SearchTab.FREQUENT.ordinal,
        pageCount = { SearchTab.entries.size },
    )
    val tab = SearchTab.entries[pagerState.currentPage]

    LaunchedEffect(visible) {
        if (visible) {
            query = ""
            pagerState.scrollToPage(SearchTab.FREQUENT.ordinal)
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }

    val launch: (AppEntry) -> Unit = { entry ->
        AppRepository.launch(context, entry)
        onDismiss()
    }

    // Enter/Search on the keyboard takes the top-ranked result of whichever tab is showing.
    val submit: () -> Unit = {
        AppRepository.search(query, SearchTab.entries[pagerState.currentPage])
            .firstOrNull()
            ?.let(launch)
    }

    // requiredHeight pins the whole panel at full height so the card's shrinking height clips it
    // rather than squeezing it — that is what makes the grid look already-rendered as the card
    // grows. The header is left to fill the card's animating width, matching the reference.
    Column(Modifier.requiredHeight(CardExpandedHeight)) {
        SearchHeader(
            query = query,
            onQueryChange = { query = it },
            focusRequester = focusRequester,
            onSubmit = submit,
        )

        // Pinned to the settled width so the width animation never re-measures the tab row or the
        // grid — ten two-line labels re-running text layout every frame is what made the earlier
        // version stutter. Only the cheap header re-flows.
        Column(Modifier.requiredWidth(pinnedWidth)) {
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
                    onClick = {
                        scope.launch { pagerState.animateScrollToPage(entry.ordinal) }
                    },
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

        // Each tab is a page, so the grid can be swiped as well as tapped. Pages are pinned to the
        // card width; the pager itself is what makes tab and swipe share one source of truth.
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.requiredWidth(pinnedWidth),
            pageSize = PageSize.Fill,
            beyondViewportPageCount = 1,
        ) { page ->
            val pageTab = SearchTab.entries[page]
            AppGrid(
                entries = AppRepository.search(query, pageTab),
                emptyMessage = emptyMessageFor(pageTab),
                onLaunch = launch,
                modifier = Modifier.requiredWidth(pinnedWidth),
            )
        }
        }
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
    onSubmit: () -> Unit,
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
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(
                onGo = { onSubmit() },
                onSearch = { onSubmit() },
                onDone = { onSubmit() },
            ),
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
    modifier: Modifier = Modifier,
) {
    if (entries.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
        modifier = modifier
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
