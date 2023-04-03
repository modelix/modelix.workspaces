import java.text.SimpleDateFormat

fun githubCredentials(): Pair<String, String>? {
    if (project.hasProperty("modelix.github.user") && project.hasProperty("modelix.github.token")) {
        return Pair(
            project.findProperty("modelix.github.user").toString(),
            project.findProperty("modelix.github.token").toString()
        )
    } else if (project.hasProperty("gpr.user") && project.hasProperty("gpr.key")) {
        return Pair(project.findProperty("gpr.user").toString(), project.findProperty("gpr.key").toString())
    } else if (System.getenv("GITHUB_ACTOR") != null && System.getenv("GITHUB_TOKEN") != null) {
        return Pair(System.getenv("GITHUB_ACTOR"), System.getenv("GITHUB_TOKEN"))
    } else {
        logger.error("Please specify your github username (gpr.user) and access token (gpr.key) in ~/.gradle/gradle.properties")
        return null
    }
}

buildscript {
    repositories {
        /* It is useful to have the central maven repo before the Itemis's one
           as it is more reliable */
        mavenLocal()
        maven { url = uri("https://repo.maven.apache.org/maven2") }
        maven { url = uri("https://plugins.gradle.org/m2/") }
        mavenCentral()
        maven { url = uri("https://artifacts.itemis.cloud/repository/maven-mps/") }
        maven { url = uri("https://projects.itemis.de/nexus/content/repositories/mbeddr") }
    }

    dependencies {
        classpath("de.itemis.mps:mps-gradle-plugin:mps20211.1.5.281.69e6edc")
        classpath("com.google.googlejavaformat:google-java-format:1.8+")
    }
}
plugins {
    id("com.diffplug.gradle.spotless") version "4.5.1" apply false
    kotlin("jvm") apply false
    kotlin("multiplatform") apply false
    kotlin("plugin.serialization") apply false
    `maven-publish`
    id("com.palantir.git-version") version "0.13.0"
}



fun computeVersion(): Any {
    val versionFile = file("version.txt")
    val gitVersion: groovy.lang.Closure<String> by extra
    return if (versionFile.exists()) {
        versionFile.readText().trim()
    } else {
        gitVersion().let{ if (it.endsWith("-SNAPSHOT")) it else "$it-SNAPSHOT" }.also { versionFile.writeText(it) }
    }
}

group = "org.modelix"
description = "Projectional Editor"
version = computeVersion()
println("Version: $version")
subprojects {
    apply(plugin = "maven-publish")
    version = rootProject.version
    group = rootProject.group

    repositories {
        mavenLocal()
        maven { url = uri("https://artifacts.itemis.cloud/repository/maven-mps/") }
        maven { url = uri("https://maven.pkg.jetbrains.space/public/p/kotlinx-html/maven") }
        mavenCentral()
    }

    publishing {
        repositories {
            if (project.hasProperty("artifacts.itemis.cloud.user")) {
                maven {
                    name = "itemis"
                    url = if (version.toString().contains("SNAPSHOT"))
                        uri("https://artifacts.itemis.cloud/repository/maven-mps-snapshots/")
                    else
                        uri("https://artifacts.itemis.cloud/repository/maven-mps-releases/")
                    credentials {
                        username = project.findProperty("artifacts.itemis.cloud.user").toString()
                        password = project.findProperty("artifacts.itemis.cloud.pw").toString()
                    }
                }
            }
        }
    }
}
