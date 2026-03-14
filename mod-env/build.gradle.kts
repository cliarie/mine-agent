plugins {
    id("fabric-loom") version "1.6.12"
    kotlin("jvm") version "1.9.23"
}

version = providers.gradleProperty("mod_version").get()
group = providers.gradleProperty("maven_group").get()

val minecraftVersion = providers.gradleProperty("minecraft_version").get()
val yarnMappings = providers.gradleProperty("yarn_mappings").get()
val loaderVersion = providers.gradleProperty("loader_version").get()
val fabricVersion = providers.gradleProperty("fabric_version").get()
val fabricKotlinVersion = providers.gradleProperty("fabric_kotlin_version").get()

repositories {
    maven("https://maven.fabricmc.net/")
    mavenCentral()
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings("net.fabricmc:yarn:$yarnMappings:v2")
    modImplementation("net.fabricmc:fabric-loader:$loaderVersion")
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fabricVersion")

    modImplementation("net.fabricmc:fabric-language-kotlin:$fabricKotlinVersion")

    // WebSocket server
    implementation("org.java-websocket:Java-WebSocket:1.5.6")
    include("org.java-websocket:Java-WebSocket:1.5.6")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

base {
    archivesName.set("mine-env")
}

loom {
    splitEnvironmentSourceSets()

    mods {
        create("mine_env") {
            sourceSet(sourceSets["main"])
            sourceSet(sourceSets["client"])
        }
    }
}

tasks {
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        kotlinOptions.jvmTarget = "17"
    }

    processResources {
        inputs.property("version", project.version)
        filesMatching("fabric.mod.json") {
            expand(mapOf("version" to project.version))
        }
    }
}
