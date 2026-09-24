package io.github.originalrecipe1.unfurlit.data.extractor.gallerydl

import android.content.Context
import android.system.Os
import io.github.originalrecipe1.unfurlit.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Runs the bundled gallery-dl engine on the Python runtime that youtubedl-android
 * installs for yt-dlp, with the same environment. The runtime must already be
 * initialized (see YtDlpMediaExtractor); this class only adds the engine zip.
 */
internal class GalleryDlRunner(context: Context) {
    private val appContext = context.applicationContext

    /** Returns the engine's combined output; the result is its last JSON line. */
    suspend fun run(url: String): String {
        val engine = withContext(Dispatchers.IO) { ensureInstalled() }
        val pythonHome = File(appContext.noBackupFilesDir, "youtubedl-android/packages/python/usr")
        val command = listOf(
            File(appContext.applicationInfo.nativeLibraryDir, "libpython.so").absolutePath,
            "-S", // the entry point needs nothing from the runtime's site-packages
            engine.absolutePath,
            url,
        )
        val builder = ProcessBuilder(command)
            // Log lines on stderr are kept but ignored; the result is the last line.
            .redirectErrorStream(true)
            .apply {
                environment().apply {
                    put("LD_LIBRARY_PATH", File(pythonHome, "lib").absolutePath)
                    put("SSL_CERT_FILE", File(pythonHome, "etc/tls/cert.pem").absolutePath)
                    put("PYTHONHOME", pythonHome.absolutePath)
                    put("HOME", pythonHome.absolutePath)
                    put("TMPDIR", appContext.cacheDir.absolutePath)
                    put("PYTHONIOENCODING", "utf-8")
                    put("PYTHONDONTWRITEBYTECODE", "1")
                }
            }
        return execute(builder)
    }

    private suspend fun execute(builder: ProcessBuilder): String =
        suspendCancellableCoroutine { continuation ->
            val process = try {
                builder.start()
            } catch (error: IOException) {
                continuation.resumeWithException(error)
                return@suspendCancellableCoroutine
            }
            // Destroying the process also unblocks the reader thread below.
            continuation.invokeOnCancellation { process.destroy() }
            executor.execute {
                try {
                    val output = process.inputStream.readLimited(MAX_OUTPUT_BYTES)
                    process.waitFor()
                    continuation.resume(output)
                } catch (error: Throwable) {
                    continuation.resumeWithException(error)
                } finally {
                    process.destroy()
                }
            }
        }

    /** Copies the engine out of the APK when it is missing or from another build. */
    private fun ensureInstalled(): File = synchronized(installLock) {
        val directory = File(appContext.noBackupFilesDir, "gallery-dl")
        val engine = File(directory, "gallerydl.zip")
        val bundledHash = appContext.resources.openRawResource(R.raw.gallerydl).use(::sha256)
        if (engine.isFile && FileInputStream(engine).use(::sha256) == bundledHash) return engine

        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Could not create $directory")
        val temporary = File.createTempFile("gallerydl-", ".part", directory)
        try {
            val copiedHash = appContext.resources.openRawResource(R.raw.gallerydl).use { input ->
                FileOutputStream(temporary).use { output ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                    digest.digest().toHex()
                }
            }
            if (copiedHash != bundledHash) throw IOException("Bundled gallery-dl changed while copying")
            Os.rename(temporary.absolutePath, engine.absolutePath)
        } finally {
            temporary.delete()
        }
        engine
    }

    private companion object {
        const val MAX_OUTPUT_BYTES = 2 * 1024 * 1024
        const val BUFFER_SIZE = 64 * 1024
        val installLock = Any()
        val executor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "unfurlit-gallerydl").apply { isDaemon = true }
        }

        fun sha256(input: InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
            return digest.digest().toHex()
        }

        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

        /** Reads at most [limit] bytes, then keeps draining so the process can exit. */
        fun InputStream.readLimited(limit: Int): String {
            val kept = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val count = read(buffer)
                if (count < 0) break
                val room = limit + 1 - kept.size()
                if (room > 0) kept.write(buffer, 0, minOf(count, room))
            }
            return kept.toString(Charsets.UTF_8.name())
        }
    }
}
