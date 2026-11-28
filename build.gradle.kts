buildscript {
    // Override transitive versions in AGP until its classpath includes the reviewed fixes.
    configurations.named("classpath") {
        resolutionStrategy.force(
            "com.google.guava:guava:33.8.0-jre",
            "org.apache.commons:commons-lang3:3.22.0",
            "org.bitbucket.b_c:jose4j:0.9.7",
            "org.bouncycastle:bcpkix-jdk18on:1.87",
            "org.bouncycastle:bcprov-jdk18on:1.87",
            "org.bouncycastle:bcutil-jdk18on:1.87",
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
