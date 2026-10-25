package org.aralozkaya.msngr.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.aralozkaya.msngr.MessengerMainColors
import org.aralozkaya.msngr.MessengerScenes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SceneColorSchemeTest {
    @Test fun everySceneAndCustomSeedHasReadableLightAndDarkMaterialRoles() {
        val seeds = MessengerScenes.map { it.seedArgb } + MessengerMainColors.map { it.second.toInt() } +
            listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF808080.toInt(),
                0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt())
        seeds.forEach { seed ->
            listOf(false, true).forEach { dark ->
                val s = seededColorScheme(seed, dark)
                listOf(s.onPrimary to s.primary, s.onPrimaryContainer to s.primaryContainer,
                    s.onSecondary to s.secondary, s.onSecondaryContainer to s.secondaryContainer,
                    s.onTertiary to s.tertiary, s.onTertiaryContainer to s.tertiaryContainer,
                    s.onSurface to s.surface, s.onSurfaceVariant to s.surfaceVariant,
                    s.onBackground to s.background, s.onError to s.error,
                    s.onErrorContainer to s.errorContainer, s.inverseOnSurface to s.inverseSurface,
                    s.onPrimaryFixed to s.primaryFixed, s.onSecondaryFixed to s.secondaryFixed,
                    s.onTertiaryFixed to s.tertiaryFixed).forEach { (fg, bg) ->
                    val ratio = (maxOf(fg.luminance(), bg.luminance()) + 0.05f) /
                        (minOf(fg.luminance(), bg.luminance()) + 0.05f)
                    assertTrue("Seed ${seed.toUInt().toString(16)}, dark=$dark contrast=$ratio", ratio >= 4.5f)
                }
                assertNotEquals(s.surfaceContainerLowest, s.surfaceContainerHighest)
                assertEquals(Color.Black, s.scrim)
                assertEquals(s.primary, s.surfaceTint)
            }
            assertEquals(seededColorScheme(seed, false).primaryFixed, seededColorScheme(seed, true).primaryFixed)
        }
    }

    @Test fun catalogContainsEveryDownloadableGenerationAndBothLaterVariants() {
        assertEquals(51, MessengerScenes.size)
        assertEquals(MessengerScenes.size, MessengerScenes.distinctBy { it.id }.size)
        assertEquals(setOf("Windows Live Messenger 2009", "Windows Live Messenger 2011 and 2012"),
            MessengerScenes.map { it.group }.toSet())
        assertEquals(22, MessengerScenes.count { it.group == "Windows Live Messenger 2009" })
        assertEquals(29, MessengerScenes.count { it.group == "Windows Live Messenger 2011 and 2012" })
        val assets = File("src/main/assets")
        MessengerScenes.forEach { scene ->
            assertTrue(scene.id, File(assets, scene.assetPath).isFile)
            assertTrue(scene.id, File(assets, scene.previewPath).isFile)
            if (scene.group == "Windows Live Messenger 2011 and 2012") {
                assertNotNull(scene.wideFile)
                assertTrue(File(assets, "scenes/${scene.wideFile}").isFile)
            }
            assertEquals(0xFF, scene.seedArgb ushr 24)
        }
        val expectedFiles = MessengerScenes.flatMap { listOfNotNull(it.file, it.wideFile, "${it.id}_preview.webp") }.toSet()
        val actualFiles = File(assets, "scenes").listFiles()!!.filter { it.extension != "json" }.map { it.name }.toSet()
        assertEquals("Only scene originals and their picker previews are packaged", expectedFiles, actualFiles)
    }
}
