import org.openapitools.generator.gradle.plugin.tasks.GenerateTask

plugins {
    base
    id("ch.acanda.gradle.fabrikt") version "1.15.2"
    kotlin("jvm")
    alias(libs.plugins.openapi.generator)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.data.conversion)
}

tasks.processResources {
    dependsOn(tasks.fabriktGenerate)
}
tasks.compileKotlin {
    dependsOn(tasks.fabriktGenerate)
}

fabrikt {
    generate("workspaces") {
        apiFile = file("specifications/workspace-manager.yaml")
        basePackage = "org.modelix.service.workspaces"
        validationLibrary = NoValidation
        model {
            generate = enabled
            serializationLibrary = Kotlin
            includeCompanionObject = enabled
            // ignoreUnknownProperties = enabled
            extensibleEnums = enabled
        }
        controller {
            generate = enabled
            target = Ktor
            authentication = enabled
            suspendModifier = enabled
            completionStage = enabled
        }
    }
}

val generatedKotlinSrc = project.layout.buildDirectory.dir("generated/sources/fabrikt/src/main/kotlin")
sourceSets["main"].resources.srcDir(generatedKotlinSrc)

fun GenerateTask.commonGenerateConfig() {
    group = "openapi tools"
    inputSpecRootDirectory = layout.projectDirectory.dir("specifications").asFile.absolutePath
    inputSpecRootDirectorySkipMerge = false
}

val generateTypescript by tasks.registering(GenerateTask::class) {
    commonGenerateConfig()
    generatorName = "typescript-fetch"
    outputDir = layout.buildDirectory.dir("generate/typescript-fetch").get().asFile.absolutePath
}

val generateRedux by tasks.registering(GenerateTask::class) {
    commonGenerateConfig()
    generatorName = "typescript-redux-query"
    outputDir = layout.buildDirectory.dir("generate/typescript-redux-query").get().asFile.absolutePath
}
