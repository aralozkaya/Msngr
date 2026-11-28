@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package org.aralozkaya.msngr.pages.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.aralozkaya.msngr.PortalWebTarget
import org.aralozkaya.msngr.R
import org.aralozkaya.msngr.data.MsgrClientConfig
import org.aralozkaya.msngr.rememberRemoteImagePainter

@Composable
fun PortalSitesActionMenu(
    config: MsgrClientConfig,
    onOpenWebTarget: (PortalWebTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val todayUrl = config.todayUrl
    if (todayUrl == null && config.tabs.isEmpty()) return

    var expanded by rememberSaveable { mutableStateOf(false) }
    val toggleLabel = stringResource(
        if (expanded) R.string.portal_sites_close else R.string.portal_sites_open,
    )
    val todayTitle = stringResource(R.string.portal_today_title)

    BackHandler(enabled = expanded) { expanded = false }

    FloatingActionButtonMenu(
        expanded = expanded,
        modifier = modifier.testTag("portal_sites_fab_menu"),
        button = {
            ActionMenuToggle(
                expanded = expanded,
                onExpandedChange = { expanded = it },
                label = toggleLabel,
                icon = Icons.Filled.Explore,
                modifier = Modifier.testTag("portal_sites_fab"),
            )
        },
    ) {
        if (todayUrl != null) {
            FloatingActionButtonMenuItem(
                onClick = {
                    expanded = false
                    onOpenWebTarget(PortalWebTarget(title = todayTitle, url = todayUrl))
                },
                modifier = Modifier.testTag("portal_site_today"),
                text = { Text(todayTitle) },
                icon = { Icon(Icons.Outlined.Today, contentDescription = null) },
            )
        }
        config.tabs.forEach { site ->
            val iconPainter = rememberRemoteImagePainter(site.iconUrl.ifBlank { null })
            FloatingActionButtonMenuItem(
                onClick = {
                    expanded = false
                    onOpenWebTarget(PortalWebTarget(title = site.name, url = site.contentUrl))
                },
                modifier = Modifier.testTag("portal_site_${site.id}"),
                text = { Text(site.name) },
                icon = {
                    if (iconPainter != null) {
                        Image(
                            painter = iconPainter,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.size(24.dp),
                        )
                    } else {
                        Icon(Icons.Default.Public, contentDescription = null)
                    }
                },
            )
        }
    }
}
