package org.aralozkaya.msngr.pages.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.style.TextDecoration
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.rememberMarkdownState
import org.aralozkaya.msngr.R

@Composable
internal fun ReleaseNotes(notes: String, modifier: Modifier = Modifier) {
    SelectionContainer(modifier) {
        if (notes.isBlank()) {
            Text(stringResource(R.string.update_no_changelog))
        } else {
            Markdown(
                markdownState = rememberMarkdownState(notes),
                modifier = Modifier.fillMaxWidth(),
                colors = markdownColor(text = LocalContentColor.current),
                typography = markdownTypography(
                    h1 = MaterialTheme.typography.headlineSmall,
                    h2 = MaterialTheme.typography.titleLarge,
                    h3 = MaterialTheme.typography.titleMedium,
                    h4 = MaterialTheme.typography.titleSmall,
                    h5 = MaterialTheme.typography.labelLarge,
                    h6 = MaterialTheme.typography.labelMedium,
                    textLink = TextLinkStyles(SpanStyle(
                        color = MaterialTheme.colorScheme.primary,
                        textDecoration = TextDecoration.Underline,
                    )),
                ),
                error = { Text(notes) },
            )
        }
    }
}
