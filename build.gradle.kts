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


dependencies {
    minecraft("com.mojang:minecraft:${project.property("minecraft_version")}")
    mappings("net.fabricmc:yarn:${project.property("mappings_version")}:v2")
    modImplementation("net.fabricmc:fabric-loader:${project.property("fabric_loader_version")}")
    implementation("com.google.code.findbugs:jsr305:3.0.2")

    //modImplementation("fi.dy.masa.malilib:malilib-fabric-${project.property("minecraft_version_out")}:${project.property("malilib_version")}")
    modImplementation("com.github.sakura-ryoko:malilib:${project.property("malilib_version")}")

    // Fabric API. This is technically optional, but you probably want it anyway.
    modCompileOnly("net.fabricmc.fabric-api:fabric-api:" + project.property("fabric_api_version"))

    modCompileOnly("com.terraformersmc:modmenu:${project.property("mod_menu_version")}")
}

group = "${project.property("group")}.${project.property("mod_id")}"

var version: String = project.property("mod_version") as String

tasks.named<net.fabricmc.loom.task.RemapJarTask>("remapJar") {
    val modFileName = project.property("mod_file_name")
    val minecraftVersion = project.property("minecraft_version_out")
    val modVersion = project.property("mod_version")
    val isDev = modVersion.toString().endsWith("-dev")

    val buildDate = if (isDev) {
        "-dev-" + SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
    } else {
        ""
    }

    archiveBaseName.set("$modFileName-$minecraftVersion-$modVersion$buildDate")
    destinationDirectory.set(file(layout.buildDirectory.dir("libs")))
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

