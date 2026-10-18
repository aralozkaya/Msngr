package org.aralozkaya.msngr.pages.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp

@Composable
fun PageContentList(
    modifier: Modifier = Modifier,
    innerPadding: PaddingValues,
    scrollBehavior: TopAppBarScrollBehavior,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(12.dp),
    listState: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    val density = LocalDensity.current
    val bottomObstacle = with(density) {
        WindowInsets.ime.union(keyboardSheetWindowInsets()).getBottom(this).toDp()
    }
    val extraBottom = (bottomObstacle - innerPadding.calculateBottomPadding()).coerceAtLeast(0.dp)
    LazyColumn(
        state = listState,
        modifier = modifier
            // Only page content drives the toolbar.
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .windowInsetsPadding(WindowInsets.safeContent.only(WindowInsetsSides.Horizontal)),
        contentPadding = innerPadding.plus(
            PaddingValues(
                top = 8.dp + 10.dp * (1.0f - scrollBehavior.state.collapsedFraction),
                bottom = 18.dp + extraBottom,
            ),
        ),
        verticalArrangement = verticalArrangement,
        content = content,
    )
}
