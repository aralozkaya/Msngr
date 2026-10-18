package org.aralozkaya.msngr

import android.graphics.Bitmap
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.UnderlineSpan
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessengerPlusRenderingInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun nativeEditorUsesTheVerifiedColorsAndDecorations() {
        val source = "[a=#123456][u][s][c=#000000]AA[b]BB[/b]CC[/c=#FFFFFF][/s][/u][/a]"
        compose.setContent {
            MaterialTheme {
                MsnEmoticonEditor(source, {}, false, false)
            }
        }
        onView(withContentDescription("Message input")).check { view, error ->
            if (error != null) throw error
            val text = (view as EditText).text
            val starts = listOf(source.indexOf("AA"), source.indexOf("BB"), source.indexOf("CC"))
                .flatMap { listOf(it, it + 1) }
            val expected = listOf(0, 21, 106, 127, 233, 255)
            starts.forEachIndexed { index, offset ->
                assertEquals(expected[index], text.getSpans(offset, offset + 1, ForegroundColorSpan::class.java)
                    .single().foregroundColor and 255)
                assertEquals(0xFF123456.toInt(), text.getSpans(offset, offset + 1, BackgroundColorSpan::class.java)
                    .single().backgroundColor)
                assertTrue(text.getSpans(offset, offset + 1, UnderlineSpan::class.java).isNotEmpty())
                assertTrue(text.getSpans(offset, offset + 1, StrikethroughSpan::class.java).isNotEmpty())
            }
        }
    }

    @Test
    fun rendersTheNativeReferenceExamples() {
        val samples = listOf(
            "Styles" to "[b]Bold[/b] [i]Italic[/i] [u]Underline[/u] [s]Strike[/s]",
            "Native foreground gradient" to "[c=4]Messenger Plus![/c=12]",
            "Native background gradient" to "[a=8]Messenger Plus![/a=9]",
            "Nested gradients" to "[a=18][c=4]A [b]bold[/b] gradient[/c=12][/a=23]",
            "Nested colors restore the outer color" to "[c=4]Red [c=12]Blue[/c] Red[/c] Default",
            "Legacy nickname controls" to "·#Bold·# ·&Italic·& ·@Underline·@ ·'Strike·'",
            "Extended palette and custom backgrounds" to "[c=28]Coral[/c] [c=49]Mint[/c] [a=#123456][c=0] Custom [/c][/a]",
            "Quoted and unmatched tags remain visible" to "[n][b]Literal[/b][/n] [i]Unclosed",
            "Scene-adaptive color" to "[c=adapt]Uses the app theme[/c]",
        )
        compose.setContent {
            MaterialTheme {
                Surface {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Messenger Plus compatibility", style = MaterialTheme.typography.titleLarge)
                        samples.forEach { (label, source) ->
                            Column {
                                Text(label, style = MaterialTheme.typography.labelMedium)
                                MsnEmoticonText(source, false, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 20.sp))
                            }
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("Red Blue Red Default").assertExists()
        compose.onNodeWithText("[b]Literal[/b] [i]Unclosed").assertExists()
        val screenshot = compose.onRoot().captureToImage().asAndroidBitmap()
        val file = File(compose.activity.getExternalFilesDir(null), "messenger-plus-reference.png")
        file.outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
