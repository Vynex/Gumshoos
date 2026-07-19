package com.vynex.gumshoos.settings

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vynex.gumshoos.answers.AnswerContextFactory
import com.vynex.gumshoos.answers.AnswerFormat
import com.vynex.gumshoos.answers.CurrencyRates
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.vynex.gumshoos.ContactAction
import com.vynex.gumshoos.ContactsRepository
import com.vynex.gumshoos.CrashLog
import com.vynex.gumshoos.EventLog
import com.vynex.gumshoos.GumshoosConfig
import com.vynex.gumshoos.LaunchHistory
import com.vynex.gumshoos.OverlayService
import com.vynex.gumshoos.R
import com.vynex.gumshoos.SearchShortcuts
import com.vynex.gumshoos.SearchWidgetSmallProvider
import com.vynex.gumshoos.UsageRepository
import com.vynex.gumshoos.UsageSource

/**
 * The individual settings controls, moved out of MainActivity unchanged when the screen was split
 * into categories. Grouping them was the work; the controls themselves already behaved.
 */

@Composable
internal fun EventTrace() {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var trace by remember { mutableStateOf(EventLog.read()) }
    var expanded by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { trace = EventLog.read(); expanded = true }) {
                Text(if (expanded) "Refresh trace" else "Show event trace")
            }
            if (expanded) {
                OutlinedButton(
                    onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(trace)) },
                ) { Text("Copy") }
                OutlinedButton(onClick = { EventLog.clear(); trace = "" }) { Text("Clear") }
            }
        }

        if (expanded) {
            Text(
                text = trace.ifBlank { "No events recorded yet." },
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontSize = 11.sp,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .verticalScroll(rememberScrollState()),
            )
        }
    }
}

/**
 * Which signal orders the Frequent and Recent tabs.
 *
 * The two answer subtly different questions. System usage measures foreground time across the whole
 * phone, so something left open in the background outranks what you actually reach for. Gumshoos'
 * own history counts launches made here — narrower, but a truer picture of how the launcher is
 * used, and it needs no permission at all.
 */
@Composable
internal fun UsageSourcePicker() {
    val context = LocalContext.current
    var source by remember { mutableStateOf(GumshoosConfig.usageSource) }
    val tracked = LaunchHistory.stats.size

    Column {
        UsageSourceRow(
            label = "System-wide usage",
            description = "How much every app is used across the phone. Needs usage access below.",
            selected = source == UsageSource.SYSTEM,
        ) {
            source = UsageSource.SYSTEM
            GumshoosConfig.setUsageSource(context, UsageSource.SYSTEM)
        }
        UsageSourceRow(
            label = "Launches from Gumshoos only",
            description = if (tracked == 0) {
                "Counts only apps opened from this search window. No permission needed; " +
                    "nothing recorded yet, so ordering stays alphabetical until you open something."
            } else {
                "Counts only apps opened from this search window. No permission needed; " +
                    "$tracked app${if (tracked == 1) "" else "s"} recorded so far."
            },
            selected = source == UsageSource.GUMSHOOS,
        ) {
            source = UsageSource.GUMSHOOS
            GumshoosConfig.setUsageSource(context, UsageSource.GUMSHOOS)
        }

        if (source == UsageSource.GUMSHOOS && tracked > 0) {
            OutlinedButton(
                onClick = { LaunchHistory.clear(context) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Reset launch history") }
        }
    }
}

@Composable
internal fun UsageSourceRow(
    label: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(4.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Which app the contact row's email action uses.
 *
 * Worth choosing explicitly rather than resolving: with several mail apps installed and none set as
 * the system default, Android hands back its chooser activity, whose icon is a generic cogwheel.
 */
@Composable
internal fun EmailAppPicker() {
    val context = LocalContext.current
    var handlers by remember { mutableStateOf<List<ContactsRepository.ActionHandler>>(emptyList()) }
    val selected = GumshoosConfig.contactActionPackages[ContactAction.EMAIL]

    LaunchedEffect(Unit) {
        handlers = ContactsRepository.handlersFor(context, ContactAction.EMAIL)
    }

    fun choose(packageName: String?) {
        GumshoosConfig.setContactActionPackage(context, ContactAction.EMAIL, packageName)
        // Re-warm so the overlay picks up the new icon without waiting for a restart.
        context.startForegroundService(OverlayService.intent(context, OverlayService.ACTION_WARM))
    }

    Spacer(Modifier.height(4.dp))
    Text("Email app", style = MaterialTheme.typography.titleSmall)
    Text(
        "Used for the mail action on a contact.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    EmailAppRow(
        label = "Automatic",
        icon = null,
        selected = selected == null,
        onClick = { choose(null) },
    )
    handlers.forEach { handler ->
        EmailAppRow(
            label = handler.label,
            icon = handler.icon,
            selected = selected == handler.packageName,
            onClick = { choose(handler.packageName) },
        )
    }
    if (handlers.isEmpty()) {
        Text(
            "No mail apps found.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun EmailAppRow(
    label: String,
    icon: androidx.compose.ui.graphics.ImageBitmap?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(4.dp))
        if (icon != null) {
            androidx.compose.foundation.Image(
                bitmap = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(10.dp))
        }
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Icons that open search directly. Both go through a trampoline activity, because a shortcut cannot
 * start a service — only a widget can. Expect them to measure slower than the widget for that
 * reason; the overlay's readout tags this path "icon".
 */
/**
 * Exchange rate status, and a way to refresh without waiting for the automatic one.
 *
 * Rates refresh at warm time at most once every 20 hours, which is right for a background job and
 * wrong when you want a current number now.
 */
@Composable
internal fun CurrencyRatesSettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val table = CurrencyRates.table
    var lastAttemptFailed by remember { mutableStateOf(false) }

    SettingsSection("Exchange rates")
    Text(
        when {
            table == null -> "No rates yet. Conversions stay hidden until some are fetched — an " +
                "invented exchange rate would be worse than no answer."
            else -> "${table.rates.size} currencies, based on ${table.base}. Updated " +
                AnswerFormat.relativeTime(table.fetchedAtMillis, System.currentTimeMillis()) + "."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (lastAttemptFailed) {
        Text(
            "Couldn't reach the rate service. Any cached rates are still in use.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }

    OutlinedButton(
        onClick = {
            lastAttemptFailed = false
            scope.launch {
                val base = AnswerContextFactory.forDevice(context).currencyCode
                lastAttemptFailed = !CurrencyRates.refresh(context, base, force = true)
            }
        },
        enabled = !CurrencyRates.refreshing,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(if (CurrencyRates.refreshing) "Refreshing…" else "Refresh rates now") }
}

@Composable
internal fun SearchIconSettings() {
    val context = LocalContext.current
    var drawerIcon by remember { mutableStateOf(SearchShortcuts.isDrawerIconEnabled(context)) }

    SettingsSection("Search icons")
    Text(
        "A shortcut can only launch an activity, so these pass through a hidden one on the way to " +
            "the search window. The widget reaches it directly and should still be quicker — " +
            "compare the millisecond readout under the search bar.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                drawerIcon = !drawerIcon
                SearchShortcuts.setDrawerIconEnabled(context, drawerIcon)
            },
    ) {
        Checkbox(
            checked = drawerIcon,
            onCheckedChange = {
                drawerIcon = it
                SearchShortcuts.setDrawerIconEnabled(context, it)
            },
        )
        Spacer(Modifier.width(4.dp))
        Column {
            Text("Second icon in the app drawer", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Adds a separate \"Gumshoos Search\" entry that opens the search window, alongside " +
                    "the normal icon that opens this screen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    OutlinedButton(
        onClick = { SearchShortcuts.requestPin(context) },
        enabled = SearchShortcuts.canPin(context),
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Pin a search icon to the home screen") }
}

@Composable
internal fun WidgetTuning() {
    val context = LocalContext.current
    var size by remember { mutableStateOf(GumshoosConfig.widgetIconSizeDp.toFloat()) }
    var offset by remember { mutableStateOf(GumshoosConfig.widgetIconOffsetDp.toFloat()) }

    SettingsSection("1x1 widget")
    Text(
        "Adjust, then press Home to see the result — placed widgets update immediately. Offset 0 " +
            "centres the icon in its cell; positive moves it down.",
        style = MaterialTheme.typography.bodySmall,
    )

    Text("Icon size: ${size.roundToInt()}dp", style = MaterialTheme.typography.bodyMedium)
    Slider(
        value = size,
        onValueChange = { size = it },
        onValueChangeFinished = {
            GumshoosConfig.setWidgetIconSizeDp(context, size.roundToInt())
            SearchWidgetSmallProvider.refreshAll(context)
        },
        valueRange = 32f..64f,
        steps = 31,
        modifier = Modifier.fillMaxWidth(),
    )

    Text("Vertical offset: ${offset.roundToInt()}dp", style = MaterialTheme.typography.bodyMedium)
    Slider(
        value = offset,
        onValueChange = { offset = it },
        onValueChangeFinished = {
            GumshoosConfig.setWidgetIconOffsetDp(context, offset.roundToInt())
            SearchWidgetSmallProvider.refreshAll(context)
        },
        valueRange = -16f..16f,
        steps = 31,
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedButton(
        onClick = {
            size = GumshoosConfig.DEFAULT_WIDGET_ICON_SIZE_DP.toFloat()
            offset = GumshoosConfig.DEFAULT_WIDGET_ICON_OFFSET_DP.toFloat()
            GumshoosConfig.setWidgetIconSizeDp(context, size.roundToInt())
            GumshoosConfig.setWidgetIconOffsetDp(context, offset.roundToInt())
            SearchWidgetSmallProvider.refreshAll(context)
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Reset to defaults") }
}

@Composable
internal fun CrashReport() {
    val context = LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var crash by remember { mutableStateOf(CrashLog.read(context)) }
    val trace = crash ?: return

    androidx.compose.material3.Card(
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Last crash", style = MaterialTheme.typography.titleMedium)
            Text(
                text = trace,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontSize = 11.sp,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(trace))
                    },
                ) { Text("Copy") }
                OutlinedButton(
                    onClick = {
                        CrashLog.clear(context)
                        crash = null
                    },
                ) { Text("Clear") }
            }
        }
    }
}
