@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package org.aralozkaya.msngr.pages.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

@Composable
internal fun LoadingButtonContent(
    loading: Boolean,
    label: String,
    modifier: Modifier = Modifier,
    indicatorModifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    var actionSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    // Keep the label's space so loading does not resize or recolor the button.
    val loadingSize = if (loading && actionSize != IntSize.Zero) {
        with(density) { Modifier.size(actionSize.width.toDp(), actionSize.height.toDp()) }
    } else Modifier
    Box(
        modifier = modifier.then(loadingSize).onSizeChanged { if (!loading) actionSize = it },
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            val progressModifier = indicatorModifier.semantics {
                contentDescription = label
                liveRegion = LiveRegionMode.Polite
            }
            CircularWavyProgressIndicator(
                modifier = progressModifier.size(20.dp),
                color = LocalContentColor.current,
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, content = content)
        }
    }
}
