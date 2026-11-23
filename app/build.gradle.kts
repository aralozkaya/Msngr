import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// GitHub's default Java CodeQL checkout omits submodules. Managed-source extraction
// does not need the native codec or WASM assets; normal APK CI verifies both.
val javaOnlyCodeQl = providers.environmentVariable("CODEQL_EXTRACTOR_JAVA_ROOT").isPresent &&
    !providers.environmentVariable("CODEQL_EXTRACTOR_CPP_ROOT").isPresent
// Resolving Maven identities does not compile native code. Avoid downloading an
// NDK for this one task; every APK task still configures and builds the codec.
val dependencyInventoryOnly = gradle.startParameter.taskNames.isNotEmpty() &&
    gradle.startParameter.taskNames.all { it in setOf(":app:writeDependencyInventory", "writeDependencyInventory") }

val ruffleSource = rootProject.layout.projectDirectory.dir("third_party/ruffle")
val ruffleCommit = if (!ruffleSource.file(".git").asFile.exists() && ruffleSource.file("UPSTREAM_COMMIT").asFile.exists()) {
    providers.fileContents(ruffleSource.file("UPSTREAM_COMMIT")).asText.map { it.trim() }
} else providers.exec {
    workingDir(ruffleSource)
    commandLine("git", "rev-parse", "HEAD")
}.standardOutput.asText.map { it.trim() }

val verifyBundledRuffle = tasks.register("verifyBundledRuffle") {
    group = "verification"
    description = "Checks that the bundled Ruffle runtime matches the pinned source and file hashes."
    val manifest = rootProject.layout.projectDirectory.file("third_party/ruffle-runtime.json")
    val assets = layout.projectDirectory.dir("src/main/assets/ruffle")
    inputs.file(manifest)
    inputs.dir(assets)
    inputs.dir(rootProject.layout.projectDirectory.dir("third_party/ruffle-build"))
    inputs.file(rootProject.layout.projectDirectory.file("third_party/patches/ruffle-security.patch"))
    inputs.file(ruffleSource.file("web/packages/selfhosted/package.json"))
    inputs.property("sourceCommit", ruffleCommit)
    doLast {
        @Suppress("UNCHECKED_CAST")
        val metadata = groovy.json.JsonSlurper().parse(manifest.asFile) as Map<String, Any>
        check(metadata["sourceCommit"] == ruffleCommit.get()) {
            "Ruffle source/runtime mismatch. Run python tools/update_ruffle.py --update."
        }
        @Suppress("UNCHECKED_CAST")
        val hashes = metadata["files"] as Map<String, String>
        val actual = assets.asFile.listFiles()!!.filter { it.name != "README.md" }.map { it.name }.toSet()
        check(actual == hashes.keys) { "Ruffle runtime files differ from the locked file set" }
        hashes.forEach { (name, expected) ->
            check(name.matches(Regex("[a-zA-Z0-9_.-]+"))) { "Invalid Ruffle asset name" }
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(assets.file(name).asFile.readBytes()).joinToString("") { "%02x".format(it) }
            check(digest == expected) { "Ruffle integrity check failed: $name" }
        }
        fun sha256(file: File) = MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        val inventoryFile = rootProject.file("third_party/ruffle-build/inventory.json")
        check(metadata["dependencyInventory"] == sha256(inventoryFile)) {
            "Ruffle dependency inventory hash mismatch"
        }
        metadata["sourceBuild"]?.let { expected ->
            val buildFile = rootProject.file("third_party/ruffle-build/manifest.json")
            check(expected == sha256(buildFile)) { "Ruffle source build metadata hash mismatch" }
            @Suppress("UNCHECKED_CAST")
            val buildInfo = groovy.json.JsonSlurper().parse(buildFile) as Map<String, Any>
            check(buildInfo["sourceCommit"] == metadata["sourceCommit"] &&
                buildInfo["version"] == metadata["version"]) { "Ruffle source build identity mismatch" }
            @Suppress("UNCHECKED_CAST")
            (buildInfo["inputs"] as Map<String, String>).forEach { (path, hash) ->
                check(path.matches(Regex("third_party/(ruffle-build|patches)/[a-zA-Z0-9_.-]+"))) {
                    "Invalid Ruffle source build input path"
                }
                check(sha256(rootProject.file(path)) == hash) { "Ruffle source build input hash mismatch: $path" }
            }
        }
    }
}

abstract class GenerateLocaleAliasResourcesTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val stringResourceFiles: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val templateFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val localesConfigFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val manifestFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        data class ParsedStringFile(
            val tag: String,
            val dirName: String,
            val file: File,
            val aliasOf: String?,
            val entries: Map<String, String>,
            val untranslatableKeys: Set<String> = emptySet(),
        ) {
            val keys: Set<String> get() = entries.keys
        }

        fun qualifierToTag(qualifierDirName: String): String? {
            if (qualifierDirName == "values") return "default"
            if (!qualifierDirName.startsWith("values-")) return null
            val raw = qualifierDirName.removePrefix("values-")
            if (raw.startsWith("b+")) {
                return raw.removePrefix("b+").replace('+', '-')
            }
            val parts = raw.split('-')
            if (parts.isEmpty() || !parts[0].matches(Regex("[a-z]{2,3}"))) return null
            if (parts.size == 1) return parts[0]
            if (parts.size == 2 && parts[1].matches(Regex("r[A-Z0-9]{2,3}"))) {
                return "${parts[0]}-${parts[1].substring(1)}"
            }
            return null
        }

        val docBuilder = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder()

        fun parseStringXml(
            file: File,
            tag: String,
            dirName: String,
            allowAlias: Boolean,
        ): ParsedStringFile {
            val text = file.readText(Charsets.UTF_8)
            val aliasMatches = Regex("""<!--\s*alias-of:\s*([A-Za-z0-9-]+)\s*-->""")
                .findAll(text)
                .map { it.groupValues[1] }
                .toList()
            if (aliasMatches.size > 1) {
                throw GradleException("$file declares multiple alias-of directives: $aliasMatches")
            }
            val aliasOf = aliasMatches.firstOrNull()
            if (aliasOf != null && !allowAlias) {
                throw GradleException("$file cannot declare alias-of")
            }

            val doc = docBuilder.parse(file)
            val root = doc.documentElement
            if (root.tagName != "resources") {
                throw GradleException("$file root element must be <resources>, found <${root.tagName}>")
            }

            val entries = linkedMapOf<String, String>()
            val untranslatableKeys = linkedSetOf<String>()
            val numericFormatRegex = Regex("""%(\d+\$)?[+\-# 0,(]*\d*(\.\d+)?[df]""")
            val children = root.childNodes
            for (i in 0 until children.length) {
                val node = children.item(i)
                if (node.nodeType != org.w3c.dom.Node.ELEMENT_NODE) continue
                val elem = node as org.w3c.dom.Element
                when (elem.tagName) {
                    "plurals" -> throw GradleException(
                        "$file contains <plurals>; split sentences containing numbers into *_before_number and *_after_number <string> resources.",
                    )
                    "string" -> {
                        val name = elem.getAttribute("name").trim()
                        if (name.isEmpty()) {
                            throw GradleException("$file contains a <string> without a name attribute")
                        }
                        if (name in entries) {
                            throw GradleException("$file defines duplicate string key '$name'")
                        }
                        val value = elem.textContent ?: ""
                        entries[name] = value
                        if (elem.getAttribute("translatable").trim() == "false") {
                            untranslatableKeys.add(name)
                        }
                        if (aliasOf == null && numericFormatRegex.containsMatchIn(value)) {
                            throw GradleException(
                                "$file key '$name' contains numeric format specifier '$value'; split numeric text into before/after number resources.",
                            )
                        }
                    }
                }
            }

            if (aliasOf != null && entries.isNotEmpty()) {
                val illegal = if (tag == "default") entries.keys - untranslatableKeys else entries.keys
                if (illegal.isNotEmpty()) {
                    throw GradleException(
                        "$file is an alias-of:$aliasOf stub and may only contain translatable=\"false\" <string> items (found translatable keys: ${illegal.sorted().joinToString()}).",
                    )
                }
            }

            return ParsedStringFile(
                tag = tag,
                dirName = dirName,
                file = file,
                aliasOf = aliasOf,
                entries = entries,
                untranslatableKeys = untranslatableKeys,
            )
        }

        val parsedFiles = stringResourceFiles.files
            .sortedBy { it.absolutePath }
            .map { file ->
                val dirName = file.parentFile.name
                val tag = qualifierToTag(dirName)
                    ?: throw GradleException("Unsupported locale qualifier directory '$dirName' for $file")
                parseStringXml(file = file, tag = tag, dirName = dirName, allowAlias = true)
            }

        val byTag = parsedFiles.associateBy { it.tag }
        if ("default" !in byTag) {
            throw GradleException("Missing default strings file: app/src/main/res/values/strings.xml")
        }

        val resolvedConcrete = mutableMapOf<String, ParsedStringFile>()
        for (entry in parsedFiles) {
            var current = entry
            val visited = linkedSetOf(current.tag)
            while (current.aliasOf != null) {
                val targetTag = current.aliasOf!!
                if (!visited.add(targetTag)) {
                    throw GradleException("Circular locale alias detected: ${visited.joinToString(" -> ")} -> $targetTag")
                }
                current = byTag[targetTag]
                    ?: throw GradleException("${current.file} references missing alias target '$targetTag'")
            }
            resolvedConcrete[entry.tag] = current
        }

        val concreteFiles = parsedFiles.filter { it.aliasOf == null }
        if (concreteFiles.isEmpty()) {
            throw GradleException("No concrete strings.xml files found")
        }

        val rootUntranslatable = byTag.getValue("default").untranslatableKeys
        for (concrete in concreteFiles) {
            val overlap = concrete.keys.intersect(rootUntranslatable)
            if (overlap.isNotEmpty()) {
                throw GradleException(
                    "${concrete.file} must not redefine translatable=\"false\" keys from values/strings.xml: ${overlap.sorted().joinToString()}",
                )
            }
        }

        for (tag in byTag.keys) {
            if (tag != "default" && '-' in tag) {
                val baseLanguage = tag.substringBefore('-')
                if (baseLanguage !in byTag) {
                    throw GradleException(
                        "Regional locale '$tag' requires a default language file for '$baseLanguage' (values-$baseLanguage/strings.xml).",
                    )
                }
            }
        }

        val templateParsed = parseStringXml(
            file = templateFile.get().asFile,
            tag = "template",
            dirName = "template",
            allowAlias = false,
        )

        val expectedKeys = linkedSetOf<String>()
        concreteFiles.forEach { expectedKeys.addAll(it.keys) }
        expectedKeys.addAll(templateParsed.keys)

        for (concrete in concreteFiles) {
            val missing = expectedKeys - concrete.keys
            if (missing.isNotEmpty()) {
                throw GradleException("${concrete.file} is missing string keys: ${missing.sorted().joinToString()}")
            }
        }
        val missingInTemplate = expectedKeys - templateParsed.keys
        if (missingInTemplate.isNotEmpty()) {
            throw GradleException(
                "${templateParsed.file} is missing string keys: ${missingInTemplate.sorted().joinToString()}",
            )
        }
        val extraInTemplate = templateParsed.keys - concreteFiles.flatMapTo(linkedSetOf()) { it.keys }
        if (extraInTemplate.isNotEmpty()) {
            throw GradleException(
                "${templateParsed.file} has extra string keys not in translations: ${extraInTemplate.sorted().joinToString()}",
            )
        }
        val nonEmptyTemplateKeys = templateParsed.entries.filterValues { it.isNotEmpty() }.keys
        if (nonEmptyTemplateKeys.isNotEmpty()) {
            throw GradleException(
                "${templateParsed.file} must have empty string values for all keys: ${nonEmptyTemplateKeys.sorted().joinToString()}",
            )
        }

        val beforeSingle = expectedKeys.filter { it.endsWith("_before_number") }
            .map { it.removeSuffix("_before_number") }.toSet()
        val afterSingle = expectedKeys.filter { it.endsWith("_after_number") }
            .map { it.removeSuffix("_after_number") }.toSet()
        if (beforeSingle != afterSingle) {
            throw GradleException(
                "Unpaired *_before_number / *_after_number keys: missingAfter=${(beforeSingle - afterSingle).sorted()}, missingBefore=${(afterSingle - beforeSingle).sorted()}",
            )
        }

        val beforeFirst = expectedKeys.filter { it.endsWith("_before_first_number") }
            .map { it.removeSuffix("_before_first_number") }.toSet()
        val betweenNumbers = expectedKeys.filter { it.endsWith("_between_numbers") }
            .map { it.removeSuffix("_between_numbers") }.toSet()
        val afterSecond = expectedKeys.filter { it.endsWith("_after_second_number") }
            .map { it.removeSuffix("_after_second_number") }.toSet()
        if (beforeFirst != betweenNumbers || beforeFirst != afterSecond) {
            throw GradleException(
                "Unpaired two-number keys: beforeFirst=${beforeFirst.sorted()}, between=${betweenNumbers.sorted()}, afterSecond=${afterSecond.sorted()}",
            )
        }

        val localesDoc = docBuilder.parse(localesConfigFile.get().asFile)
        val localeConfigRoot = localesDoc.documentElement
        if (localeConfigRoot.tagName != "locale-config") {
            throw GradleException("${localesConfigFile.get().asFile} root must be <locale-config>")
        }
        val androidNs = "http://schemas.android.com/apk/res/android"
        val defaultLocale = localeConfigRoot.getAttributeNS(androidNs, "defaultLocale").trim()
        if (defaultLocale.isEmpty()) {
            throw GradleException("${localesConfigFile.get().asFile} must define android:defaultLocale")
        }
        val declaredLocales = linkedSetOf<String>()
        val localeNodes = localeConfigRoot.childNodes
        for (i in 0 until localeNodes.length) {
            val node = localeNodes.item(i)
            if (node.nodeType != org.w3c.dom.Node.ELEMENT_NODE) continue
            val elem = node as org.w3c.dom.Element
            if (elem.tagName == "locale") {
                val name = elem.getAttributeNS(androidNs, "name").trim()
                if (name.isEmpty()) {
                    throw GradleException("${localesConfigFile.get().asFile} has <locale> without android:name")
                }
                declaredLocales.add(name)
            }
        }
        if (defaultLocale !in declaredLocales) {
            throw GradleException("${localesConfigFile.get().asFile}: android:defaultLocale '$defaultLocale' must also be listed in <locale>")
        }
        val resourceLocales = byTag.keys - "default"
        if (declaredLocales != resourceLocales) {
            throw GradleException(
                "${localesConfigFile.get().asFile} locales $declaredLocales do not match resource locales $resourceLocales",
            )
        }

        val manifestDoc = docBuilder.parse(manifestFile.get().asFile)
        val appElems = manifestDoc.getElementsByTagName("application")
        if (appElems.length == 0) {
            throw GradleException("${manifestFile.get().asFile} missing <application>")
        }
        val appElem = appElems.item(0) as org.w3c.dom.Element
        val configuredAttr = appElem.getAttributeNS(androidNs, "localeConfig").trim()
        if (configuredAttr != "@xml/locales_config") {
            throw GradleException(
                "${manifestFile.get().asFile} <application> must specify android:localeConfig=\"@xml/locales_config\" (found '$configuredAttr')",
            )
        }

        val outRoot = outputDir.get().asFile
        if (outRoot.exists()) {
            outRoot.deleteRecursively()
        }
        outRoot.mkdirs()

        for (entry in parsedFiles) {
            if (entry.aliasOf == null) continue
            val target = resolvedConcrete.getValue(entry.tag)
            val targetContent = target.file.readText(Charsets.UTF_8)
            val comment = "<!-- Generated alias of ${target.tag} (${target.dirName}/strings.xml); edit source instead. -->"
            val outputContent = if (targetContent.startsWith("<?xml")) {
                val endDecl = targetContent.indexOf("?>")
                if (endDecl >= 0) {
                    targetContent.substring(0, endDecl + 2) + "\n$comment" + targetContent.substring(endDecl + 2)
                } else {
                    "$comment\n$targetContent"
                }
            } else {
                "$comment\n$targetContent"
            }
            val destFile = File(File(outRoot, entry.dirName), "locale_alias_strings.xml")
            destFile.parentFile.mkdirs()
            destFile.writeText(outputContent, Charsets.UTF_8)
        }
    }
}

abstract class SyncSharedTestSourcesTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun sync() {
        val out = outputDir.get().asFile
        if (out.exists()) out.deleteRecursively()
        inputDir.get().asFile.copyRecursively(out, overwrite = true)
    }
}

androidComponents {
    onVariants { variant ->
        val capName = variant.name.replaceFirstChar { it.uppercase() }
        val aliasTask = tasks.register<GenerateLocaleAliasResourcesTask>(
            "generate${capName}LocaleAliasResources",
        ) {
            stringResourceFiles.from(
                fileTree(layout.projectDirectory.dir("src/main/res")) {
                    include("values*/strings.xml")
                },
            )
            templateFile.set(layout.projectDirectory.file("src/main/strings.template.xml"))
            localesConfigFile.set(layout.projectDirectory.file("src/main/res/xml/locales_config.xml"))
            manifestFile.set(layout.projectDirectory.file("src/main/AndroidManifest.xml"))
        }
        variant.sources.res?.addGeneratedSourceDirectory(
            aliasTask,
            GenerateLocaleAliasResourcesTask::outputDir,
        )
        variant.androidTest?.let { androidTest ->
            val syncSharedTestTask = tasks.register<SyncSharedTestSourcesTask>(
                "sync${capName}SharedAndroidTestSources",
            ) {
                inputDir.set(layout.projectDirectory.dir("src/sharedTest/java"))
            }
            androidTest.sources.kotlin?.addGeneratedSourceDirectory(
                syncSharedTestTask,
                SyncSharedTestSourcesTask::outputDir,
            )
        }
    }
}

tasks.named("preBuild") {
    if (!javaOnlyCodeQl) dependsOn(verifyBundledRuffle)
}

val verifyLibraryLicenses = tasks.register("verifyLibraryLicenses") {
    group = "verification"
    description = "Rejects stale library notices, source copies, or unaudited release/debug dependencies."
    doLast {
        val auditFile = rootProject.file("third_party/library-audit.json")
        @Suppress("UNCHECKED_CAST")
        val audit = groovy.json.JsonSlurper().parse(auditFile) as Map<String, Any>
        fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
        @Suppress("UNCHECKED_CAST")
        val components = audit["components"] as List<Map<String, Any>>
        for (variant in listOf("release", "debug")) {
            val reviewed = components.filter { it["ecosystem"] == "Maven" && (it["variants"] as List<*>).contains(variant) }.associate {
                it["name"].toString() to it["artifactSha256"].toString()
            }
            val resolved = configurations.getByName("${variant}RuntimeClasspath").resolvedConfiguration.resolvedArtifacts
                .associate { it.moduleVersion.id.toString() to digest(it.file) }
            check(resolved == reviewed) { "$variant libraries changed. Regenerate and review tools/library_licenses.py's notices." }
        }
        check(audit["ruffleSourceCommit"] == ruffleCommit.get()) { "Ruffle's license audit is stale" }
        check(audit["ruffleInventorySha256"] == digest(rootProject.file("third_party/ruffle-build/inventory.json"))) { "Ruffle dependency notices are stale" }
        check(audit["libsirenPatchSha256"] == digest(rootProject.file("third_party/patches/libsiren-hardening.patch"))) { "libsiren's source copy is stale" }
        val sirenRoot = rootProject.file("third_party/libsiren")
        val sirenCommit = if (!File(sirenRoot, ".git").exists() && File(sirenRoot, "UPSTREAM_COMMIT").exists()) {
            File(sirenRoot, "UPSTREAM_COMMIT").readText().trim()
        } else providers.exec { workingDir(sirenRoot); commandLine("git", "rev-parse", "HEAD") }.standardOutput.asText.get().trim()
        check(audit["libsirenCommit"] == sirenCommit) { "libsiren's license audit is stale" }
        @Suppress("UNCHECKED_CAST")
        (audit["inputs"] as Map<String, String>).forEach { (name, expected) ->
            val normalized = rootProject.file(name).readText().replace("\r\n", "\n").toByteArray(Charsets.UTF_8)
            check(MessageDigest.getInstance("SHA-256").digest(normalized).joinToString("") { "%02x".format(it) } == expected) {
                "License notice/source input changed: $name. Regenerate the library notices."
            }
        }
        @Suppress("UNCHECKED_CAST")
        (audit["files"] as Map<String, String>).forEach { (name, expected) ->
            check(name.matches(Regex("[A-Za-z0-9_.-]+"))) { "Invalid license bundle filename" }
            check(digest(file("src/main/assets/licenses/$name")) == expected) { "Library notice/source bundle changed: $name" }
        }
    }
}
tasks.named("preBuild") { if (!javaOnlyCodeQl) dependsOn(verifyLibraryLicenses) }

tasks.register("writeDependencyInventory") {
    group = "verification"
    description = "Exports all resolved project and buildscript Maven dependencies for vulnerability scanning."
    val inventory = layout.buildDirectory.file("reports/dependencies/maven.json")
    outputs.file(inventory)
    // Resolve again on scheduled scans; never trust a stale inventory from another build.
    outputs.upToDateWhen { false }
    doLast {
        val classpaths = rootProject.allprojects.flatMap { project ->
            project.configurations.filter { it.isCanBeResolved } +
                project.buildscript.configurations.filter { it.isCanBeResolved }
        }
        val dependencies = classpaths.flatMap { configuration ->
            configuration.resolvedConfiguration.rethrowFailure()
            configuration.incoming.resolutionResult.allComponents.mapNotNull { it.moduleVersion }
        }.filter { it.group.isNotEmpty() && it.version != "unspecified" }
            .distinctBy { "${it.group}:${it.name}:${it.version}" }
            .sortedBy { "${it.group}:${it.name}:${it.version}" }
            .map { mapOf("package" to mapOf("name" to "${it.group}:${it.name}",
                "version" to it.version, "ecosystem" to "Maven")) }
        check(dependencies.isNotEmpty()) { "No resolved dependencies to scan" }
        inventory.get().asFile.apply {
            parentFile.mkdirs()
            writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(
                mapOf("results" to listOf(mapOf("packages" to dependencies))))))
        }
    }
}

val releaseTag = providers.gradleProperty("releaseTag")
    .orElse(providers.environmentVariable("RELEASE_TAG"))
    .orElse(
        providers.exec {
            workingDir(rootProject.projectDir)
            commandLine("git", "describe", "--tags", "--abbrev=0", "--match", "v[0-9]*")
            isIgnoreExitValue = true
        }.standardOutput.asText.map { it.trim() }.filter { it.isNotEmpty() }
    )
    .orNull
val appVersion = if (releaseTag == null) {
    "1.0.0-dev" to 1
} else {
    val match = Regex(
        """v(0|[1-9]\d{0,2})\.(0|[1-9]\d?)\.(0|[1-9]\d?)(?:-(alpha|beta|rc)\.([1-9]\d?))?"""
    ).matchEntire(releaseTag) ?: throw GradleException(
        "Invalid release tag '$releaseTag'. Use vMAJOR.MINOR.PATCH, optionally followed by " +
            "-alpha.N, -beta.N, or -rc.N (MAJOR: 0..209, MINOR/PATCH: 0..99, N: 1..99)."
    )
    val major = match.groupValues[1].toInt()
    val minor = match.groupValues[2].toInt()
    val patch = match.groupValues[3].toInt()
    require(major <= 209) { "Release tag MAJOR must be at most 209 to fit Android's versionCode limit." }
    val stage = when (match.groupValues[4]) {
        "alpha" -> 100
        "beta" -> 200
        "rc" -> 300
        else -> 999
    }
    // Reserve three digits for prereleases so alpha < beta < rc < stable.
    // The largest supported version (209.99.99) stays below 2,100,000,000.
    val code = major * 10_000_000 + minor * 100_000 + patch * 1_000 +
        stage + (match.groupValues[5].toIntOrNull() ?: 0)
    releaseTag.removePrefix("v") to code
}

android {
    namespace = "org.aralozkaya.msngr"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }
    // Java/Kotlin CodeQL extraction compiles all managed sources without needing
    // a native linker. Its default runner cannot download additional NDKs.
    // Keep JNI enabled for normal builds and for C/C++ CodeQL extraction.
    if (!javaOnlyCodeQl && !dependencyInventoryOnly) {
        ndkVersion = "28.2.13676358"
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }

    defaultConfig {
        applicationId = "org.aralozkaya.msngr"
        minSdk = 24
        targetSdk = 37
        versionCode = appVersion.second
        versionName = appVersion.first

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets {
        getByName("test").kotlin.directories.add("src/sharedTest/java")
    }
    lint {
        checkGeneratedSources = true
        // Alias locale files (values/strings.xml, values-en/strings.xml) are generated into
        // variant res directories and strictly validated by GenerateLocaleAliasResourcesTask.
        disable += setOf("ExtraTranslation", "MissingTranslation")
    }
}

dependencies {
    implementation(libs.material.color.utilities)
    implementation(libs.markdown.renderer.m3)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.work.runtime)
    implementation(libs.tink.android)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
