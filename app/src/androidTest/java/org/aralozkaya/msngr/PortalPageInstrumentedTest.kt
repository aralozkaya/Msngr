@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package org.aralozkaya.msngr

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeRight
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.aralozkaya.msngr.data.MessengerBannerAd
import org.aralozkaya.msngr.data.MessengerTabSite
import org.aralozkaya.msngr.data.MessengerTextAd
import org.aralozkaya.msngr.data.MockServerData
import org.aralozkaya.msngr.data.MsgrClientConfig
import org.aralozkaya.msngr.data.PortalState
import org.aralozkaya.msngr.data.SignInRequest
import org.aralozkaya.msngr.data.WhatsNewActivity
import org.aralozkaya.msngr.data.WhatsNewKind
import org.aralozkaya.msngr.enums.Presence
import org.aralozkaya.msngr.pages.AppearanceSettingsScreen
import org.aralozkaya.msngr.pages.PortalPage
import org.aralozkaya.msngr.pages.components.PortalSitesActionMenu
import org.aralozkaya.msngr.ui.theme.MsngrTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PortalPageInstrumentedTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null
    private var mockContext: Context? = null

    @After
    fun tearDown() {
        scenario?.close()
        scenario = null
        mockContext?.let { context ->
            runCatching { context.appContainer.messengerRepository.logout() }
            SessionPrefs.disableRememberedCredentials(context)
            SessionPrefs.logout()
        }
        mockContext = null
    }

    private suspend fun launchMockMainActivity(): Context {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        mockContext = context
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }
        runCatching { context.appContainer.messengerRepository.logout() }
        SessionPrefs.disableRememberedCredentials(context)
        SessionPrefs.logout()

        val repository = context.appContainer.repositoryFor(useMockServer = true)
        val serverConfig = ServerPresets.resolveForProvider(ProviderOptions.CROSSTALK, ServerOverrides())
        val serverProfile = repository.signIn(
            SignInRequest(
                email = MockServerData.ACCOUNT_EMAIL,
                password = MockServerData.ACCOUNT_PASSWORD,
                serverConfig = serverConfig,
                initialPresence = Presence.Online,
            ),
        ).getOrThrow()
        SessionPrefs.completeLogin(
            context = context,
            email = MockServerData.ACCOUNT_EMAIL,
            password = MockServerData.ACCOUNT_PASSWORD,
            savePassword = true,
            provider = ProviderOptions.CROSSTALK,
            customServers = ServerOverrides(),
            useMockServer = true,
            automaticSignIn = true,
            loginPresence = Presence.Online,
        )
        SessionPrefs.saveUserProfile(
            context = context,
            username = MockServerData.ACCOUNT_EMAIL.substringBefore('@'),
            presence = Presence.Online,
            statusMessage = MockServerData.ACCOUNT_STATUS_MESSAGE,
        )
        repository.setLocalProfile(
            SessionPrefs.mergeServerProfile(context, serverProfile),
            publishStoredSettings = false,
        )

        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
        compose.waitForIdle()
        return context
    }

    @Test
    fun mainActivityStartsOnContactsAndSwipesRightToPortalPage() {
        runBlocking {
        val context = launchMockMainActivity()

        // Default landing page is Contacts (page 1).
        compose.onNodeWithTag("contacts_search_bar").assertIsDisplayed()
        compose.onNodeWithTag("main_pager_indicator").assertIsDisplayed()
        compose.onNodeWithTag("main_pager_dot_0").assertIsNotSelected()
        compose.onNodeWithTag("main_pager_dot_1").assertIsSelected()

        // Swiping right reveals the Portal page (page 0) with clean empty state in mock mode.
        compose.onNodeWithTag("main_pager").performTouchInput { swipeRight() }
        compose.waitForIdle()

        compose.onNodeWithTag("portal_page").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.portal_whats_new_title)).assertIsDisplayed()
        compose.onNodeWithTag("portal_ad_card").assertDoesNotExist()
        compose.onNodeWithTag("main_pager_dot_0").assertIsSelected()
        compose.onNodeWithTag("main_pager_dot_1").assertIsNotSelected()

        // Tapping the Contacts pagination dot navigates smoothly back to Contacts.
        compose.onNodeWithTag("main_pager_dot_1").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("contacts_search_bar").assertIsDisplayed()
        compose.onNodeWithTag("main_pager_dot_1").assertIsSelected()

        // Swiping right directly on a closed contact row passes through to the page swiper.
        compose.onNode(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and
                hasAnyDescendant(hasTestTag("contacts_search_bar")),
        ).performScrollToNode(hasTestTag("contact-row-selin"))
        compose.onNodeWithTag("contact-row-selin").performTouchInput { swipeRight() }
        compose.waitForIdle()
        compose.onNodeWithTag("portal_page").assertIsDisplayed()
        compose.onNodeWithTag("main_pager_dot_0").assertIsSelected()

        // Tapping Contacts dot returns to Contacts, then indicator auto-hides after idle.
        compose.onNodeWithTag("main_pager_dot_1").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("contacts_search_bar").assertIsDisplayed()
        compose.onNodeWithTag("main_pager_dot_1").assertIsSelected()

        // After movement stops, the launcher pagination indicator auto-hides cleanly.
        compose.waitUntil(timeoutMillis = 3_000) {
            compose.onAllNodes(hasTestTag("main_pager_indicator")).fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("main_pager_indicator").assertDoesNotExist()
        }
    }

    @Test
    fun contactsPageStaysTopAlignedWhenContentDoesNotFillScreen() {
        runBlocking {
            val context = launchMockMainActivity()
            val contactsListMatcher =
                SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and
                    hasAnyDescendant(hasTestTag("contacts_search_bar"))

            val pagerBounds = compose.onNodeWithTag("main_pager").fetchSemanticsNode().boundsInRoot
            val initialListBounds = compose.onNode(contactsListMatcher).fetchSemanticsNode().boundsInRoot
            assertEquals(pagerBounds.top, initialListBounds.top, 1f)
            assertEquals(pagerBounds.height, initialListBounds.height, 1f)

            val profileName = MockServerData.ACCOUNT_EMAIL.substringBefore('@')
            val initialProfileTop = compose.onNodeWithText(profileName).fetchSemanticsNode().boundsInRoot.top
            val initialSearchTop = compose.onNodeWithTag("contacts_search_bar").fetchSemanticsNode().boundsInRoot.top

            compose.onNodeWithText(context.getString(R.string.contacts_section_groups_plural)).performClick()
            compose.waitForIdle()
            compose.onNodeWithText(context.getString(R.string.contacts_section_other))
                .performScrollTo()
                .performClick()
            compose.waitForIdle()

            val collapsedListBounds = compose.onNode(contactsListMatcher).fetchSemanticsNode().boundsInRoot
            val collapsedProfileTop = compose.onNodeWithText(profileName).fetchSemanticsNode().boundsInRoot.top
            val collapsedSearchTop = compose.onNodeWithTag("contacts_search_bar").fetchSemanticsNode().boundsInRoot.top

            assertEquals(pagerBounds.top, collapsedListBounds.top, 1f)
            assertEquals(pagerBounds.height, collapsedListBounds.height, 1f)
            assertEquals(initialProfileTop, collapsedProfileTop, 1f)
            assertEquals(initialSearchTop, collapsedSearchTop, 1f)

            compose.onNodeWithTag("main_pager").performTouchInput { swipeRight() }
            compose.waitForIdle()
            val portalBounds = compose.onNodeWithTag("portal_page").fetchSemanticsNode().boundsInRoot
            assertEquals(pagerBounds.top, portalBounds.top, 1f)
        }
    }

    @Test
    fun appearanceSettingsTogglesAdsAndPortalPageHonorsSetting() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val baseContext = instrumentation.targetContext
        val isolated = object : ContextWrapper(baseContext) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences("portal_test_$name", mode)
        }.also {
            AccountPreferences.preferences(it, "msngr_appearance").edit().clear().commit()
            AppearancePrefs.reload(it)
        }

        assertTrue(AppearancePrefs.state(isolated).value.showAds)

        val settingsRule = androidx.test.core.app.ActivityScenario.launch(androidx.activity.ComponentActivity::class.java)
        try {
            var showSettings by mutableStateOf(true)
            var openedTarget by mutableStateOf<PortalWebTarget?>(null)
            var refreshCount by mutableStateOf(0)
            lateinit var portalListState: LazyListState
            settingsRule.onActivity { activity ->
                activity.setContent {
                    val appearance by AppearancePrefs.state(isolated).collectAsState()
                    CompositionLocalProvider(LocalContext provides isolated) {
                        MsngrTheme {
                            if (showSettings) {
                                AppearanceSettingsScreen(
                                    innerPadding = PaddingValues(),
                                    scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(),
                                )
                            } else {
                                portalListState = androidx.compose.foundation.lazy.rememberLazyListState()
                                PortalPage(
                                    listState = portalListState,
                                    innerPadding = PaddingValues(),
                                    scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(),
                                    portalState = PortalState(
                                        config = MsgrClientConfig(
                                            todayUrl = "https://today.msgrsvcs.ctsrv.xyz/start?wlm=1",
                                            tabs = listOf(
                                                MessengerTabSite(
                                                    id = "1",
                                                    name = "Wiby",
                                                    tooltip = "Wiby",
                                                    contentUrl = "https://wiby.me/",
                                                ),
                                            ),
                                        ),
                                        bannerAd = MessengerBannerAd(
                                            imageUrl = "",
                                            targetUrl = "https://crosstalk.im",
                                        ),
                                        textAd = MessengerTextAd(
                                            text = "CrossTalk Test Ad",
                                            hitUrl = "https://crosstalk.im",
                                        ),
                                        whatsNew = listOf(
                                            WhatsNewActivity(
                                                id = "ann-1",
                                                kind = WhatsNewKind.Announcement,
                                                publisherCid = "0",
                                                publisherName = "CrossTalk 0.6 Long Publisher Name Example",
                                                text = "Server announcement full text that would otherwise be clipped in a compact row https://crosstalk.im/news",
                                                targetUrl = "https://crosstalk.im/profile/+someone",
                                                sourceLabel = "Announcements",
                                                publishedAtMillis = System.currentTimeMillis(),
                                            ),
                                            WhatsNewActivity(
                                                id = "contact-1",
                                                kind = WhatsNewKind.Status,
                                                publisherCid = "100",
                                                publisherName = "Torii",
                                                text = "Outside",
                                                publishedAtMillis = System.currentTimeMillis(),
                                            ),
                                            WhatsNewActivity(
                                                id = "photo-1",
                                                kind = WhatsNewKind.Photo,
                                                publisherCid = "101",
                                                publisherName = "Photo Contact",
                                                text = "Long caption for a shared photo post that should wrap beyond a single line in the feed",
                                                publishedAtMillis = System.currentTimeMillis(),
                                            ),
                                        ),
                                    ),
                                    contacts = emptyList(),
                                    showAds = appearance.showAds,
                                    isMockServer = false,
                                    onRefresh = { refreshCount += 1 },
                                    onOpenWebTarget = { openedTarget = it },
                                    onOpenContactProfile = {},
                                )
                            }
                        }
                    }
                }
            }
            compose.waitForIdle()
            compose.onNode(androidx.compose.ui.test.hasScrollAction())
                .performScrollToNode(hasTestTag("show_ads_setting"))
            compose.onNodeWithTag("show_ads_setting").assertIsOn()
            compose.onNodeWithTag("show_ads_setting").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("show_ads_setting").assertIsOff()
            assertFalse(AppearancePrefs.state(isolated).value.showAds)

            showSettings = false
            compose.waitForIdle()
            compose.onNodeWithTag("portal_ad_card").assertDoesNotExist()
            compose.onNodeWithTag("portal_whats_new_refresh").assertDoesNotExist()
            compose.onNodeWithTag("portal_pull_refresh").assertIsDisplayed()
            compose.onNodeWithTag("portal_page").performTouchInput { swipeDown() }
            compose.waitForIdle()
            assertEquals(1, refreshCount)
            compose.onNodeWithTag("whats_new_avatar_ann-1", useUnmergedTree = true)
                .assertWidthIsEqualTo(44.dp)
                .assertHeightIsEqualTo(44.dp)
            compose.onNodeWithTag("whats_new_avatar_contact-1", useUnmergedTree = true)
                .assertWidthIsEqualTo(44.dp)
                .assertHeightIsEqualTo(44.dp)
            val rowLayoutResults = mutableListOf<TextLayoutResult>()
            compose.onNodeWithTag("whats_new_description_ann-1", useUnmergedTree = true)
                .fetchSemanticsNode()
                .config[SemanticsActions.GetTextLayoutResult]
                .action!!(rowLayoutResults)
            assertEquals(1, rowLayoutResults.single().lineCount)
            assertTrue(rowLayoutResults.single().hasVisualOverflow)
            val photoLayoutResults = mutableListOf<TextLayoutResult>()
            compose.onNodeWithTag("whats_new_description_photo-1", useUnmergedTree = true)
                .fetchSemanticsNode()
                .config[SemanticsActions.GetTextLayoutResult]
                .action!!(photoLayoutResults)
            assertTrue(photoLayoutResults.single().lineCount > 1)
            compose.onNodeWithTag("whats_new_item_ann-1").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("whats_new_detail_dialog").assertIsDisplayed()
            compose.onNode(
                hasAnyAncestor(hasTestTag("whats_new_detail_dialog")) and
                    hasText("CrossTalk 0.6 Long Publisher Name Example"),
            ).assertIsDisplayed()
            compose.onNode(
                hasAnyAncestor(hasTestTag("whats_new_detail_dialog")) and
                    hasText("Server announcement full text that would otherwise be clipped in a compact row https://crosstalk.im/news"),
            ).apply {
                assertIsDisplayed()
                val textValue = fetchSemanticsNode().config[SemanticsProperties.Text].first()
                val linkUrls = textValue.getLinkAnnotations(0, textValue.length)
                    .mapNotNull { (it.item as? LinkAnnotation.Url)?.url }
                assertEquals(listOf("https://crosstalk.im/news"), linkUrls)
                assertEquals(null, openedTarget)
                performFirstLinkClick()
            }
            compose.waitForIdle()
            assertEquals("https://crosstalk.im/news", openedTarget?.url)
            openedTarget = null
            compose.onNodeWithTag("whats_new_detail_close").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("whats_new_detail_dialog").assertDoesNotExist()

            AppearancePrefs.setShowAds(isolated, true)
            compose.waitForIdle()
            assertEquals(0, portalListState.firstVisibleItemIndex)
            assertEquals(0, portalListState.firstVisibleItemScrollOffset)
            compose.onNodeWithTag("portal_ad_card").assertIsDisplayed()
            compose.onNodeWithText("CrossTalk Test Ad").performClick()
            assertEquals("https://crosstalk.im", openedTarget?.url)
        } finally {
            settingsRule.close()
        }
    }

    @Test
    fun portalSitesActionMenuListsTodayAndDynamicTabs() {
        val activityScenario = ActivityScenario.launch(androidx.activity.ComponentActivity::class.java)
        try {
            var openedTarget by mutableStateOf<PortalWebTarget?>(null)
            var externalBrowserUrl by mutableStateOf<String?>(null)
            lateinit var expectedTodayTitle: String
            activityScenario.onActivity { activity ->
                expectedTodayTitle = activity.getString(R.string.portal_today_title)
                activity.setContent {
                    MsngrTheme {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            contentAlignment = Alignment.BottomEnd,
                        ) {
                            PortalSitesActionMenu(
                                config = MsgrClientConfig(
                                    todayUrl = "https://today.msgrsvcs.ctsrv.xyz/start?wlm=1",
                                    tabs = listOf(
                                        MessengerTabSite("1", "Wiby", "Wiby", "https://wiby.me/"),
                                        MessengerTabSite("2", "TheOldNet", "TheOldNet", "https://theoldnet.com"),
                                    ),
                                ),
                                onOpenWebTarget = { openedTarget = it },
                            )
                        }
                        openedTarget?.let { target ->
                            PortalWebViewDialog(
                                target = target,
                                onDismiss = { openedTarget = null },
                                onOpenInBrowser = { externalBrowserUrl = it },
                            )
                        }
                    }
                }
            }
            compose.waitForIdle()
            compose.onNodeWithTag("portal_sites_fab").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("portal_site_today").assertIsDisplayed()
            compose.onNodeWithTag("portal_site_1").assertIsDisplayed()
            compose.onNodeWithTag("portal_site_2").assertIsDisplayed()

            compose.onNodeWithTag("portal_site_today").performClick()
            compose.waitForIdle()
            assertEquals(expectedTodayTitle, openedTarget?.title)
            assertEquals("https://today.msgrsvcs.ctsrv.xyz/start?wlm=1", openedTarget?.url)
            compose.onNodeWithTag("portal_webview_dialog").assertIsDisplayed()
            compose.onNodeWithTag("portal_webview_pull_refresh").assertIsDisplayed()
            compose.onNodeWithTag("portal_webview_refresh").assertDoesNotExist()
            compose.onNodeWithTag("portal_webview_back").assertDoesNotExist()
            compose.onNodeWithTag("portal_webview_open_browser").assertIsDisplayed()
            compose.onNodeWithTag("portal_webview_open_browser").performClick()
            compose.waitForIdle()
            assertEquals("https://today.msgrsvcs.ctsrv.xyz/start?wlm=1", externalBrowserUrl)
            compose.onNodeWithTag("portal_webview_close").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("portal_webview_dialog").assertDoesNotExist()
        } finally {
            activityScenario.close()
        }
    }
}
