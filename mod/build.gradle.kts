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

val nativeProjectDir = rootProject.file("../native")
val nativeLibBaseName = "mcap_native"

fun currentOs(): String {
    val os = System.getProperty("os.name").lowercase()
    return when {
        os.contains("mac") || os.contains("darwin") -> "macos"
        os.contains("win") -> "windows"
        os.contains("nux") || os.contains("linux") -> "linux"
        else -> error("Unsupported OS: $os")
    }
}

fun currentArch(): String {
    val arch = System.getProperty("os.arch").lowercase()
    return when {
        arch.contains("aarch64") || arch.contains("arm64") -> "aarch64"
        arch.contains("x86_64") || arch.contains("amd64") -> "x86_64"
        else -> error("Unsupported arch: $arch")
    }
}

fun nativeFileName(os: String): String {
    return when (os) {
        "macos" -> "lib${nativeLibBaseName}.dylib"
        "linux" -> "lib${nativeLibBaseName}.so"
        "windows" -> "${nativeLibBaseName}.dll"
        else -> error("Unsupported OS: $os")
    }
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings("net.fabricmc:yarn:$yarnMappings:v2")
    modImplementation("net.fabricmc:fabric-loader:$loaderVersion")
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fabricVersion")

    modImplementation("net.fabricmc:fabric-language-kotlin:$fabricKotlinVersion")

    // Arrow IPC for ML pipeline (tick-level game state export)
    implementation("org.apache.arrow:arrow-vector:14.0.1") {
        exclude(group = "io.netty") // Minecraft already bundles Netty
        exclude(group = "com.fasterxml.jackson.core") // Minecraft bundles Jackson
    }
    implementation("org.apache.arrow:arrow-memory-unsafe:14.0.1")
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
}

tasks {
    val buildNative by registering(Exec::class) {
        group = "build"
        description = "Build the Rust JNI native library via Cargo."
        workingDir = nativeProjectDir
        commandLine("cargo", "build")
    }

    val copyNative by registering(Copy::class) {
        group = "build"
        description = "Copy the built native library into mod resources so NativeBridge can extract it."
        dependsOn(buildNative)

        val os = currentOs()
        val arch = currentArch()
        val outName = nativeFileName(os)

        val builtPath = when (os) {
            "windows" -> nativeProjectDir.resolve("target/debug/$outName")
            else -> nativeProjectDir.resolve("target/debug/$outName")
        }

        from(builtPath)
        into(project.layout.projectDirectory.dir("src/main/resources/natives/$os-$arch"))
        rename { outName }
    }

    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        kotlinOptions.jvmTarget = "17"
    }

    processResources {
        dependsOn(copyNative)
        inputs.property("version", project.version)
        filesMatching("fabric.mod.json") {
            expand(mapOf("version" to project.version))
        }
    }
}
