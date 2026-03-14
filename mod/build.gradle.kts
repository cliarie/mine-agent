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

    // JSON parsing
    implementation("com.google.code.gson:gson:2.10.1")
    include("com.google.code.gson:gson:2.10.1")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

loom {
    splitEnvironmentSourceSets()

    mods {
        create("mcap_replay") {
            sourceSet(sourceSets["main"])
            sourceSet(sourceSets["client"])
        }
    }

    runConfigs {
        configureEach {
            // Pass through dev auth/server env vars to the game process
            listOf("IODINE_DEV_TOKEN", "IODINE_SERVER_URL").forEach { key ->
                System.getenv(key)?.let { environmentVariable(key, it) }
            }
            // Default dev token (HS256, secret=iodine-jwt-secret-change-in-production, expires 2030)
            // Only applied if IODINE_DEV_TOKEN is not already set above
            if (System.getenv("IODINE_DEV_TOKEN") == null) {
                environmentVariable(
                    "IODINE_DEV_TOKEN",
                    "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9." +
                    "eyJpc3MiOiJpb2RpbmUtd2Vic2VydmVyIiwiYXVkIjoiaW9kaW5lLWNsaWVudCIsInN1YiI6IjAwMDAwMDAwLTAwMDAtMDAwMC0wMDAwLTAwMDAwMDAwMDAwMCIsInVzZXJuYW1lIjoiRGV2UGxheWVyIiwiaWF0IjoxNzczNTE3NzQyLCJleHAiOjE4OTM0NTYwMDB9." +
                    "5WKrnHAC0vND8i6y-VN6fGzJIUpfCARiCj-U6cY_98Q"
                )
            }
        }
    }
}

// Allow test source set to see client classes (analytics, ML, capture)
sourceSets["test"].compileClasspath += sourceSets["client"].output
sourceSets["test"].runtimeClasspath += sourceSets["client"].output

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
