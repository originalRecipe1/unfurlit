package io.github.originalrecipe1.unfurlit.data.extractor.ytdlp

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import io.github.originalrecipe1.unfurlit.BuildConfig
import io.github.originalrecipe1.unfurlit.data.extractor.gallerydl.GalleryDlJsonParser
import io.github.originalrecipe1.unfurlit.data.extractor.gallerydl.GalleryDlRunner
import io.github.originalrecipe1.unfurlit.domain.model.ExtractedMedia
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionError
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionException
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PythonRuntimeTest {
    @Test
    fun bundledEngineIncludesYoutubeChallengeSolver() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        BundledYtDlpInstaller(context).ensureCurrent()
        val directory = File(File(context.noBackupFilesDir, YoutubeDL.baseName), YoutubeDL.ytdlpDirName)
        val solverFiles = mutableMapOf<String, Boolean>()
        File(directory, YoutubeDL.ytdlpBin).inputStream().buffered().use { input ->
            // ZipFile on API 36 rejects the executable prefix. Stream local ZIP entries
            // after the known shebang without rewriting central-directory offsets.
            // The installed executable stays unchanged for Python's zipimport.
            for (expected in "#!/usr/bin/env python3\n") {
                assertEquals("Unexpected yt-dlp executable header", expected.code, input.read())
            }
            ZipInputStream(input).use { engine ->
                while (true) {
                    val entry = engine.nextEntry ?: break
                    if (entry.name.startsWith("yt_dlp_ejs/")) {
                        solverFiles[entry.name.removePrefix("yt_dlp_ejs/")] = engine.read() != -1
                    }
                    engine.closeEntry()
                }
            }
        }
        // Checks whichever engine this APK packages: normally the official asset,
        // or the source build when -Punfurlit.ytdlp.file is supplied. The source
        // build script independently checks its output before Gradle packages it.
        for (path in listOf(
            "__init__.py", "_version.py", "yt/__init__.py", "yt/solver/__init__.py",
            "yt/solver/core.min.js", "yt/solver/lib.min.js",
        )) {
            assertTrue("Missing YouTube solver file: $path", solverFiles.containsKey(path))
        }
        for (script in listOf("core.min.js", "lib.min.js")) {
            assertTrue("Empty YouTube solver script: $script", solverFiles["yt/solver/$script"] == true)
        }
    }

    @Test(timeout = 120_000)
    fun runtimeUpgradeRemovesObsoleteFilesAndPreservesDatabase() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        BundledYtDlpInstaller(context).ensureCurrent()
        val engine = YoutubeDL.getInstance()
        engine.init(context)
        val runtime = File(context.noBackupFilesDir, "youtubedl-android/packages/python")
        val obsolete = File(runtime, "usr/lib/quickjs/libquickjs.a")
        val databaseName = "runtime-upgrade-${UUID.randomUUID()}.db"
        try {
            context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null).use { database ->
                database.execSQL("CREATE TABLE history (title TEXT NOT NULL)")
                database.execSQL("INSERT INTO history VALUES ('Saved visit')")
                obsolete.parentFile!!.mkdirs()
                obsolete.writeText("obsolete runtime build file")
                // Simulate the archive-size marker stored by the previous runtime.
                context.getSharedPreferences("youtubedl-android", Context.MODE_PRIVATE)
                    .edit().putString("pythonLibVersion", "previous-archive-size").apply()

                engine.initPython(context, runtime)

                assertFalse(obsolete.exists())
                database.rawQuery("SELECT title FROM history", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("Saved visit", cursor.getString(0))
                }
                val request = YoutubeDLRequest(emptyList()).apply { addOption("--version") }
                assertEquals(BuildConfig.YT_DLP_ENGINE_VERSION, engine.execute(request).out.trim())
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test(timeout = 120_000)
    fun trimmedRuntimeStartsThePinnedEngineWithoutNetworkAccess() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        BundledYtDlpInstaller(context).ensureCurrent()
        val engine = YoutubeDL.getInstance()
        engine.init(context)
        val request = YoutubeDLRequest(emptyList()).apply { addOption("--version") }
        assertEquals(BuildConfig.YT_DLP_ENGINE_VERSION, engine.execute(request).out.trim())

        val runtime = File(context.noBackupFilesDir, "youtubedl-android/packages/python")
        assertFalse(File(runtime, "usr/lib/quickjs/libquickjs.a").exists())
        val modules = File(runtime, "usr/lib/python3.12/lib-dynload").listFiles().orEmpty()
        assertFalse(modules.any { it.name.startsWith("_test") && it.extension == "so" })
    }

    @Test(timeout = 120_000)
    fun bundledGalleryDlRunsOnTheRuntimeWithoutNetworkAccess() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        BundledYtDlpInstaller(context).ensureCurrent()
        YoutubeDL.getInstance().init(context)
        val runner = GalleryDlRunner(context)

        // Matching a URL imports every extractor module (and requests) from the zip.
        val unsupported = runBlocking { runner.run("https://example.invalid/nothing") }
        try {
            GalleryDlJsonParser.parse("https://example.invalid/nothing", unsupported)
            fail("Expected no extractor for $unsupported")
        } catch (error: ExtractionException) {
            assertEquals(unsupported, ExtractionError.UnsupportedUrl, error.error)
        }

        // Direct image links resolve without any request.
        val imageUrl = "https://images.example.invalid/photo.jpg"
        val result = GalleryDlJsonParser.parse(imageUrl, runBlocking { runner.run(imageUrl) })
        assertEquals(imageUrl, (result.media.single() as ExtractedMedia.Image).source.url)
    }
}
