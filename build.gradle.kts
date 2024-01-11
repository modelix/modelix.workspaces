plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.gitVersion)
}

version = computeVersion()
description = "Components to run MPS in the cloud with Kubernetes"

fun computeVersion(): Any {
    val versionFile = file("workspaces-version.txt")
    return if (versionFile.exists()) {
        versionFile.readText().trim()
    } else {
        val gitVersion: groovy.lang.Closure<String> by extra
        val version = gitVersion()
        versionFile.writeText(version)
    }
}

subprojects {
    repositories {
        // It is useful to have the central maven repo before the Itemis's one as it is more reliable
        mavenCentral()
        maven { url = uri("https://artifacts.itemis.cloud/repository/maven-mps/") }
    }
}