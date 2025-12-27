package dev.replaycraft.mcap.native

import java.io.File
import java.nio.file.Files

object NativeBridge {
    private const val LIB_BASE = "mcap_native"

    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val libPath = extractNative()
            System.load(libPath.absolutePath)
            loaded = true
        }
    }

    @Volatile private var loaded = false

    private fun extractNative(): File {
        val (os, arch, ext, prefix) = detectPlatform()
        val resourcePath = "/natives/$os-$arch/${prefix}${LIB_BASE}.$ext"
        val ins = NativeBridge::class.java.getResourceAsStream(resourcePath)
            ?: error("Native library not found in jar: $resourcePath")

        val dir = Files.createTempDirectory("mcap_native").toFile()
        dir.deleteOnExit()
        val out = File(dir, "${prefix}${LIB_BASE}.$ext")
        out.deleteOnExit()
        ins.use { input -> out.outputStream().use { input.copyTo(it) } }
        return out
    }

    private data class Platform(val os: String, val arch: String, val ext: String, val prefix: String)

    private fun detectPlatform(): Platform {
        val osName = System.getProperty("os.name").lowercase()
        val archName = System.getProperty("os.arch").lowercase()

        val os = when {
            osName.contains("mac") || osName.contains("darwin") -> "macos"
            osName.contains("win") -> "windows"
            osName.contains("nux") || osName.contains("linux") -> "linux"
            else -> error("Unsupported OS: $osName")
        }

        val arch = when {
            archName.contains("aarch64") || archName.contains("arm64") -> "aarch64"
            archName.contains("x86_64") || archName.contains("amd64") -> "x86_64"
            else -> error("Unsupported arch: $archName")
        }

        return when (os) {
            "macos" -> Platform("macos", arch, "dylib", "lib")
            "linux" -> Platform("linux", arch, "so", "lib")
            "windows" -> Platform("windows", arch, "dll", "")
            else -> error("Unsupported OS: $os")
        }
    }

    fun defaultManifestJson(): ByteArray {
        return "{\"schema_version\":1,\"mc_version\":\"1.20.1\",\"yarn\":\"1.20.1+build.10\"}".toByteArray()
    }

    // Capture functions
    external fun nativeInitSession(manifestJson: ByteArray, baseDir: String): Long
    external fun nativeAppendTicks(handle: Long, startTick: Int, packed: ByteArray, len: Int)
    external fun nativeCloseSession(handle: Long)

    // Replay functions
    external fun nativeOpenReplay(sessionPath: String): Long
    external fun nativeGetReplayMaxTick(handle: Long): Int
    external fun nativeReadTick(handle: Long, tick: Int): ByteArray
    external fun nativeCloseReplay(handle: Long)

    init {
        // JNI expects this name.
        // Adjust if you rename the Kotlin package/class.
        // We load the native in ensureLoaded().
    }
}
