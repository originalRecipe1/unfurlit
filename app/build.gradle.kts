import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import io.github.originalrecipe1.unfurlit.buildlogic.TrimPythonRuntime
import org.gradle.api.attributes.Attribute
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val trimmedPythonRuntime = Attribute.of("io.github.originalrecipe1.unfurlit.trimmedPythonRuntime", Boolean::class.javaObjectType)
dependencies {
    attributesSchema.attribute(trimmedPythonRuntime)
    artifactTypes.maybeCreate("aar").attributes.attribute(trimmedPythonRuntime, false)
    registerTransform(TrimPythonRuntime::class) {
        from.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "aar")
            .attribute(trimmedPythonRuntime, false)
        to.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "aar")
            .attribute(trimmedPythonRuntime, true)
    }
}
configurations.configureEach {
    if (isCanBeResolved) attributes.attribute(trimmedPythonRuntime, true)
}

@CacheableTask
abstract class PreparePinnedYtDlp : DefaultTask() {
    @get:Input
    abstract val engineVersion: Property<String>

    @get:Input
    abstract val expectedSha256: Property<String>

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val localEngine: RegularFileProperty

    @get:OutputFile
    abstract val destination: RegularFileProperty

    @TaskAction
    fun prepare() {
        val version = engineVersion.get()
        val expectedHash = expectedSha256.get()
        val destinationFile = destination.get().asFile
        destinationFile.parentFile.mkdirs()
        val temporary = Files.createTempFile(destinationFile.parentFile.toPath(), "ytdlp-", ".part")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val inputStream = localEngine.orNull?.asFile?.inputStream() ?: URI(
                "https://github.com/yt-dlp/yt-dlp/releases/download/$version/yt-dlp",
            ).toURL().openConnection().apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", "Unfurlit-Android-build/$version")
            }.getInputStream()
            inputStream.buffered().use { input ->
                Files.newOutputStream(temporary).buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            }

            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            check(actualHash == expectedHash) {
                "yt-dlp $version checksum mismatch: $actualHash"
            }
            ZipFile(temporary.toFile()).use { archive ->
                val versionEntry = checkNotNull(archive.getEntry("yt_dlp/version.py")) {
                    "yt-dlp archive does not contain yt_dlp/version.py"
                }
                val versionSource = archive.getInputStream(versionEntry).bufferedReader().use { it.readText() }
                val expectedVersion = "__version__ = '$version'"
                check(versionSource.lineSequence().any { it.trim() == expectedVersion }) {
                    "yt-dlp archive version does not match $version"
                }
            }
            Files.move(
                temporary,
                destinationFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}

val ytDlpEngineVersion = libs.versions.ytDlpEngine.get()
val ytDlpReleaseSha256 = "1fa6733c37ea6fb51c99ad8fe785e7b7e5f3246c9b980230329d4fb72ed8d4d6"
// Retain old property aliases for existing local/F-Droid build setups.
val localYtDlpPath = providers.gradleProperty("unfurlit.ytdlp.file")
    .orElse(providers.gradleProperty("peek.ytdlp.file")).orNull
val ytDlpEngineSha256 = if (localYtDlpPath == null) {
    ytDlpReleaseSha256
} else {
    providers.gradleProperty("unfurlit.ytdlp.sha256")
        .orElse(providers.gradleProperty("peek.ytdlp.sha256")).orNull
        ?: error("unfurlit.ytdlp.sha256 is required when unfurlit.ytdlp.file is set")
}
require(ytDlpEngineSha256.matches(Regex("[0-9a-f]{64}"))) {
    "The selected yt-dlp SHA-256 must be 64 lowercase hexadecimal characters"
}
val generatedYtDlpResources = layout.buildDirectory.dir("generated/unfurlitYtDlp/res")
val bundledYtDlp = generatedYtDlpResources.map { it.file("raw/ytdlp") }
val ciX86_64 = providers.gradleProperty("unfurlit.ci.x86_64")
    .map { it.toBooleanStrict() }
    .orElse(false)
    .get()

val preparePinnedYtDlp by tasks.registering(PreparePinnedYtDlp::class) {
    description = "Fetches and verifies the pinned yt-dlp zipimport executable"
    group = "build setup"
    engineVersion.set(ytDlpEngineVersion)
    expectedSha256.set(ytDlpEngineSha256)
    localYtDlpPath?.let { localEngine.fileValue(file(it)) }
    destination.set(bundledYtDlp)
}

android {
    namespace = "io.github.originalrecipe1.unfurlit"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.originalrecipe1.unfurlit"
        minSdk = 24
        targetSdk = 36
        versionCode = 9
        versionName = "1.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField(
            "String",
            "YOUTUBEDL_ANDROID_VERSION",
            "\"${libs.versions.youtubedlAndroid.get()}\"",
        )
        buildConfigField(
            "String",
            "YT_DLP_ENGINE_VERSION",
            "\"$ytDlpEngineVersion\"",
        )
        buildConfigField(
            "String",
            "YT_DLP_ENGINE_SHA256",
            "\"$ytDlpEngineSha256\"",
        )
    }

    buildTypes {
        debug {
            ndk {
                abiFilters += if (ciX86_64) "x86_64" else "arm64-v8a"
            }
        }
        release {
            ndk {
                abiFilters += "arm64-v8a"
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    testOptions {
        animationsDisabled = true
        managedDevices {
            localDevices {
                create("pixel2Api30") {
                    device = "Pixel 2"
                    apiLevel = 30
                    systemImageSource = "aosp-atd"
                    require64Bit = true
                    testedAbi = "x86_64"
                }
            }
        }
    }

    sourceSets.named("main") {
        res.srcDir(generatedYtDlpResources)
    }

    packaging {
        jniLibs.useLegacyPackaging = true
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/DEPENDENCIES",
        )
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.named("preBuild").configure {
    dependsOn(preparePinnedYtDlp)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    implementation(libs.youtubedl.android)

    testImplementation(libs.junit)
    testImplementation(libs.json)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
