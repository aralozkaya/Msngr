package org.aralozkaya.msngr.pages.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

private data class ActionSnackbarVisuals(
    override val message: String,
    val isError: Boolean,
) : SnackbarVisuals {
    override val actionLabel: String? = null
    override val withDismissAction = true
    override val duration = SnackbarDuration.Short
}

internal suspend fun SnackbarHostState.showActionSnackbar(message: String, isError: Boolean = false) {
    currentSnackbarData?.dismiss()
    showSnackbar(ActionSnackbarVisuals(message, isError))
}

@Composable
internal fun ActionSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(hostState, modifier) { data ->
        val isError = (data.visuals as? ActionSnackbarVisuals)?.isError == true
        val colors = MaterialTheme.colorScheme
        Snackbar(
            snackbarData = data,
            modifier = Modifier.testTag(if (isError) "action-error-snackbar" else "action-snackbar"),
            containerColor = if (isError) colors.errorContainer else colors.inverseSurface,
            contentColor = if (isError) colors.onErrorContainer else colors.inverseOnSurface,
            dismissActionContentColor = if (isError) colors.onErrorContainer else colors.inverseOnSurface,
        )
    }
}
