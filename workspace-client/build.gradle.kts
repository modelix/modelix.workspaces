import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

description = "Downloads modules from a workspace before starting MPS"

plugins {
    application
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.shadow)
}

application {
    mainClass.set("org.modelix.workspace.client.MainKt")
    applicationDefaultJvmArgs = listOf("-Dmodelix.workspace.server=http://localhost:28104/")
}

tasks.withType<ShadowJar> {
    archiveVersion.set("latest")
}

// <editor-fold desc="IDEA plugins">
val modelixIdeaPlugins: Configuration by configurations.creating
dependencies {
    modelixIdeaPlugins(libs.bundles.modelix.mpsPlugins.all)
}
val copyMpsPlugins by tasks.registering(Sync::class) {
    from(modelixIdeaPlugins.resolve().map { zipTree(it) })
    into(project.layout.buildDirectory.dir("mps-plugins"))
}
tasks.assemble {
    dependsOn(copyMpsPlugins)
}
// </editor-fold>

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.auth)
    implementation(libs.logback.classic)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kaml)
    implementation(libs.jgit)
    implementation(libs.maven.invoker)
    implementation(libs.zt.zip)
    implementation(libs.commons.text)
    implementation(project(":workspace-manager"))
    implementation(project(":workspaces"))

    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
}

tasks.getByName<Test>("test") {
    useJUnitPlatform()
}