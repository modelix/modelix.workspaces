plugins {
    application
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.shadow)
}

application {
    mainClass.set("org.modelix.workspace.gitsync.push.MainKt")
}

dependencies {
    implementation(libs.kotlin.logging)
    implementation(libs.kotlinx.coroutines.debug)
    implementation(libs.logback.classic)
    implementation(libs.commons.configuration)

    implementation(project(":workspaces"))

    implementation(libs.jgit)

    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
}

tasks.getByName<Test>("test") {
    useJUnitPlatform()
}

val buildImages by tasks.registering(Exec::class) {
    dependsOn(tasks.shadowDistZip)
    environment("MODELIX_WORKSPACES_VERSION" to rootProject.version)
    commandLine = listOf("sh", "-c", "./docker-build.sh")
}