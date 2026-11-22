package org.aralozkaya.msngr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class TranslationValidationTest {

    private fun appMainDir(): File {
        val candidates = listOf(File("src/main"), File("app/src/main"))
        return candidates.firstOrNull { it.isDirectory && File(it, "AndroidManifest.xml").isFile }
            ?: error("Could not locate app/src/main from ${File(".").absolutePath}")
    }

    private fun parseStringsXml(file: File): Pair<Map<String, String>, Int> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        doc.documentElement.normalize()
        val strings = linkedMapOf<String, String>()
        val children = doc.documentElement.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i) as? Element ?: continue
            if (node.tagName == "string") {
                val name = node.getAttribute("name")
                assertFalse("Duplicate key '$name' in ${file.path}", name in strings)
                strings[name] = node.textContent.orEmpty()
            }
        }
        val pluralsCount = doc.getElementsByTagName("plurals").length
        return strings to pluralsCount
    }

    private fun extractAliasTarget(file: File): String? {
        val match = Regex("""<!--\s*alias-of:\s*([A-Za-z0-9-]+)\s*-->""").find(file.readText(Charsets.UTF_8))
        return match?.groupValues?.get(1)
    }

    private fun folderToLocaleTag(dirName: String): String? {
        if (dirName == "values") return null
        val suffix = dirName.removePrefix("values-")
        val regional = Regex("""^([a-z]{2,3})-r([A-Z]{2})$""").matchEntire(suffix)
        if (regional != null) return "${regional.groupValues[1]}-${regional.groupValues[2]}"
        val script = Regex("""^b\+([a-z]{2,3})\+([A-Za-z0-9]+)(?:\+([A-Za-z0-9]+))?$""").matchEntire(suffix)
        if (script != null) {
            return listOfNotNull(
                script.groupValues[1],
                script.groupValues[2].takeIf { it.isNotEmpty() },
                script.groupValues[3].takeIf { it.isNotEmpty() },
            ).joinToString("-")
        }
        return suffix
    }

    private fun validateTranslationParity(
        baselineKeys: Set<String>,
        candidate: Map<String, String>,
    ): List<String> {
        val errors = mutableListOf<String>()
        val missing = baselineKeys - candidate.keys
        val extra = candidate.keys - baselineKeys
        if (missing.isNotEmpty()) errors += "Missing keys: $missing"
        if (extra.isNotEmpty()) errors += "Extra keys: $extra"
        // Empty string values ("") are explicitly allowed and must NOT produce an error.
        return errors
    }

    @Test fun allLocalesAndTemplateHaveIdenticalKeysAndAllowEmptyValues() {
        val mainDir = appMainDir()
        val resDir = File(mainDir, "res")
        val templateFile = File(mainDir, "strings.template.xml")
        assertTrue("Missing strings.template.xml", templateFile.isFile)

        val enFile = File(resDir, "values-en/strings.xml")
        assertTrue("Missing values-en/strings.xml", enFile.isFile)

        val (enMap, enPlurals) = parseStringsXml(enFile)
        val (tplMap, tplPlurals) = parseStringsXml(templateFile)

        assertEquals(0, enPlurals)
        assertEquals(0, tplPlurals)
        assertTrue("Expected translation keys to exist", enMap.isNotEmpty())

        assertEquals(emptyList<String>(), validateTranslationParity(enMap.keys, tplMap))

        resDir.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("values-") }
            ?.map { File(it, "strings.xml") }
            ?.filter { it.isFile && it != enFile && extractAliasTarget(it) == null }
            ?.forEach { otherFile ->
                val (otherMap, otherPlurals) = parseStringsXml(otherFile)
                assertEquals(0, otherPlurals)
                assertEquals(
                    "Parity mismatch in ${otherFile.path}",
                    emptyList<String>(),
                    validateTranslationParity(enMap.keys, otherMap),
                )
            }

        // Every template value must be empty ("")
        tplMap.forEach { (key, value) ->
            assertEquals("Template key '$key' must be empty", "", value)
        }

        // Empty values in actual locale files are valid (e.g. *_before_number keys when sentence starts with number)
        assertEquals("", enMap["group_members_count_singular_before_number"])
        assertEquals("", enMap["main_count_badge_cap_before_number"])

        // Any entries kept in values/strings.xml alongside alias-of must be translatable="false" and not duplicated in locales/template.
        val rootDoc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(resDir, "values/strings.xml"))
        rootDoc.documentElement.normalize()
        val rootChildren = rootDoc.documentElement.childNodes
        for (i in 0 until rootChildren.length) {
            val node = rootChildren.item(i) as? Element ?: continue
            if (node.tagName == "string") {
                val key = node.getAttribute("name")
                assertEquals(
                    "Entry '$key' in values/strings.xml must specify translatable=\"false\"",
                    "false",
                    node.getAttribute("translatable").trim(),
                )
                assertFalse("Untranslatable key '$key' must not appear in values-en/strings.xml", key in enMap)
                assertFalse("Untranslatable key '$key' must not appear in strings.template.xml", key in tplMap)
            }
        }
    }

    @Test fun validatorRejectsMissingKeysWhileAcceptingEmptyValues() {
        val mainDir = appMainDir()
        val (enMap, _) = parseStringsXml(File(mainDir, "res/values-en/strings.xml"))

        val allEmpty = enMap.keys.associateWith { "" }
        assertEquals(
            "All-empty translation file must be valid",
            emptyList<String>(),
            validateTranslationParity(enMap.keys, allEmpty),
        )

        val sampleKey = enMap.keys.first()
        val missingOne = enMap.filterKeys { it != sampleKey }
        val errors = validateTranslationParity(enMap.keys, missingOne)
        assertTrue("Missing key must fail validation", errors.any { sampleKey in it })
    }

    @Test fun everyRegionalLocaleHasLanguageDefaultAndValidAliases() {
        val resDir = File(appMainDir(), "res")
        val stringDirs = resDir.listFiles()?.filter { it.isDirectory && File(it, "strings.xml").isFile }.orEmpty()
        val byTag = mutableMapOf<String, File>()
        var defaultFile: File? = null
        for (dir in stringDirs) {
            val tag = folderToLocaleTag(dir.name)
            val file = File(dir, "strings.xml")
            if (tag == null) defaultFile = file else byTag[tag] = file
        }

        val rootDefault = requireNotNull(defaultFile) { "Missing values/strings.xml default fallback" }
        assertEquals("en", extractAliasTarget(rootDefault))
        val enDefault = requireNotNull(byTag["en"]) { "Missing values-en/strings.xml language default" }
        assertEquals(null, extractAliasTarget(enDefault))

        assertEquals("en-US", folderToLocaleTag("values-en-rUS"))
        assertEquals("en-GB", folderToLocaleTag("values-en-rGB"))
        for (tag in byTag.keys) {
            if ('-' in tag) {
                val lang = tag.substringBefore('-')
                assertTrue("Regional locale '$tag' must have a language-default 'values-$lang/strings.xml'", lang in byTag)
            }
        }
    }

    @Test fun numberSegmentsAreSplitCorrectlyWithoutPluralsOrNumericSpecifiers() {
        val mainDir = appMainDir()
        val resDir = File(mainDir, "res")
        val xmlFiles = resDir.listFiles()
            ?.filter { it.isDirectory }
            ?.map { File(it, "strings.xml") }
            ?.filter { it.isFile }
            .orEmpty() + File(mainDir, "strings.template.xml")

        val numericSpecifier = Regex("""%(?:\d+\$)?[+\-# 0,(]*\d*(?:\.\d+)?[df]""")
        for (file in xmlFiles) {
            val (entries, plurals) = parseStringsXml(file)
            assertEquals("<plurals> is forbidden (${file.path})", 0, plurals)

            entries.forEach { (key, value) ->
                assertFalse(
                    "Numeric specifier forbidden in '${file.path}' key '$key': $value",
                    numericSpecifier.containsMatchIn(value),
                )
            }

            if (entries.isEmpty()) continue
            entries.keys.filter { it.endsWith("_before_number") }.forEach { before ->
                val after = before.removeSuffix("_before_number") + "_after_number"
                assertTrue("Missing '$after' in ${file.path}", after in entries)
            }
            entries.keys.filter { it.endsWith("_after_number") }.forEach { after ->
                val before = after.removeSuffix("_after_number") + "_before_number"
                assertTrue("Missing '$before' in ${file.path}", before in entries)
            }
            entries.keys.filter { it.endsWith("_before_first_number") }.forEach { beforeFirst ->
                val stem = beforeFirst.removeSuffix("_before_first_number")
                assertTrue("Missing '${stem}_between_numbers' in ${file.path}", "${stem}_between_numbers" in entries)
                assertTrue("Missing '${stem}_after_second_number' in ${file.path}", "${stem}_after_second_number" in entries)
            }
        }
    }

    @Test fun localesConfigAndManifestDeclareSupportedLanguages() {
        val mainDir = appMainDir()
        val manifest = File(mainDir, "AndroidManifest.xml").readText(Charsets.UTF_8)
        assertTrue(
            "AndroidManifest.xml must declare android:localeConfig=\"@xml/locales_config\"",
            Regex("""android:localeConfig\s*=\s*"@xml/locales_config"""").containsMatchIn(manifest),
        )

        val localesConfig = File(mainDir, "res/xml/locales_config.xml")
        assertTrue("Missing res/xml/locales_config.xml", localesConfig.isFile)
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(localesConfig)
        val defaultLocale = doc.documentElement.getAttribute("android:defaultLocale")
        assertEquals("en", defaultLocale)

        val declared = mutableSetOf<String>()
        val children = doc.documentElement.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i) as? Element ?: continue
            if (node.tagName == "locale") {
                declared += node.getAttribute("android:name")
            }
        }
        assertEquals(setOf("en", "en-GB", "tr"), declared)
        val (enGbMap, _) = parseStringsXml(File(mainDir, "res/values-en-rGB/strings.xml"))
        assertEquals("Favourites", enGbMap["contacts_section_favorites"])
    }

    @Test fun singularPluralAndYouConventionsAreEnforced() {
        val resDir = File(appMainDir(), "res")
        val (enMap, _) = parseStringsXml(File(resDir, "values-en/strings.xml"))
        val (trMap, _) = parseStringsXml(File(resDir, "values-tr/strings.xml"))

        enMap.keys.forEach { key ->
            assertFalse("Legacy _one_ number key forbidden: $key", "_one_before_number" in key || "_one_after_number" in key)
            assertFalse("Legacy _other_ number key forbidden: $key", "_other_before_number" in key || "_other_after_number" in key)
        }

        val pluralRegex = Regex("""(?<!_you)_singular(?=(?:_you_(?:singular|plural))?_(?:before|after)_number$)""")
        val quantitySingularKeys = enMap.keys.filter {
            pluralRegex.containsMatchIn(it) && !it.startsWith("emotion_sound_numbered_")
        }
        assertTrue(quantitySingularKeys.isNotEmpty())
        quantitySingularKeys.forEach { singularKey ->
            val pluralKey = pluralRegex.replace(singularKey, "_plural")
            assertTrue("Missing plural counterpart '$pluralKey' for '$singularKey'", pluralKey in enMap)
            assertTrue("Missing plural counterpart '$pluralKey' in tr for '$singularKey'", pluralKey in trMap)
            assertEquals(
                "Turkish plural '$pluralKey' must match singular '$singularKey'",
                trMap[singularKey],
                trMap[pluralKey],
            )
        }

        val youWordRegex = Regex("""\b(you|your|yours|yourself|yourselves)\b""", RegexOption.IGNORE_CASE)
        enMap.forEach { (key, value) ->
            if (youWordRegex.containsMatchIn(value)) {
                assertTrue(
                    "Key '$key' containing 'you/your' must denote '_you_singular' or '_you_plural': $value",
                    "_you_singular" in key || "_you_plural" in key,
                )
            }
        }
    }

    @Test fun uiSourcesContainNoInlineUiStringsOrPlurals() {
        val javaDir = File(appMainDir(), "java")
        val forbidden = Regex(
            """(?<![A-Za-z0-9_])(Text\s*\(\s*(?:text\s*=\s*)?"[^"]+"|contentDescription\s*=\s*"[^"]+"|stateDescription\s*=\s*"[^"]+"|paneTitle\s*=\s*"[^"]+"|onClickLabel\s*=\s*"[^"]+"|onLongClickLabel\s*=\s*"[^"]+"|pluralStringResource|getQuantityString|R\.plurals)""",
        )
        val violations = mutableListOf<String>()
        javaDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                file.readLines(Charsets.UTF_8).forEachIndexed { idx, line ->
                    if (forbidden.containsMatchIn(line)) {
                        violations += "${file.name}:${idx + 1}: ${line.trim()}"
                    }
                }
            }
        assertEquals("Found hardcoded UI text or plurals: $violations", emptyList<String>(), violations)
    }
}
