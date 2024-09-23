plugins {
    application
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.shadow)
}

application {
    mainClass.set("org.modelix.workspace.gitsync.checkout.MainKt")
}

dependencies {
    implementation(libs.kotlin.logging)
    implementation(libs.kotlinx.coroutines.debug)
    implementation(libs.logback.classic)
    implementation(libs.commons.configuration)

    implementation(project(":workspaces"))

    implementation(libs.modelix.authorization)
    implementation(libs.modelix.model.client)
    implementation(libs.modelix.bulk.model.sync.lib) {
        because("It used to export data from model server as JSONs.")
    }
    implementation(libs.jgit)
}

tasks.getByName<Test>("test") {
    useJUnitPlatform()
}

val buildImages by tasks.registering(Exec::class) {
    dependsOn(tasks.shadowDistZip)
    environment("MODELIX_WORKSPACES_VERSION" to rootProject.version)
    commandLine = listOf("sh", "-c", "./docker-build.sh")
}