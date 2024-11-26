
description = "Allows multiple clients to work on the same set of modules from different sources"

plugins {
    application
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

application {
    mainClass.set("io.ktor.server.netty.EngineMain")
}

val mpsPlugins by configurations.registering

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
    implementation(libs.modelix.model.server)
    implementation(libs.ktor.client.cio)
    implementation(project(":workspaces"))
    implementation(project(":gitui"))
    implementation(libs.modelix.authorization)
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)

    mpsPlugins(libs.modelix.mpsPlugins.legacySync)
    mpsPlugins(libs.modelix.mpsPlugins.diff)
    mpsPlugins(libs.modelix.mpsPlugins.generator)
}

tasks.getByName<Test>("test") {
    useJUnitPlatform()
}

tasks.named("assemble") {
    dependsOn("installDist")
}

val copyClient = tasks.register("copyClient", Sync::class.java) {
    dependsOn(project(":workspace-client").tasks.named("distTar"))
    from(project(":workspace-client").layout.buildDirectory.file("distributions/workspace-client.tar"))
    from(project(":workspace-client").layout.projectDirectory.file("pre-startup.sh"))
    into(project.layout.buildDirectory.dir("client/org/modelix/workspace/client"))
}

val copyMpsPlugins = tasks.register("copyMpsPlugins", Sync::class.java) {
    dependsOn(":workspace-client-plugin:buildPlugin")
    from(mpsPlugins)
    from(project(":workspace-client-plugin").layout.buildDirectory.dir("distributions/workspace-client-plugin.zip"))
    into(project.layout.buildDirectory.dir("client/org/modelix/workspace/mpsplugins"))
    rename { fileName ->
        println(fileName)
        // strip version number
        val artifact = mpsPlugins.get().resolvedConfiguration.resolvedArtifacts.find { it.file.name == fileName }
            ?: return@rename fileName
        artifact.name + "." + artifact.extension
    }
}

tasks.processResources {
    dependsOn(copyClient)
    dependsOn(copyMpsPlugins)
}

sourceSets {
    main {
        resources {
            srcDir(project.layout.buildDirectory.dir("client"))
        }
    }
}
