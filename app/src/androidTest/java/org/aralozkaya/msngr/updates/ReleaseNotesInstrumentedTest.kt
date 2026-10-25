package org.aralozkaya.msngr.updates

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import org.aralozkaya.msngr.pages.components.ReleaseNotes
import org.aralozkaya.msngr.ui.theme.MsngrTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ReleaseNotesInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun githubMarkdownRendersHeadingsInlineFormattingListsCodeQuotesAndTables() {
        compose.setContent {
            MsngrTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ReleaseNotes("""
                        # Release highlights

                        **Bold** and *italic* and ~~removed~~ and `Msngr-release.apk`.

                        - First fix
                          - Nested fix

                        1. Download
                        2. Install

                        > Restart Msngr.

                        ```text
                        Saved history
                        ```

                        | Feature | Status |
                        | --- | --- |
                        | Updater | Fixed |

                        - [x] Ready
                        - [ ] Planned
                    """.trimIndent())
                }
            }
        }
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithText("Release highlights").assertExists() }.isSuccess }
        val paragraph = compose.onNodeWithText("Bold and italic and removed and", substring = true, useUnmergedTree = true)
            .fetchSemanticsNode().config[SemanticsProperties.Text].single()
        assertTrue(paragraph.text, listOf("**", "~~", "`").none { it in paragraph.text })
        fun spansFor(word: String) = paragraph.spanStyles.filter {
            paragraph.text.indexOf(word) in it.start until it.end
        }.map { it.item }
        assertTrue(spansFor("Bold").any { it.fontWeight == FontWeight.Bold })
        assertTrue(spansFor("italic").any { it.fontStyle == FontStyle.Italic })
        assertTrue(spansFor("removed").any { it.textDecoration?.contains(TextDecoration.LineThrough) == true })
        assertTrue(spansFor("Msngr-release.apk").any { it.fontFamily == FontFamily.Monospace })
        listOf("First fix", "Nested fix", "Download", "Install", "Restart Msngr.",
            "Saved history", "Feature", "Status", "Updater", "Fixed", "Ready", "Planned").forEach {
            compose.onNodeWithText(it, substring = true).assertExists()
        }
        compose.onNodeWithText("# Release highlights").assertDoesNotExist()
    }

    @Test fun releaseNoteLinksOpenTheirDestination() {
        var openedUrl: String? = null
        val destination = "$RELEASES_PAGE/tag/v2.0.0"
        compose.setContent {
            CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                override fun openUri(uri: String) { openedUrl = uri }
            }) {
                MsngrTheme { ReleaseNotes("[Full changelog]($destination)") }
            }
        }
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithText("Full changelog").assertExists() }.isSuccess }
        val link = compose.onNode(hasText("Full changelog") and
            SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
        var layout: TextLayoutResult? = null
        link.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
            val results = mutableListOf<TextLayoutResult>()
            check(action(results))
            layout = results.single()
        }
        link.performTouchInput { click(checkNotNull(layout).getBoundingBox(1).center) }
        compose.runOnIdle { assertEquals(destination, openedUrl) }
    }

    @Test fun emptyReleaseNotesKeepTheirFallbackMessage() {
        compose.setContent { MsngrTheme { ReleaseNotes(" \n\t") } }
        compose.onNodeWithText("No release notes were provided.").assertExists()
    }
}
