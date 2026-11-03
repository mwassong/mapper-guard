import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.20"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "sqlatoolkit"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

kotlin { jvmToolchain(21) }

// Locally we build against the PyCharm install on disk (the same one scripts/real_world_check.sh drives).
// CI has no such install, so it falls back to a downloaded IDE + the Python plugin.
val pycharmHome: String =
    providers.environmentVariable("PYCHARM_HOME").orNull ?: "/home/yamu/side-hustle/.tools/pycharm-2026.2.3"
val hasLocalIde = file(pycharmHome).isDirectory

dependencies {
    intellijPlatform {
        if (hasLocalIde) {
            local(pycharmHome)
            bundledPlugin("PythonCore")
        } else {
            // UNVERIFIED until CI runs once — no GitHub remote exists for this repo yet.
            intellijIdeaCommunity("2026.2")
            plugin("PythonCore", "262.10968.63")
        }
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
    testLogging { events("passed", "failed", "skipped") }
}

intellijPlatform {
    pluginVerification {
        // JetBrains' official compatibility verifier, run against the local install
        // (`recommended()` downloads several full IDEs and timed out at 40 minutes here).
        ides { if (hasLocalIde) local(pycharmHome) else recommended() }
    }
    pluginConfiguration {
        ideaVersion { sinceBuild = "252" }
    }
    buildSearchableOptions = false
    instrumentCode = false
}
