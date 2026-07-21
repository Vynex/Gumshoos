package com.vynex.gumshoos.settings

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vynex.gumshoos.EventLog
import com.vynex.gumshoos.LaunchCandidate
import com.vynex.gumshoos.WidgetActionTargets
import com.vynex.gumshoos.widget.WidgetAction

/**
 * Fires one entry point at a time, so the one that actually draws can be found by watching.
 *
 * The probe answers "what exists and where does an intent resolve". It cannot answer the question
 * that has cost this project four iterations on Lens — *does anything appear* — because a component
 * that starts and immediately finishes returns success and is indistinguishable from a working
 * launch through every API we have. The only instrument for that is a pair of eyes, and the only
 * thing missing was a way to point them at one rung at a time.
 *
 * Before this, each candidate cost a build, a sideload, a tap and a trace read, one candidate per
 * cycle. The list is [WidgetActionTargets.candidates], the same list the shipped chain filters, so
 * a rung tested here is the rung that ships rather than a copy of it that has drifted.
 */
@Composable
internal fun EntryPointsScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    SettingsScaffold(title = "Entry point tester", onBack = onBack) {
        SettingsNote(
            "Tap a row to fire exactly that intent, then watch the screen. Nothing here reports " +
                "whether it worked — a component that opens and closes itself again looks like a " +
                "success from this side, which is the whole reason this screen exists. Every tap " +
                "is written to the event trace either way, so a session can be read back.",
        )
        SettingsNote(
            "Rows marked \"in chain\" are what the widget icon tries, in this order. The rest are " +
                "candidates the probe turned up that nothing has yet been observed doing.",
        )

        WidgetAction.entries.forEach { action ->
            SettingsDivider()
            SettingsSection(action.label)

            // Resolution is read per row, so the list is rebuilt on every recomposition rather than
            // remembered: returning from a launch is exactly when a rung's availability may have
            // changed, and a stale row would be reporting on the state before the tap.
            val candidates = WidgetActionTargets.candidates(context, action)
            val chain = WidgetActionTargets.chain(context, action).map(WidgetActionTargets::describe)
            candidates.forEachIndexed { index, candidate ->
                val at = candidate.intent
                    ?.let { chain.indexOf(WidgetActionTargets.describe(it)) }
                    ?.takeIf { it >= 0 }
                CandidateRow(action, candidate, chainAt = at?.plus(1))
                if (index < candidates.lastIndex) SettingsGroupDivider()
            }
        }
    }
}

@Composable
private fun CandidateRow(action: WidgetAction, candidate: LaunchCandidate, chainAt: Int?) {
    val context = LocalContext.current
    val intent = candidate.intent

    // Where it lands, read once. Worth showing next to the row: a rung whose resolution has moved
    // since the probe was taken is the first thing to notice, and it costs one PackageManager call.
    val resolved = remember(intent) {
        intent?.let {
            runCatching { context.packageManager.resolveActivity(it, 0) }.getOrNull()
                ?.activityInfo?.name?.substringAfterLast('.')
        }
    }

    val status = when {
        intent == null -> "not available on this device"
        resolved == null -> "resolves to nothing"
        chainAt != null -> "in chain, rung $chainAt · $resolved"
        else -> "not in chain · $resolved"
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fullBleedRow()
            .clickable(enabled = intent != null && resolved != null) { fire(context, action, candidate) }
            .fillMaxWidth()
            .padding(horizontal = SettingsInset, vertical = 12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                candidate.label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (intent != null && resolved != null) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                candidate.note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                status,
                style = MaterialTheme.typography.labelSmall,
                color = if (chainAt != null) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

/**
 * Starts one candidate and records the attempt.
 *
 * A copy of the intent, never the candidate's own: [WidgetActionTargets] hands out fresh instances
 * per call, but adding NEW_TASK to something a caller might hold is the kind of shared mutation that
 * is invisible until the second tap behaves differently from the first.
 *
 * "started" is deliberately not "worked". A throw is the only outcome this can report honestly, and
 * it is worth reporting loudly — a SecurityException from a non-exported component is a *clean*
 * negative, unlike every silent success above it.
 */
private fun fire(context: Context, action: WidgetAction, candidate: LaunchCandidate) {
    val intent = candidate.intent ?: return
    val label = WidgetActionTargets.describe(intent)
    runCatching { context.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onSuccess { EventLog.record("entry-point test ${action.id} started [$label]") }
        .onFailure {
            EventLog.record("entry-point test ${action.id} THREW ${it.javaClass.simpleName} [$label]")
            Toast.makeText(context, it.javaClass.simpleName, Toast.LENGTH_SHORT).show()
        }
}
