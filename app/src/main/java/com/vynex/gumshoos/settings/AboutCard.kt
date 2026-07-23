package com.vynex.gumshoos.settings

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.vynex.gumshoos.BuildConfig
import com.vynex.gumshoos.EventLog
import com.vynex.gumshoos.R
import com.vynex.gumshoos.UpdateChecker

/**
 * The card at the foot of the settings root: what this build is, who made it, and the two ways out
 * to the project on GitHub.
 *
 * Unintrusive by design — the update check surfaces as a small dot on the Releases button, never a
 * dialog or a banner. If GitHub can't be reached nothing appears at all, which is the correct
 * resting state and identical to being up to date. See [UpdateChecker].
 */
@Composable
internal fun AboutCard() {
    val context = LocalContext.current

    // The debug build carries a "-debug" version suffix purely to sit beside a release copy; it is
    // not a version anyone releases, so it is dropped for both the readout and the comparison.
    val installed = remember { BuildConfig.VERSION_NAME.substringBefore('-') }

    LaunchedEffect(installed) { UpdateChecker.check(installed) }

    val updateAvailable = UpdateChecker.updateAvailable

    SettingsGroup {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SettingsInset, vertical = 14.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_provider_hat_glasses),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 16.dp).size(24.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Version $installed · Made by ${UpdateChecker.AUTHOR}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SettingsGroupDivider()

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SettingsInset, vertical = 12.dp),
        ) {
            LinkButton(
                label = "Source",
                icon = R.drawable.ic_provider_folder_git,
                onClick = { openUrl(context, UpdateChecker.REPO_URL) },
                modifier = Modifier.weight(1f),
            )
            LinkButton(
                label = "Releases",
                icon = R.drawable.ic_provider_gallery_horizontal,
                // With an update live, land straight on the new release rather than the list — the
                // badge said there's something new, so this takes you to it.
                onClick = {
                    val url = if (updateAvailable) {
                        UpdateChecker.LATEST_RELEASE_URL
                    } else {
                        UpdateChecker.RELEASES_URL
                    }
                    openUrl(context, url)
                },
                modifier = Modifier.weight(1f),
                showBadge = updateAvailable,
            )
        }
    }
}

/**
 * An outlined button that leaves the app, marked with its own leading glyph.
 *
 * [showBadge] draws a small accent dot after the label — an inline mark rather than the stock
 * `BadgedBox`, whose default offset anchors to an icon-sized child and floats disproportionately
 * over a full-width button. A dot inside the content scales with nothing and sits where it's put.
 */
@Composable
private fun LinkButton(
    label: String,
    @DrawableRes icon: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showBadge: Boolean = false,
) {
    OutlinedButton(onClick = onClick, modifier = modifier) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(label)
        if (showBadge) {
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

/**
 * Hands a web address to whatever opens links, recording a failure rather than swallowing it.
 *
 * The `https` scheme is declared in the manifest's `<queries>`, so a browser resolves; the catch is
 * for the phone that has none, which is the one case this cannot assume away.
 */
private fun openUrl(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, url.toUri())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
        .onFailure {
            EventLog.record("open url FAILED $url")
            Toast.makeText(context, "No app to open links", Toast.LENGTH_SHORT).show()
        }
}
