plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val releaseTag = providers.gradleProperty("releaseTag")
    .orElse(providers.environmentVariable("RELEASE_TAG"))
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
        version = release(37)
    }
    ndkVersion = "28.2.13676358"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
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
