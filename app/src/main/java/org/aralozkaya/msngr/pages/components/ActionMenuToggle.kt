@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package org.aralozkaya.msngr.pages.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.ToggleFloatingActionButtonDefaults
import androidx.compose.material3.ToggleFloatingActionButtonDefaults.animateIcon
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.traversalIndex
import org.aralozkaya.msngr.R

@Composable
internal fun ActionMenuToggle(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    val menuState = stringResource(
        if (expanded) R.string.contacts_actions_expanded else R.string.contacts_actions_collapsed,
    )
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
            if (expanded) TooltipAnchorPosition.Start else TooltipAnchorPosition.Above,
        ),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        ToggleFloatingActionButton(
            checked = expanded,
            onCheckedChange = onExpandedChange,
            containerCornerRadius = ToggleFloatingActionButtonDefaults.containerCornerRadius(),
            modifier = modifier.semantics {
                contentDescription = label
                stateDescription = menuState
                traversalIndex = -1f
            },
        ) {
            Icon(
                imageVector = if (checkedProgress > 0.5f) Icons.Filled.Close else icon,
                contentDescription = null,
                modifier = Modifier.animateIcon({ checkedProgress }),
            )
        }
    }
}
