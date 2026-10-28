@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package org.aralozkaya.msngr

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.aralozkaya.msngr.pages.AboutSettingsScreen
import org.aralozkaya.msngr.pages.OpenSourceLicensesScreen
import org.aralozkaya.msngr.ui.theme.MsngrTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class OpenSourceLicensesInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun aboutOpensCompleteOfflineNoticesWithoutJavascriptOrFileAccess() {
        val opened = mutableStateOf(false)
        compose.setContent {
            MsngrTheme {
                if (opened.value) OpenSourceLicensesScreen(PaddingValues())
                else AboutSettingsScreen(PaddingValues(), TopAppBarDefaults.pinnedScrollBehavior(), { opened.value = true })
            }
        }
        compose.onNodeWithText("Open-source licenses").performClick()
        fun findWeb(view: View): WebView? {
            if (view is WebView) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) findWeb(view.getChildAt(index))?.let { return it }
            return null
        }
        var web: WebView? = null
        compose.waitUntil(15_000) {
            var ready = false
            compose.runOnIdle {
                web = findWeb(compose.activity.window.decorView)
                ready = web != null && web!!.progress == 100
            }
            ready
        }
        val matches = AtomicInteger(-1)
        compose.runOnIdle {
            assertFalse(web!!.settings.javaScriptEnabled)
            assertFalse(web!!.settings.allowFileAccess)
            assertFalse(web!!.settings.allowContentAccess)
            assertEquals("file:///android_asset/licenses/index.html", web!!.url)
            web!!.setFindListener { _, count, done -> if (done) matches.set(count) }
            web!!.findAllAsync("libsiren")
        }
        compose.waitUntil(5_000) { matches.get() >= 0 }
        assertTrue("Local notices must be rendered and searchable", matches.get() > 0)
    }
}
