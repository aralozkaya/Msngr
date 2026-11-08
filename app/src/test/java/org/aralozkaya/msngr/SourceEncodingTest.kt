package org.aralozkaya.msngr

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards against UI text corrupted by tools that read or write UTF-8 sources with another
 * charset: an ellipsis then renders as "â€¦" instead of "…". Kotlin sources spell the
 * ellipsis as the `\u2026` escape so it cannot be damaged that way.
 */
class SourceEncodingTest {
    private val mojibakeMarkers = listOf("\u00E2\u20AC", "\u00C3\u00A9", "\u00C2\u00B7", "\u00EF\u00BF\u00BD")

    private fun mainSources(): List<File> {
        val root = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
        return root.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }
            .toList()
    }

    @Test fun sourcesContainNoMojibake() {
        val offenders = mainSources().flatMap { file ->
            file.readLines(Charsets.UTF_8).withIndex().filter { (_, line) ->
                mojibakeMarkers.any(line::contains)
            }.map { "${file.name}:${it.index + 1}" }
        }
        assertTrue("Corrupted UTF-8 text in: $offenders", offenders.isEmpty())
    }

    @Test fun kotlinSourcesEscapeTheEllipsis() {
        val offenders = mainSources().filter { it.extension == "kt" }.flatMap { file ->
            file.readLines(Charsets.UTF_8).withIndex()
                .filter { (_, line) -> line.contains('\u2026') }
                .map { "${file.name}:${it.index + 1}" }
        }
        assertTrue("Use \\u2026 instead of a literal ellipsis in: $offenders", offenders.isEmpty())
    }
}
