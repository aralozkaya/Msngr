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
val ruffleCommit = providers.exec {
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

tasks.named("preBuild") {
    if (!javaOnlyCodeQl) dependsOn(verifyBundledRuffle)
}

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
        version = release(37) { minorApiLevel = 1 }
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
        getByName("androidTest").kotlin.directories.add("src/sharedTest/java")
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
