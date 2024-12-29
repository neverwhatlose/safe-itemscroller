import java.text.SimpleDateFormat
import java.util.Date

plugins {
    id("fabric-loom") version "1.8-SNAPSHOT"
}

repositories {
    maven("https://masa.dy.fi/maven")
    maven("https://maven.terraformersmc.com/releases/")
    maven("https://jitpack.io")
}

tasks.withType<Jar>().configureEach {
    archiveBaseName.set("${project.property("mod_file_name")}-${project.property("minecraft_version_out")}")
}


dependencies {
    minecraft("com.mojang:minecraft:${project.property("minecraft_version")}")
    mappings("net.fabricmc:yarn:${project.property("mappings_version")}:v2")
    modImplementation("net.fabricmc:fabric-loader:${project.property("fabric_loader_version")}")
    implementation("com.google.code.findbugs:jsr305:3.0.2")

    //modImplementation("fi.dy.masa.malilib:malilib-fabric-${project.property("minecraft_version_out")}:${project.property("malilib_version")}")
    modImplementation("com.github.sakura-ryoko:malilib:${project.property("malilib_version")}")

    // Fabric API. This is technically optional, but you probably want it anyway.
    //modCompile("net.fabricmc.fabric-api:fabric-api:" + project.property("fabric_version"))

    modCompileOnly("com.terraformersmc:modmenu:${project.property("mod_menu_version")}")
}

group = "${project.property("group")}.${project.property("mod_id")}"
tasks.withType<Jar>().configureEach {
    archiveBaseName.set("${project.property("mod_file_name")}-${project.property("minecraft_version_out")}")
}
var version: String = project.property("mod_version") as String

if (version.endsWith("-dev")) {
    version += "." + SimpleDateFormat("yyyyMMdd.HHmmss").format(Date())
}

// Process resources
val processResources = tasks.named<Copy>("processResources") {
    exclude("**/*.xcf", "**/xcf")

    inputs.property("mod_version", project.property("mod_version"))

    filesMatching("fabric.mod.json") {
        expand(mapOf("mod_version" to project.property("mod_version")))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = true
    //isReproducibleFileOrder = true
}
