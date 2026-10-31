@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package org.aralozkaya.msngr.pages.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Keeps dependent settings on the same expanding surface as their controlling row. */
@Composable
internal fun ExpandableSettingsGroup(
    expanded: Boolean,
    highlighted: Boolean = expanded,
    master: @Composable (ListItemShapes) -> Unit,
    content: @Composable (ListItemShapes, ListItemColors) -> Unit,
) {
    Column {
        val shapes = ListItemDefaults.segmentedShapes(0, if (expanded) 2 else 1)
        master(shapes.copy(selectedShape = shapes.shape))
        if (expanded) Spacer(Modifier.height(ListItemDefaults.SegmentedGap))
        AnimatedVisibility(visible = expanded) {
            val childShapes = ListItemDefaults.segmentedShapes(1, 2)
            val colors = ListItemDefaults.segmentedColors()
            content(childShapes.copy(selectedShape = childShapes.shape), if (highlighted) colors.copy(
                containerColor = colors.selectedContainerColor,
                contentColor = colors.selectedContentColor,
            ) else colors.copy(
                selectedContainerColor = colors.containerColor,
                selectedContentColor = colors.contentColor,
            ))
        }
    }
}
