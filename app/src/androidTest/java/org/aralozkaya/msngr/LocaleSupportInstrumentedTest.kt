package org.aralozkaya.msngr

import android.app.LocaleConfig
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import org.aralozkaya.msngr.enums.Presence
import org.aralozkaya.msngr.pages.components.LoggedInUserProfileCard
import org.aralozkaya.msngr.pages.components.PresenceLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LocaleSupportInstrumentedTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val baseContext: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun localizedContext(languageTags: String): Context {
        val config = Configuration(baseContext.resources.configuration)
        config.setLocales(LocaleList.forLanguageTags(languageTags))
        return baseContext.createConfigurationContext(config)
    }

    @Test fun osLocaleConfigExposesSupportedEnglishLocales() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val localeConfig = LocaleConfig(baseContext)
        assertEquals(LocaleConfig.STATUS_SUCCESS, localeConfig.status)
        val supported = localeConfig.supportedLocales
        val tags = buildSet {
            if (supported != null) {
                for (i in 0 until supported.size()) {
                    add(supported[i].toLanguageTag())
                }
            }
        }
        assertTrue("Expected 'en' in LocaleConfig ($tags)", "en" in tags)
        assertTrue("Expected 'tr' in LocaleConfig ($tags)", "tr" in tags)
        assertEquals(setOf("en", "tr"), tags)
        assertEquals("en", localeConfig.defaultLocale?.toLanguageTag())
    }

    @Test fun regionalLocalesLanguageDefaultAndUnsupportedFallbackResolveCorrectly() {
        val locales = listOf(
            localizedContext("en"),
            localizedContext("en-US"),
            localizedContext("en-GB"),
            localizedContext("en-AU"),
            localizedContext("ja-JP"),
        )
        for (localized in locales) {
            assertEquals("Msngr", localized.getString(R.string.app_name))
            assertEquals("Colors", localized.getString(R.string.plus_colors_plural))
            assertEquals("Minimize", localized.getString(R.string.game_minimize))
            assertEquals("File transfer canceled", localized.getString(R.string.file_sharing_canceled))
        }

        val tr = localizedContext("tr")
        assertEquals("Msngr", tr.getString(R.string.app_name))
        assertEquals("İptal", tr.getString(R.string.common_cancel))
        assertEquals("Siz", tr.getString(R.string.common_you_singular))
        assertEquals("?", tr.getString(R.string.shortcut_avatar_fallback_initial))
        assertEquals("Kişi", tr.getString(R.string.common_contact_singular))
        assertEquals("Engellenen kişiler", tr.getString(R.string.blocked_contacts_title))
        assertEquals("Kişi istekleri", tr.getString(R.string.contact_requests_title_plural))
    }

    @Test fun mainScreenStatusDropdownAndPresenceLabelAreTranslatedInTurkish() {
        val tr = localizedContext("tr")
        compose.setContent {
            CompositionLocalProvider(
                LocalContext provides tr,
                LocalConfiguration provides tr.resources.configuration,
                LocalResources provides tr.resources,
            ) {
                MaterialTheme {
                    Column {
                        LoggedInUserProfileCard(
                            profile = UserProfileSnapshot(
                                username = "Tester",
                                email = "user@example.test",
                                presence = Presence.Online,
                                statusMessage = "",
                            ),
                            onPresenceSelected = {},
                            onEditProfile = {},
                        )
                        PresenceLabel(Presence.Offline)
                    }
                }
            }
        }

        compose.onNodeWithText("Uygun").assertIsDisplayed().performClick()
        assertEquals(2, compose.onAllNodesWithText("Uygun").fetchSemanticsNodes().size)
        compose.onNodeWithText("Meşgul").assertIsDisplayed()
        compose.onNodeWithText("Uzakta").assertIsDisplayed()
        compose.onNodeWithText("Çevrim dışı görün").assertIsDisplayed()
        compose.onNodeWithText("Çevrim dışı").assertIsDisplayed()
    }

    @Test fun splitNumberSentencesAssembleCleanlyAcrossLocales() {
        val en = localizedContext("en")
        val enGb = localizedContext("en-GB")
        val tr = localizedContext("tr")

        assertEquals(
            "1 member",
            en.getQuantityNumberSentence(
                1,
                R.string.group_members_count_singular_before_number,
                R.string.group_members_count_singular_after_number,
                R.string.group_members_count_plural_before_number,
                R.string.group_members_count_plural_after_number,
            ),
        )
        assertEquals(
            "4 members",
            enGb.getQuantityNumberSentence(
                4,
                R.string.group_members_count_singular_before_number,
                R.string.group_members_count_singular_after_number,
                R.string.group_members_count_plural_before_number,
                R.string.group_members_count_plural_after_number,
            ),
        )
        assertEquals(
            "1 üye",
            tr.getQuantityNumberSentence(
                1,
                R.string.group_members_count_singular_before_number,
                R.string.group_members_count_singular_after_number,
                R.string.group_members_count_plural_before_number,
                R.string.group_members_count_plural_after_number,
            ),
        )
        assertEquals(
            "4 üye",
            tr.getQuantityNumberSentence(
                4,
                R.string.group_members_count_singular_before_number,
                R.string.group_members_count_singular_after_number,
                R.string.group_members_count_plural_before_number,
                R.string.group_members_count_plural_after_number,
            ),
        )
        assertEquals(
            "Enter a valid color: #RRGGBB, 00–67, or name.",
            en.getTwoNumbersSentence(
                R.string.color_picker_code_error_before_first_number,
                "00",
                R.string.color_picker_code_error_between_numbers,
                67,
                R.string.color_picker_code_error_after_second_number,
            ),
        )
        assertEquals(
            "Enter a valid color: #RRGGBB, 00–67, or name.",
            enGb.getTwoNumbersSentence(
                R.string.color_picker_code_error_before_first_number,
                "00",
                R.string.color_picker_code_error_between_numbers,
                67,
                R.string.color_picker_code_error_after_second_number,
            ),
        )
        assertEquals(
            "Geçerli bir renk girin: #RRGGBB, 00–67 veya ad.",
            tr.getTwoNumbersSentence(
                R.string.color_picker_code_error_before_first_number,
                "00",
                R.string.color_picker_code_error_between_numbers,
                67,
                R.string.color_picker_code_error_after_second_number,
            ),
        )
    }
}
