
buildscript {
    // Make sure the sync plugin is built before the script is evaluated,
    // because the IntelliJ Gradle plugin expects that.
    val syncPluginZip by configurations.creating {
        attributes.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, ArtifactTypeDefinition.ZIP_TYPE)
    }
    dependencies { syncPluginZip(libs.modelix.syncPlugin3) }

    dependencies {
        classpath(libs.modelix.mps.build.tools)
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
        exclude("org.slf4j", "slf4j-api")
    }

    fun implementationWithoutBundled(dependencyNotation: Provider<*>) {
        implementation(dependencyNotation) {
            excludedBundledLibraries()
        }
    }
}

// copy and extract sync plugin
val syncPluginZip by configurations.creating {
    attributes {
        attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, ArtifactTypeDefinition.ZIP_TYPE)
    }
}
dependencies {
    syncPluginZip(libs.modelix.syncPlugin3)
}
val pluginDependenciesDir = layout.buildDirectory.dir("plugin-dependencies")
sync {
    from(zipTree({ syncPluginZip.singleFile }))
    into(pluginDependenciesDir)
}
val syncPluginDir = pluginDependenciesDir.get().asFile.resolve("mps-sync-plugin3")

val supportedMPSVersions = project.properties["mpsMajorVersions"].toString().split(",").sorted()
fun String.toPlatformVersion(): String = replace(Regex("""20(\d\d)\.(\d+).*"""), "$1$2")

// Configure Gradle IntelliJ Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-gradle-intellij-plugin.html
intellij {
    version = supportedMPSVersions.first()
    instrumentCode = false
    plugins.set(listOf(syncPluginDir))
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
