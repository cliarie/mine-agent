package dev.replaycraft.mcap.video

import net.minecraft.client.MinecraftClient
import org.lwjgl.opengl.GL11
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * Records Minecraft gameplay to video by capturing OpenGL framebuffer contents
 * each frame and piping raw RGB data to ffmpeg for encoding.
 *
 * Usage:
 *   1. Call startRecording(outputPath) to begin
 *   2. Call captureFrame() each render frame (from HudRenderCallback or similar)
 *   3. Call stopRecording() to finalize the video
 *
 * The output is an MP4 file encoded with libx264 at the game's current resolution.
 */
class VideoRecorder {

    @Volatile
    var isRecording: Boolean = false
        private set

    private var ffmpegProcess: Process? = null
    private var ffmpegStdin: OutputStream? = null
    private var frameBuffer: ByteBuffer? = null
    private var width: Int = 0
    private var height: Int = 0
    private var outputPath: String = ""
    private var frameCount: Long = 0

    /**
     * Start recording video to the given output path.
     * @param output Path to the output MP4 file
     * @param fps Frames per second (default 20 to match tick rate)
     */
    fun startRecording(output: String, fps: Int = 20) {
        if (isRecording) return

        val client = MinecraftClient.getInstance()
        width = client.window.framebufferWidth
        height = client.window.framebufferHeight
        outputPath = output
        frameCount = 0

        // Allocate pixel buffer (RGB, 3 bytes per pixel)
        frameBuffer = ByteBuffer.allocateDirect(width * height * 3)

        // Ensure output directory exists
        File(output).parentFile?.mkdirs()

        // Start ffmpeg process
        val cmd = listOf(
            "ffmpeg",
            "-y",                          // overwrite output
            "-f", "rawvideo",              // input format
            "-pixel_format", "rgb24",      // pixel format
            "-video_size", "${width}x${height}",
            "-framerate", fps.toString(),
            "-i", "pipe:0",                // read from stdin
            "-c:v", "libx264",             // H.264 codec
            "-preset", "fast",             // encoding speed
            "-crf", "23",                  // quality (lower = better)
            "-pix_fmt", "yuv420p",         // output pixel format
            "-vf", "vflip",               // OpenGL is bottom-up, video is top-down
            output
        )

        try {
            val pb = ProcessBuilder(cmd)
            pb.redirectErrorStream(true)
            ffmpegProcess = pb.start()
            ffmpegStdin = ffmpegProcess!!.outputStream

            isRecording = true
            println("[MCAP Video] Recording started: ${width}x${height} @ ${fps}fps -> $output")
        } catch (e: Exception) {
            println("[MCAP Video] Failed to start ffmpeg: ${e.message}")
            println("[MCAP Video] Make sure ffmpeg is installed and in PATH")
            cleanup()
        }
    }

    /**
     * Capture the current OpenGL framebuffer and write it to ffmpeg.
     * Should be called once per render frame while recording.
     */
    fun captureFrame() {
        if (!isRecording) return
        val buf = frameBuffer ?: return
        val out = ffmpegStdin ?: return

        try {
            buf.clear()

            // Read pixels from the default framebuffer (the screen)
            GL11.glReadPixels(0, 0, width, height, GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE, buf)

            // Write raw pixel data to ffmpeg stdin
            val bytes = ByteArray(width * height * 3)
            buf.get(bytes)
            out.write(bytes)

            frameCount++
        } catch (e: Exception) {
            println("[MCAP Video] Frame capture error: ${e.message}")
            stopRecording()
        }
    }

    /**
     * Stop recording and finalize the video file.
     */
    fun stopRecording() {
        if (!isRecording) return
        isRecording = false

        println("[MCAP Video] Stopping recording... ($frameCount frames captured)")

        try {
            ffmpegStdin?.flush()
            ffmpegStdin?.close()
        } catch (_: Exception) {}

        try {
            ffmpegProcess?.waitFor()
        } catch (_: Exception) {}

        cleanup()
        println("[MCAP Video] Recording saved to: $outputPath")
    }

    private fun cleanup() {
        ffmpegStdin = null
        ffmpegProcess = null
        frameBuffer = null
        isRecording = false
    }

    /**
     * Get a default output path for video recordings.
     */
    fun defaultOutputPath(): String {
        val client = MinecraftClient.getInstance()
        val videosDir = File(client.runDirectory, "mcap_replay/videos")
        videosDir.mkdirs()
        val timestamp = System.currentTimeMillis()
        return File(videosDir, "replay_${timestamp}.mp4").absolutePath
    }
}
