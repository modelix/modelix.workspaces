
description = "Run MPS headless"

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("application")
    id("maven-publish")
}

application {
    mainClass.set("org.modelix.headlessmps.ModelImportMain")
}

val kotlinxSerializationVersion: String by rootProject
dependencies {
    compileOnly("com.jetbrains:mps-environment:2021.1.4")
    compileOnly("com.jetbrains:platform-api:2021.1.4")
    compileOnly("com.jetbrains:mps-platform:2021.1.4")
    compileOnly("com.jetbrains:util:2021.1.4")
    compileOnly("com.jetbrains:mps-core:2021.1.4")
    compileOnly("com.jetbrains:mps-openapi:2021.1.4")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinxSerializationVersion")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.6.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.6.0")
}

tasks.getByName<Test>("test") {
    useJUnitPlatform()
}

val copyDependencies = task("copyDependencies", Copy::class) {
    from(configurations.runtimeClasspath)
    into(File(File("build"), "libs"))
}

tasks.getByName("jar") {
    dependsOn(copyDependencies)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}