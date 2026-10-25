package org.aralozkaya.msngr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO

class MsnTextInputTest {
    @Test
    fun removesUnicodeEmojiSequencesButPreservesTextAndMsnShortcuts() {
        val emojiExamples = mapOf(
            "hello😀world" to "helloworld",
            "A❤️B" to "AB",
            "A👍🏽B" to "AB",
            "A👨‍👩‍👧‍👦B" to "AB",
            "A🇹🇷B" to "AB",
            "A1️⃣B" to "AB",
            "A↩️B" to "AB",
        )
        emojiExamples.forEach { (input, expected) ->
            assertEquals(expected, input.withoutUnicodeEmoji())
        }

        val ordinaryText = "Türkçe العربية 中文 :) :d (y) (S)"
        assertEquals(ordinaryText, ordinaryText.withoutUnicodeEmoji())
    }

    @Test
    fun officialShortcutListIncludesMissingAndUnusedEmoticonsWithTooltipCasingRules() {
        assertEquals(79, MSN_EMOTICON_SHORTCUTS.size)
        assertEquals(
            MSN_EMOTICON_SHORTCUTS.size,
            MSN_EMOTICON_SHORTCUTS.map(MsnEmoticonShortcut::shortcut).distinct().size,
        )
        val shortcutsByName = MSN_EMOTICON_SHORTCUTS.associate {
            it.name to it.shortcut
        }
        val expectedShortcuts = mapOf(
            "Smile" to ":)",
            "Open-mouthed" to ":D",
            "Surprised" to ":O",
            "Tongue out" to ":P",
            "Wink" to ";)",
            "Sad" to ":(",
            "Confused" to ":S",
            "Disappointed" to ":|",
            "Embarrassed" to ":$",
            "Hot" to "(H)",
            "Angry" to ":@",
            "Angel" to "(A)",
            "Devil" to "(6)",
            "Baring teeth" to "8O|",
            "Sarcastic" to "^O)",
            "Sick" to "+O(",
            "Party" to "<:O)",
            "Coffee cup" to "(C)",
            "Thumbs up" to "(Y)",
            "Thumbs down" to "(N)",
            "Beer mug" to "(B)",
            "Martini glass" to "(D)",
            "Girl" to "(X)",
            "Boy" to "(Z)",
            "Vampire bat" to ":[",
            "Red heart" to "(L)",
            "Broken heart" to "(U)",
            "Red lips" to "(K)",
            "Gift with a bow" to "(G)",
            "Red rose" to "(F)",
            "Wilted rose" to "(W)",
            "Camera" to "(P)",
            "Telephone receiver" to "(T)",
            "Light bulb" to "(I)",
            "Sleeping half-moon" to "(S)",
            "E-mail" to "(E)",
            "Clock" to "(O)",
            "MSN Messenger icon" to "(M)",
            "Snail" to "(sn)",
            "Black Sheep" to "(bah)",
            "Plate" to "(pl)",
            "Bowl" to "(||)",
            "Pizza" to "(pi)",
            "Soccer ball" to "(so)",
            "Auto" to "(au)",
            "Airplane" to "(ap)",
            "Umbrella" to "(um)",
            "Island with a palm tree" to "(ip)",
            "Computer" to "(co)",
            "Mobile Phone" to "(mp)",
            "Stormy cloud" to "(st)",
            "Lightning" to "(li)",
            "Money" to "(mo)",
            "Goat" to "(nnh)",
            "Sun" to "(#)",
            "Rainbow" to "(R)",
            "Turtle" to "(tu)",
            "Be right back" to "(brb)",
            "High five!" to "(h5)",
            "Bunny" to "('.')",
            "Fingers crossed" to "(yn)",
            "Cigarette" to "(ci)",
            "Xbox" to "(xx)",
        )
        expectedShortcuts.forEach { (name, shortcut) ->
            assertEquals("Unexpected shortcut for $name", shortcut, shortcutsByName[name])
        }
        assertTrue(MSN_EMOTICON_SHORTCUTS.all { emoticon ->
            val expectedCase = if (
                emoticon.shortcut.startsWith("(") &&
                emoticon.shortcut.endsWith(")") &&
                emoticon.shortcut.length > 3
            ) {
                emoticon.shortcut.lowercase(Locale.ROOT)
            } else {
                emoticon.shortcut.uppercase(Locale.ROOT)
            }
            emoticon.shortcut == expectedCase &&
                emoticon.tooltipShortcut == expectedCase &&
                emoticon.shortcut.all { it.code <= 0x7F } &&
                emoticon.shortcut == emoticon.shortcut.withoutUnicodeEmoji()
        })
    }

    @Test
    fun everyPickerShortcutHasUniqueOriginalAssetUnicodeEquivalentAndAssetCandidates() {
        assertEquals(
            MSN_EMOTICON_SHORTCUTS.size,
            MSN_EMOTICON_SHORTCUTS.map(MsnEmoticonShortcut::originalAsset).distinct().size,
        )
        assertEquals(
            MSN_EMOTICON_SHORTCUTS.size,
            MSN_EMOTICON_SHORTCUTS.map(MsnEmoticonShortcut::menuToken).distinct().size,
        )
        assertTrue(MSN_EMOTICON_SHORTCUTS.all { it.unicode.isNotBlank() })
        assertTrue(MSN_EMOTICON_SHORTCUTS.all { it.menuToken.code in 0xE000..0xF8FF })

        val animatedAssets = MSN_EMOTICON_SHORTCUTS.filter { it.animated }.map { it.originalAsset }.toSet()
        assertEquals(
            setOf(
                "winking-smile",
                "crying-face",
                "i-dont-know-smile",
                "thinking-smile",
                "party-smile",
                "eye-rolling-smile",
                "sleepy-smile",
                "vampire-bat",
                "birthday-cake",
                "lightning",
                "unused-cigarette",
            ),
            animatedAssets,
        )

        MSN_EMOTICON_SHORTCUTS.forEach { emoticon ->
            assertTrue(
                "Selector sheet must always prefer static SVG for ${emoticon.name}",
                emoticon.selectorAssetCandidates.first().endsWith(".svg"),
            )
            if (emoticon.animated) {
                assertEquals(
                    "emoticons/${emoticon.originalAsset}-static.svg",
                    emoticon.selectorAssetCandidates.first(),
                )
                assertEquals(
                    listOf(
                        "emoticons/${emoticon.originalAsset}.png",
                        "emoticons/${emoticon.originalAsset}.gif",
                        "emoticons/${emoticon.originalAsset}-static.svg",
                        "emoticons/${emoticon.originalAsset}.svg",
                    ),
                    emoticon.artworkAssetCandidates,
                )
            } else {
                assertEquals(
                    "emoticons/${emoticon.originalAsset}.svg",
                    emoticon.selectorAssetCandidates.single(),
                )
                assertEquals(
                    "emoticons/${emoticon.originalAsset}.svg",
                    emoticon.artworkAssetCandidates.first(),
                )
            }
        }
    }

    @Test
    fun allBundledSvgApngAndGifAssetsDecodeTo19x19EmoticonGrid() {
        val assetsRoot = sequenceOf(
            File("src/main/assets"),
            File("app/src/main/assets"),
        ).firstOrNull(File::isDirectory)
        assertNotNull("Could not locate src/main/assets directory", assetsRoot)
        val root = assetsRoot!!

        MSN_EMOTICON_SHORTCUTS.forEach { emoticon ->
            val selectorPath = emoticon.selectorAssetCandidates.first()
            val selectorFile = File(root, selectorPath)
            assertTrue("Missing selector SVG asset $selectorPath", selectorFile.isFile)
            val svgDoc = SvgCodec.parse(selectorFile.readBytes())
            assertEquals(19f, svgDoc.width, 0.001f)
            assertEquals(19f, svgDoc.height, 0.001f)
            assertEquals(19f, svgDoc.viewBoxWidth, 0.001f)
            assertEquals(19f, svgDoc.viewBoxHeight, 0.001f)
            val grid = svgDoc.rasterizeGrid()
            assertEquals(19 * 19, grid.size)
            assertTrue("Expected non-transparent pixels in $selectorPath", grid.any { (it ushr 24) != 0 })

            if (emoticon.animated) {
                val apngFile = File(root, "emoticons/${emoticon.originalAsset}.png")
                val gifFile = File(root, "emoticons/${emoticon.originalAsset}.gif")
                assertTrue("Missing APNG asset ${apngFile.path}", apngFile.isFile)
                assertTrue("Missing GIF fallback asset ${gifFile.path}", gifFile.isFile)

                val apng = ApngCodec.parse(apngFile.readBytes())
                assertNotNull("Expected animated PNG for ${emoticon.originalAsset}", apng)
                val compositedApng = ApngCodec.composite(apng!!) { frame ->
                    val image = ImageIO.read(ByteArrayInputStream(frame.png))
                    image.getRGB(0, 0, frame.width, frame.height, null, 0, frame.width)
                }
                assertEquals(MSN_EMOTICON_GRID_PX, compositedApng.width)
                assertEquals(MSN_EMOTICON_GRID_PX, compositedApng.height)
                assertTrue("Expected multiple frames in ${apngFile.name}", compositedApng.frames.size > 1)
                assertTrue(
                    "Expected every APNG frame in ${apngFile.name} to be 19x19 and non-empty",
                    compositedApng.frames.all { frame ->
                        frame.size == MSN_EMOTICON_GRID_PX * MSN_EMOTICON_GRID_PX &&
                            frame.any { (it ushr 24) != 0 }
                    },
                )

                val compositedGif = GifCodec.decode(gifFile.readBytes())
                assertEquals(MSN_EMOTICON_GRID_PX, compositedGif.width)
                assertEquals(MSN_EMOTICON_GRID_PX, compositedGif.height)
                assertTrue("Expected multiple frames in ${gifFile.name}", compositedGif.frames.size > 1)
                assertTrue(
                    "Expected every GIF frame in ${gifFile.name} to be 19x19 and non-empty",
                    compositedGif.frames.all { frame ->
                        frame.size == MSN_EMOTICON_GRID_PX * MSN_EMOTICON_GRID_PX &&
                            frame.any { (it ushr 24) != 0 }
                    },
                )
            }
        }
    }

    @Test
    fun parserRecognizesEveryShortcutAndAlternateCaseInsensitively() {
        MSN_EMOTICON_SHORTCUTS.forEach { emoticon ->
            emoticon.allShortcuts.forEach { rawShortcut ->
                listOf(
                    rawShortcut.uppercase(Locale.ROOT),
                    rawShortcut.lowercase(Locale.ROOT),
                ).forEach { input ->
                    val matches = findMsnEmoticons(input)
                    assertEquals("Failed to parse ${emoticon.name} ($input)", 1, matches.size)
                    assertEquals(emoticon, matches.single().emoticon)
                    assertEquals(MsnEmoticonRange(0, input.length), matches.single().range)
                }
            }
        }
        assertEquals("Open-mouthed", findMsnEmoticons(":D").single().emoticon.name)
        assertEquals("Open-mouthed", findMsnEmoticons(":-d").single().emoticon.name)
        assertEquals("Surprised", findMsnEmoticons(":-O").single().emoticon.name)
        assertEquals("Smile", findMsnEmoticons(":-)").single().emoticon.name)
        assertEquals("Cigarette", findMsnEmoticons("(ci)").single().emoticon.name)
        assertEquals("Fingers crossed", findMsnEmoticons("(yn)").single().emoticon.name)
        assertEquals("Xbox", findMsnEmoticons("(xx)").single().emoticon.name)
    }

    @Test
    fun parserUsesLongestMatchAndCanRenderModernUnicode() {
        val matches = findMsnEmoticons("Party <:O), smile :D, then :^)")
        assertEquals(listOf("Party", "Open-mouthed", "I don't know"), matches.map { it.emoticon.name })
        assertEquals(
            "Party 🥳, smile 😀, then 😕",
            "Party <:O), smile :D, then :^)".withUnicodeMsnEmoticons(),
        )
    }

    @Test
    fun disabledConversionLeavesTypedShortcutsButAlwaysRecognizesPickerTokens() {
        val smile = MSN_EMOTICON_SHORTCUTS.first { it.name == "Smile" }
        val draft = "Typed :D, picked ${smile.menuToken}"

        val matches = findMsnEmoticons(draft, includeTypedShortcuts = false)
        assertEquals(listOf("Smile"), matches.map { it.emoticon.name })
        assertEquals("Typed :D, picked 🙂", draft.withUnicodeMsnEmoticons(includeTypedShortcuts = false))
    }

    @Test
    fun outgoingMessageExpandsPickerTokensAndTracksOnlyTheirRangesWhenDisabled() {
        val smile = MSN_EMOTICON_SHORTCUTS.first { it.name == "Smile" }
        val outgoing = "  Hi :D ${smile.menuToken}  ".toMsnOutgoingMessage(
            convertTypedShortcuts = false,
        )

        assertEquals("Hi :D :)", outgoing.text)
        assertEquals(listOf(MsnEmoticonRange(6, 8)), outgoing.renderedEmoticonRanges)
        assertEquals(
            "Hi :D 🙂",
            outgoing.text.withUnicodeMsnEmoticons(
                renderedShortcutRanges = outgoing.renderedEmoticonRanges,
            ),
        )
        assertFalse(outgoing.text.any { it.code in 0xE000..0xF8FF })
    }

    @Test
    fun outgoingMessageRendersAllShortcutsWhenConversionIsEnabled() {
        val smile = MSN_EMOTICON_SHORTCUTS.first { it.name == "Smile" }
        val outgoing = "Hi :D ${smile.menuToken}".toMsnOutgoingMessage(
            convertTypedShortcuts = true,
        )

        assertEquals("Hi :D :)", outgoing.text)
        assertNull(outgoing.renderedEmoticonRanges)
        assertEquals("Hi 😀 🙂", outgoing.text.withUnicodeMsnEmoticons())
    }

    @Test
    fun outgoingMessageNormalizesPlusMarkupWithoutLosingPickerRanges() {
        val smile = MSN_EMOTICON_SHORTCUTS.first { it.name == "Smile" }
        val outgoing = " [b]Hi ${smile.menuToken}[/b] ".toMsnOutgoingMessage(
            convertTypedShortcuts = false,
        )

        assertEquals("[b]Hi :)[/b]", outgoing.text)
        assertEquals(listOf(MsnEmoticonRange(6, 8)), outgoing.renderedEmoticonRanges)
        val parsed = MessengerPlusTextCodec.parse(outgoing.text)
        val displayRanges = outgoing.renderedEmoticonRanges?.mapNotNull(parsed::textRangeForSource)
        assertEquals(listOf(MsnEmoticonRange(3, 5)), displayRanges)
        assertEquals(
            "Hi 🙂",
            parsed.text.withUnicodeMsnEmoticons(renderedShortcutRanges = displayRanges),
        )
    }

    @Test
    fun disabledPlusFormattingSendsOnlyVisibleTextAndKeepsPickerRanges() {
        val smile = MSN_EMOTICON_SHORTCUTS.first { it.name == "Smile" }
        val outgoing = " [b]Hi ${smile.menuToken}[/b] ".toMsnOutgoingMessage(
            convertTypedShortcuts = false,
            messengerPlusEnabled = false,
        )

        assertEquals("Hi :)", outgoing.text)
        assertEquals(listOf(MsnEmoticonRange(3, 5)), outgoing.renderedEmoticonRanges)
        assertFalse(
            outgoing.text.any {
                it == MessengerPlusTextCodec.BOLD_CONTROL ||
                    it == MessengerPlusTextCodec.COLOR_CONTROL ||
                    it == MessengerPlusTextCodec.ITALIC_CONTROL ||
                    it == MessengerPlusTextCodec.UNDERLINE_CONTROL ||
                    it == MessengerPlusTextCodec.STRIKEOUT_CONTROL
            },
        )
    }

    @Test
    fun outgoingMessageRejectsFormattingControlsWithoutVisibleContent() {
        val outgoing = " \u0002\u0005\u0003 ".toMsnOutgoingMessage(
            convertTypedShortcuts = true,
        )

        assertEquals("", outgoing.text)
        assertNull(outgoing.renderedEmoticonRanges)
    }

    @Test
    fun incomingAndSourceRestrictedMessagesUseIndependentRenderPolicies() {
        val text = ":D :)"
        assertEquals("😀 🙂", text.withUnicodeMsnEmoticons(renderedShortcutRanges = null))
        assertEquals(text, text.withUnicodeMsnEmoticons(renderedShortcutRanges = emptyList()))
        assertEquals(
            ":D 🙂",
            text.withUnicodeMsnEmoticons(
                renderedShortcutRanges = listOf(MsnEmoticonRange(3, 5)),
            ),
        )
    }

    @Test
    fun emoticonSettingsDefaultToBothBehaviorsEnabled() {
        val settings = EmoticonSettings()
        assertTrue(settings.convertTypedShortcuts)
        assertTrue(settings.useOriginalEmoticons)
    }
}
