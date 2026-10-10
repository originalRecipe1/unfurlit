package io.github.originalrecipe1.unfurlit.data.extractor.ytdlp

import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.yausername.youtubedl_android.YoutubeDL
import io.github.originalrecipe1.unfurlit.BuildConfig
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in subprocess timing. The caller must block network access for the app UID. */
class RuntimeStartupTimingTest {
    @Test(timeout = 600_000)
    fun measureOfflineStartup() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("runtimeTiming") == "true")
        val count = arguments.getString("runtimeTimingRuns")?.toInt() ?: 15
        require(count in 1..100)
        assertFalse("Block the app UID's network access before timing", runCatching {
            Socket().use { it.connect(InetSocketAddress("1.1.1.1", 443), 1_000) }
            true
        }.getOrDefault(false))

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        // Installation and resource hashing are outside all measured intervals.
        BundledYtDlpInstaller(context).ensureCurrent()
        YoutubeDL.getInstance().init(context)
        val base = File(context.noBackupFilesDir, YoutubeDL.baseName)
        val packages = File(base, "packages")
        val home = File(packages, "python/usr")
        val engine = File(File(base, YoutubeDL.ytdlpDirName), YoutubeDL.ytdlpBin)
        val bin = File(context.applicationInfo.nativeLibraryDir)
        val python = File(bin, "libpython.so")
        val commands = linkedMapOf(
            "version" to listOf(engine.absolutePath, "--version"),
            // Match the normal zipapp entry's sys.path[0], without unpacking the engine.
            "initialize" to listOf("-c", """
                import sys
                sys.path[0] = sys.argv[1]
                import yt_dlp, yt_dlp.YoutubeDL
                yt_dlp.YoutubeDL({'quiet': True})
            """.trimIndent(), engine.absolutePath),
        )
        val samples = JSONArray()
        // First invocation of each command is a discarded warm-up on every install.
        for (index in 0..count) {
            val order = if (index % 2 == 0) commands.keys.toList() else commands.keys.reversed()
            for (name in order) {
                val builder = ProcessBuilder(listOf(python.absolutePath) + commands.getValue(name))
                    .redirectErrorStream(true).apply {
                        // Match youtubedl-android 0.18.1's executeImpl environment.
                        environment().apply {
                            put("LD_LIBRARY_PATH", listOf("python", "ffmpeg", "aria2c")
                                .joinToString(":") { File(packages, "$it/usr/lib").absolutePath })
                            put("SSL_CERT_FILE", File(home, "etc/tls/cert.pem").absolutePath)
                            put("PATH", System.getenv("PATH") + ":" + bin.absolutePath)
                            put("PYTHONHOME", home.absolutePath)
                            put("HOME", home.absolutePath)
                            put("TMPDIR", context.cacheDir.absolutePath)
                            remove("PYTHONDONTWRITEBYTECODE")
                            remove("PYTHONPATH")
                        }
                    }
                val start = SystemClock.elapsedRealtimeNanos()
                val process = builder.start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                val exit = process.waitFor()
                val elapsed = SystemClock.elapsedRealtimeNanos() - start
                assertEquals("$name: $output", 0, exit)
                if (name == "version") assertEquals(BuildConfig.YT_DLP_ENGINE_VERSION, output.trim())
                samples.put(JSONObject().put("command", name).put("index", index)
                    .put("discarded", index == 0).put("elapsedNanos", elapsed))
            }
        }
        val result = JSONObject()
            .put("variant", arguments.getString("runtimeTimingVariant", "not reported"))
            .put("engineSha256", engine.sha256())
            .put("runtimeArchiveSha256", File(bin, "libpython.zip.so").sha256())
            .put("retainedRunsPerCommand", count)
            .put("commands", JSONObject(commands))
            .put("samples", samples)
        instrumentation.sendStatus(2, Bundle().apply { putString("runtimeTiming", result.toString()) })
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().buffered().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                digest.update(buffer, 0, size)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
