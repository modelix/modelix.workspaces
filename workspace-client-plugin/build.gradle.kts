
buildscript {
    dependencies {
        classpath("org.modelix.mps:build-tools-lib:1.7.3")
    }
}

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij") version "1.17.4"
}

group = "org.modelix.mps"

kotlin {
    jvmToolchain(17)
}

dependencies {
    compileOnly(kotlin("stdlib"))
    fun ModuleDependency.excludedBundledLibraries() {
        exclude(group = "org.jetbrains.kotlin")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-jdk8")
    }

    fun implementationWithoutBundled(dependencyNotation: Provider<*>) {
        implementation(dependencyNotation) {
            excludedBundledLibraries()
        }
    }
}

val supportedMPSVersions = project.properties["mpsMajorVersions"].toString().split(",").sorted()
fun String.toPlatformVersion(): String = replace(Regex("""20(\d\d)\.(\d+).*"""), "$1$2")

// Configure Gradle IntelliJ Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-gradle-intellij-plugin.html
intellij {
    version = supportedMPSVersions.first()
    instrumentCode = false
}

tasks {
    patchPluginXml {
        sinceBuild.set(supportedMPSVersions.first().toPlatformVersion())
        untilBuild.set(supportedMPSVersions.last().toPlatformVersion() + ".*")
    }

    buildSearchableOptions {
        enabled = false
    }

    runIde {
        systemProperty("idea.platform.prefix", "Idea")
        autoReloadPlugins.set(true)
    }
}
