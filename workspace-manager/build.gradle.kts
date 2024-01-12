import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

description = "Allows multiple clients to work on the same set of modules from different sources"

plugins {
    application
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.shadow)
}

application {
    mainClass.set("io.ktor.server.netty.EngineMain")
}

tasks.withType<ShadowJar> {
    archiveVersion.set("latest")
}

dependencies {
    implementation(libs.bundles.ktor.server)
    implementation(libs.logback.classic)
    implementation(libs.kotlin.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kaml)
    implementation(libs.kotlinx.coroutines.core)

    implementation(libs.kubernetes.client)
    implementation(libs.jgit)
    implementation(libs.maven.invoker)
    implementation(libs.zt.zip)
    implementation(libs.commons.text)
    implementation(libs.jasypt)
    implementation(libs.modelix.model.client)
    implementation(project(":workspaces"))
    implementation(project(":gitui"))
    implementation(libs.modelix.authorization)
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
}

tasks.getByName<Test>("test") {
    useJUnitPlatform()
}

tasks.named("assemble") {
    dependsOn("installDist")
}
