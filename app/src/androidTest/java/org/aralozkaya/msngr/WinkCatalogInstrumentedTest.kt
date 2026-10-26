package org.aralozkaya.msngr

import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/** Opt-in network test; downloads an Escargot archive and never messages another account. */
class WinkCatalogInstrumentedTest {
    @org.junit.After fun clearTransientTestSession() { SessionPrefs.logout() }
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun translatesTheRealCatalogInstallerAndImportsItsOriginalCab() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("winkCatalogInterop") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        SessionPrefs.completeLogin(context, "wink-catalog@example.test", "mock-password", false,
            ProviderOptions.ESCARGOT, ServerOverrides(), true)
        val account = context.winkAccount()!!
        ActivityScenario.launch<WinkCatalogActivity>(Intent(context, WinkCatalogActivity::class.java)).use { scenario ->
            val browser = AtomicReference<WebView>()
            fun find(view: View): WebView? {
                if (view is WebView) return view
                if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
                return null
            }
            compose.waitUntil(10_000) { scenario.onActivity { find(it.window.decorView)?.let(browser::set) }; browser.get() != null }
            scenario.onActivity { browser.get().loadUrl("https://apps.escargot.nina.chat/content/winks/?id=screen-punch&action=click") }
            val translated = AtomicReference<String?>()
            compose.waitUntil(30_000) {
                scenario.onActivity { browser.get().evaluateJavascript("document.title==='Wink - Screen Punch'&&typeof InstallContent==='function'&&InstallContent.toString().includes('msngr-install=')", translated::set) }
                translated.get() == "true"
            }
            scenario.onActivity { browser.get().evaluateJavascript("InstallContent('http://static.levelleap.com/sites/messaging/apps.escargot.nina.chat/winks/files/screen-punch-2.mco')", null) }
            try { compose.waitUntil(30_000) { compose.onAllNodesWithText("Add to my winks").fetchSemanticsNodes().isNotEmpty() } }
            catch (e: Throwable) { throw AssertionError((0 until compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().size).joinToString("\n") { compose.onAllNodes(isRoot(), useUnmergedTree = true)[it].printToString() }, e) }
            compose.onNodeWithText("Screen Punch", substring = false).assertIsDisplayed()
            compose.onNodeWithText("Add to my winks").performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithText("Added 1 wink to your library.").fetchSemanticsNodes().isNotEmpty() }
            val wink = context.winkStore().catalog(account).first { it.name == "Screen Punch" }
            assertTrue(wink.metadata.extraAttributes.getValue("stamp").length > 1000)
            val bytes = context.winkStore().bytes(account, wink.metadata)!!
            assertTrue(wink.metadata.matches(bytes))
            assertNotNull(org.aralozkaya.msngr.protocol.MsnWinkPackage.parse(bytes))
        }
    }
}
