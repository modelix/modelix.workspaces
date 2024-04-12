import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    application
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.shadow)
}

group = "org.modelix"
description = "Starts a separate MPS instance for each user"

application {
    mainClass.set("org.modelix.instancesmanager.Main")
}

repositories {
    mavenCentral()
}

tasks.withType<ShadowJar> {
    archiveVersion.set("latest")
}

dependencies {
    implementation(libs.commons.codec)
    implementation(libs.jetty.server)
    implementation(libs.jetty.proxy)
    implementation(libs.jetty.servlet)
    implementation(libs.jetty.websocket.servlet)
    implementation(libs.jetty.websocket.client)
    implementation(libs.jetty.websocket.server)
    implementation(libs.jgit)
    implementation(libs.kohsuke.github.api)
    implementation(libs.modelix.model.client)
    implementation(project(":workspaces"))
    implementation(libs.modelix.authorization)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.html.builder)
    implementation(libs.kotlin.logging)
    implementation(libs.kubernetes.client)
    implementation(libs.commons.io)
    implementation(libs.commons.lang3)
    implementation(libs.json)
    implementation(libs.auth0.jwt)

    runtimeOnly(libs.slf4j.simple)

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}