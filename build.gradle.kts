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

dependencies {
    intellijPlatform {
        // Build against the PyCharm install already on disk (the same one the inspect.sh test rig uses).
        local(providers.environmentVariable("PYCHARM_HOME").orElse("/home/yamu/side-hustle/.tools/pycharm-2026.2.3"))
        bundledPlugin("PythonCore")
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion { sinceBuild = "252" }
    }
    buildSearchableOptions = false
    instrumentCode = false
}
