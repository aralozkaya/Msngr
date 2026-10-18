package org.aralozkaya.msngr.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeColorSchemeTest {
    @Test
    fun staticSchemesPopulateEveryMaterialColorRole() {
        assertEquals(expectedRoles(isDark = false), LightColorScheme.allRoles())
        assertEquals(expectedRoles(isDark = true), DarkColorScheme.allRoles())
    }

    @Test
    fun pairedContentColorsMeetNormalTextContrast() {
        listOf(LightColorScheme, DarkColorScheme).forEach { scheme ->
            scheme.contrastPairs().forEach { (role, foreground, background) ->
                val contrast = contrastRatio(foreground, background)
                assertTrue("$role contrast was $contrast", contrast >= MIN_TEXT_CONTRAST)
            }
        }
    }

    @Test
    fun surfaceHierarchySeparatesPageCanvasFromItems() {
        listOf(LightColorScheme, DarkColorScheme).forEach { scheme ->
            assertNotEquals(scheme.surface, scheme.surfaceContainer)
            assertNotEquals(scheme.surfaceContainerLowest, scheme.surfaceContainerLow)
            assertNotEquals(scheme.surfaceContainerLow, scheme.surfaceContainer)
            assertNotEquals(scheme.surfaceContainer, scheme.surfaceContainerHigh)
            assertNotEquals(scheme.surfaceContainerHigh, scheme.surfaceContainerHighest)
        }
    }

    @Test
    fun fixedAccentRolesStayFixedAcrossBrightnessModes() {
        assertEquals(LightColorScheme.primaryFixed, DarkColorScheme.primaryFixed)
        assertEquals(LightColorScheme.primaryFixedDim, DarkColorScheme.primaryFixedDim)
        assertEquals(LightColorScheme.secondaryFixed, DarkColorScheme.secondaryFixed)
        assertEquals(LightColorScheme.secondaryFixedDim, DarkColorScheme.secondaryFixedDim)
        assertEquals(LightColorScheme.tertiaryFixed, DarkColorScheme.tertiaryFixed)
        assertEquals(LightColorScheme.tertiaryFixedDim, DarkColorScheme.tertiaryFixedDim)
    }

    private fun ColorScheme.allRoles(): List<Pair<String, Color>> = listOf(
        "primary" to primary,
        "onPrimary" to onPrimary,
        "primaryContainer" to primaryContainer,
        "onPrimaryContainer" to onPrimaryContainer,
        "inversePrimary" to inversePrimary,
        "secondary" to secondary,
        "onSecondary" to onSecondary,
        "secondaryContainer" to secondaryContainer,
        "onSecondaryContainer" to onSecondaryContainer,
        "tertiary" to tertiary,
        "onTertiary" to onTertiary,
        "tertiaryContainer" to tertiaryContainer,
        "onTertiaryContainer" to onTertiaryContainer,
        "background" to background,
        "onBackground" to onBackground,
        "surface" to surface,
        "onSurface" to onSurface,
        "surfaceVariant" to surfaceVariant,
        "onSurfaceVariant" to onSurfaceVariant,
        "surfaceTint" to surfaceTint,
        "inverseSurface" to inverseSurface,
        "inverseOnSurface" to inverseOnSurface,
        "error" to error,
        "onError" to onError,
        "errorContainer" to errorContainer,
        "onErrorContainer" to onErrorContainer,
        "outline" to outline,
        "outlineVariant" to outlineVariant,
        "scrim" to scrim,
        "surfaceBright" to surfaceBright,
        "surfaceDim" to surfaceDim,
        "surfaceContainer" to surfaceContainer,
        "surfaceContainerHigh" to surfaceContainerHigh,
        "surfaceContainerHighest" to surfaceContainerHighest,
        "surfaceContainerLow" to surfaceContainerLow,
        "surfaceContainerLowest" to surfaceContainerLowest,
        "primaryFixed" to primaryFixed,
        "primaryFixedDim" to primaryFixedDim,
        "onPrimaryFixed" to onPrimaryFixed,
        "onPrimaryFixedVariant" to onPrimaryFixedVariant,
        "secondaryFixed" to secondaryFixed,
        "secondaryFixedDim" to secondaryFixedDim,
        "onSecondaryFixed" to onSecondaryFixed,
        "onSecondaryFixedVariant" to onSecondaryFixedVariant,
        "tertiaryFixed" to tertiaryFixed,
        "tertiaryFixedDim" to tertiaryFixedDim,
        "onTertiaryFixed" to onTertiaryFixed,
        "onTertiaryFixedVariant" to onTertiaryFixedVariant,
    )

    private fun expectedRoles(isDark: Boolean): List<Pair<String, Color>> = listOf(
        "primary" to if (isDark) DarkPrimary else LightPrimary,
        "onPrimary" to if (isDark) DarkOnPrimary else LightOnPrimary,
        "primaryContainer" to if (isDark) DarkPrimaryContainer else LightPrimaryContainer,
        "onPrimaryContainer" to if (isDark) DarkOnPrimaryContainer else LightOnPrimaryContainer,
        "inversePrimary" to if (isDark) DarkInversePrimary else LightInversePrimary,
        "secondary" to if (isDark) DarkSecondary else LightSecondary,
        "onSecondary" to if (isDark) DarkOnSecondary else LightOnSecondary,
        "secondaryContainer" to if (isDark) DarkSecondaryContainer else LightSecondaryContainer,
        "onSecondaryContainer" to if (isDark) DarkOnSecondaryContainer else LightOnSecondaryContainer,
        "tertiary" to if (isDark) DarkTertiary else LightTertiary,
        "onTertiary" to if (isDark) DarkOnTertiary else LightOnTertiary,
        "tertiaryContainer" to if (isDark) DarkTertiaryContainer else LightTertiaryContainer,
        "onTertiaryContainer" to if (isDark) DarkOnTertiaryContainer else LightOnTertiaryContainer,
        "background" to if (isDark) DarkBackground else LightBackground,
        "onBackground" to if (isDark) DarkOnBackground else LightOnBackground,
        "surface" to if (isDark) DarkSurface else LightSurface,
        "onSurface" to if (isDark) DarkOnSurface else LightOnSurface,
        "surfaceVariant" to if (isDark) DarkSurfaceVariant else LightSurfaceVariant,
        "onSurfaceVariant" to if (isDark) DarkOnSurfaceVariant else LightOnSurfaceVariant,
        "surfaceTint" to if (isDark) DarkPrimary else LightPrimary,
        "inverseSurface" to if (isDark) DarkInverseSurface else LightInverseSurface,
        "inverseOnSurface" to if (isDark) DarkInverseOnSurface else LightInverseOnSurface,
        "error" to if (isDark) DarkError else LightError,
        "onError" to if (isDark) DarkOnError else LightOnError,
        "errorContainer" to if (isDark) DarkErrorContainer else LightErrorContainer,
        "onErrorContainer" to if (isDark) DarkOnErrorContainer else LightOnErrorContainer,
        "outline" to if (isDark) DarkOutline else LightOutline,
        "outlineVariant" to if (isDark) DarkOutlineVariant else LightOutlineVariant,
        "scrim" to Scrim,
        "surfaceBright" to if (isDark) DarkSurfaceBright else LightSurfaceBright,
        "surfaceDim" to if (isDark) DarkSurfaceDim else LightSurfaceDim,
        "surfaceContainer" to if (isDark) DarkSurfaceContainer else LightSurfaceContainer,
        "surfaceContainerHigh" to if (isDark) DarkSurfaceContainerHigh else LightSurfaceContainerHigh,
        "surfaceContainerHighest" to if (isDark) DarkSurfaceContainerHighest else LightSurfaceContainerHighest,
        "surfaceContainerLow" to if (isDark) DarkSurfaceContainerLow else LightSurfaceContainerLow,
        "surfaceContainerLowest" to if (isDark) DarkSurfaceContainerLowest else LightSurfaceContainerLowest,
        "primaryFixed" to PrimaryFixed,
        "primaryFixedDim" to PrimaryFixedDim,
        "onPrimaryFixed" to OnPrimaryFixed,
        "onPrimaryFixedVariant" to OnPrimaryFixedVariant,
        "secondaryFixed" to SecondaryFixed,
        "secondaryFixedDim" to SecondaryFixedDim,
        "onSecondaryFixed" to OnSecondaryFixed,
        "onSecondaryFixedVariant" to OnSecondaryFixedVariant,
        "tertiaryFixed" to TertiaryFixed,
        "tertiaryFixedDim" to TertiaryFixedDim,
        "onTertiaryFixed" to OnTertiaryFixed,
        "onTertiaryFixedVariant" to OnTertiaryFixedVariant,
    )

    private fun ColorScheme.contrastPairs(): List<Triple<String, Color, Color>> = listOf(
        Triple("onPrimary", onPrimary, primary),
        Triple("onPrimaryContainer", onPrimaryContainer, primaryContainer),
        Triple("onSecondary", onSecondary, secondary),
        Triple("onSecondaryContainer", onSecondaryContainer, secondaryContainer),
        Triple("onTertiary", onTertiary, tertiary),
        Triple("onTertiaryContainer", onTertiaryContainer, tertiaryContainer),
        Triple("onBackground", onBackground, background),
        Triple("onSurface", onSurface, surface),
        Triple("onSurfaceVariant", onSurfaceVariant, surfaceVariant),
        Triple("inverseOnSurface", inverseOnSurface, inverseSurface),
        Triple("onError", onError, error),
        Triple("onErrorContainer", onErrorContainer, errorContainer),
        Triple("onPrimaryFixed", onPrimaryFixed, primaryFixed),
        Triple("onSecondaryFixed", onSecondaryFixed, secondaryFixed),
        Triple("onTertiaryFixed", onTertiaryFixed, tertiaryFixed),
    )

    private fun contrastRatio(foreground: Color, background: Color): Float {
        val lighter = maxOf(foreground.luminance(), background.luminance())
        val darker = minOf(foreground.luminance(), background.luminance())
        return (lighter + 0.05f) / (darker + 0.05f)
    }

    private companion object {
        const val MIN_TEXT_CONTRAST = 4.5f
    }
}
