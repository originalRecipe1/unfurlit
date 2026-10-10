package io.github.originalrecipe1.unfurlit.data.extractor.ytdlp

import android.Manifest
import android.app.usage.StorageStatsManager
import android.content.Context
import android.os.Bundle
import android.os.Process
import android.os.storage.StorageManager
import android.system.Os
import android.system.OsConstants
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in measurements after a controlled extraction; does not initialize either engine. */
class RuntimeStorageMeasurementTest {
    @Test
    fun reportStorage() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runtimeMeasurement") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val runtime = File(context.noBackupFilesDir, "youtubedl-android/packages/python")
        // lstat avoids counting a shared library again for every upstream symlink.
        val files = context.dataDir.walkTopDown().filter(::isRegularFile).toList()
        val bytecode = files.filter { it.extension == "pyc" || it.extension == "pyo" }
        val measurement = JSONObject()
            .put("runtimeFileBytes", runtime.walkTopDown().filter(::isRegularFile).sumOf(File::length))
            .put("dataFileBytes", files.sumOf(File::length))
            .put("bytecodeFiles", bytecode.size)
            .put("bytecodeBytes", bytecode.sumOf(File::length))
            .put("stdlibZipBytes", File(runtime, "usr/lib/python312.zip").length())
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.PACKAGE_USAGE_STATS)
        try {
            val manager = context.getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
            val stats = manager.queryStatsForPackage(
                StorageManager.UUID_DEFAULT, context.packageName, Process.myUserHandle(),
            )
            measurement.put("appBytes", stats.appBytes)
                .put("dataBytes", stats.dataBytes)
                .put("cacheBytes", stats.cacheBytes)
        } finally {
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
        instrumentation.sendStatus(2, Bundle().apply { putString("runtimeStorage", measurement.toString()) })
    }

    private fun isRegularFile(file: File): Boolean =
        runCatching { OsConstants.S_ISREG(Os.lstat(file.absolutePath).st_mode) }.getOrDefault(false)
}
