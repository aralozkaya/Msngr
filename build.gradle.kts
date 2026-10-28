// Top-level build file where you can add configuration options common to all sub-projects/modules.
buildscript {
    // AGP's published classpath still selects vulnerable transitive versions.
    // Keep build tooling in the same vulnerability gate as app dependencies.
    configurations.named("classpath") {
        resolutionStrategy.force(
            "com.google.guava:guava:33.7.2-jre",
            "org.apache.commons:commons-lang3:3.21.0",
            "org.bitbucket.b_c:jose4j:0.9.7",
            "org.bouncycastle:bcpkix-jdk18on:1.86",
            "org.bouncycastle:bcprov-jdk18on:1.86",
            "org.bouncycastle:bcutil-jdk18on:1.86",
            "org.jdom:jdom2:2.0.6.1",
            "org.apache.httpcomponents:httpclient:4.5.14",
            "org.apache.httpcomponents:httpmime:4.5.14",
        )
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// Android lint runs in its own tool configuration, outside the plugin classpath.
val patchedBuildDependencies = buildscript.configurations.getByName("classpath")
    .resolutionStrategy.forcedModules.map { "${it.group}:${it.name}:${it.version}" }.toTypedArray()
allprojects {
    configurations.matching {
        it.name == "androidLintTool" || it.name.startsWith("lint", ignoreCase = true)
    }.configureEach {
        resolutionStrategy.force(*patchedBuildDependencies)
    }
}
