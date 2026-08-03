import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.kapt)
}

android {
    namespace = "com.example.impulse"
    compileSdk = 36

    signingConfigs {
        create("release") {
            val ksDir = project.rootProject.file("keystore")
            val ksFile = ksDir.resolve("impulse-release.jks")
            val passFile = ksDir.resolve("keystore-password.txt")
            if (ksFile.exists() && passFile.exists()) {
                storeFile = ksFile
                storePassword = passFile.readText().trim()
                keyAlias = "impulse"
                keyPassword = passFile.readText().trim()
            }
            // If the keystore is absent (e.g. fresh clone), the release build
            // falls back to unsigned; CI/developers must supply the keystore.
        }
    }

    defaultConfig {
        applicationId = "com.example.impulse"
        minSdk = 28 // WebTransport via socket-http3 (third-party), no native API 33+ needed
        targetSdk = 36
        versionCode = 7
        versionName = "2.6.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        // Required so that android:extractNativeLibs="true" takes effect.
        // AGP stores .so files uncompressed and ignores extractNativeLibs
        // unless legacy packaging is enabled. With extractNativeLibs=true the
        // Android installer re-pages/re-aligns native libraries at install time,
        // which is the final safety net for 16 KB page-size compatibility on
        // Android 15+ (API 35+) devices.
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            // BouncyCastle may ship overlapping license files.
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE.md"
            excludes += "/META-INF/LICENSE-notice.md"
            excludes += "/META-INF/LICENSE.txt"
            excludes += "/META-INF/NOTICE.txt"
            excludes += "/META-INF/INDEX.LIST"
        }
    }
    testOptions {
        // Unit tests run against android.jar stubs; return default values for
        // framework methods (e.g. org.json.JSONObject) instead of throwing
        // "not mocked", so protocol/crypto unit tests can exercise the full
        // wire format without Robolectric.
        unitTests.isReturnDefaultValues = true
    }
}

// Force cached transitive versions so the project builds fully offline.
// The layout-inspection annotation is only needed by Compose tooling on a
// device and is not required for unit tests (and is not cached offline).
configurations.all {
    resolutionStrategy {
        exclude("androidx.resourceinspection", "resourceinspection-annotation")
    }
}

// ---------------------------------------------------------------------------
// 16 KB page-size (Android 15+) ELF re-alignment.
//
// Pre-built native libraries (libbarhopper_v3.so from ML Kit,
// libandroidx.graphics.path.so from Compose, and the small JNI shim
// libquiche_jni.so from socket-quic-quiche-android) have PT_LOAD segments
// aligned to 4 KB.  Android 15+ devices with 16 KB pages reject libraries
// whose PT_LOAD segments aren't 16 KB-aligned.
//
// We fix the .so files *before* they are packaged into the APK: the task runs
// after mergeNativeLibs (which merges all per‑ABI libraries into one output
// directory) and before package{Variant} (which zips them).  This avoids
// post‑hoc APK modification, re‑signing, and the timing issues with
// finalizedBy + installDebug.
// ---------------------------------------------------------------------------
val align16kbScript = layout.projectDirectory.file("scripts/align16kb.py")

afterEvaluate {
    // CI runners (ubuntu) ship `python3`; Windows ships `python`.
    val python = if (org.gradle.internal.os.OperatingSystem.current().isWindows) "python" else "python3"
    listOf("debug", "release").forEach { variant ->
        val cap = variant.replaceFirstChar { it.uppercase() }
        val mergeTaskName = "merge${cap}NativeLibs"
        val mergeTask = tasks.findByName(mergeTaskName) ?: return@forEach

        val fixTask = tasks.register("fixNativeLibsAlign${cap}") {
            dependsOn(mergeTask)
            inputs.file(align16kbScript)
            doLast {
                val nativeDir = mergeTask.outputs.files.firstOrNull()
                    ?: file("$buildDir/intermediates/merged_native_libs/$variant/merge${cap}NativeLibs/out")
                if (!nativeDir.isDirectory) {
                    logger.lifecycle("fixNativeLibsAlign: $nativeDir not found, skipping")
                    return@doLast
                }
                // Skip alignment when no Python interpreter is on PATH (e.g. a
                // bare Windows dev box). CI runners have python3, so release APKs
                // still get the 16 KB fix.
                val probe = project.exec {
                    commandLine(if (python == "python3") listOf("sh", "-lc", "command -v python3") else listOf("where", "python"))
                    isIgnoreExitValue = true
                }.exitValue
                if (probe != 0) {
                    logger.warn("fixNativeLibsAlign: '$python' not found, skipping 16 KB alignment (dev-only build)")
                    return@doLast
                }
                nativeDir.walkTopDown().filter { it.name.endsWith(".so") }.forEach { so ->
                    project.exec {
                        commandLine(python, align16kbScript.asFile.absolutePath, so.absolutePath)
                    }
                }
            }
        }
        // Our fix must run before strip so the stripped output inherits aligned files.
        val stripTask = tasks.findByName("strip${cap}DebugSymbols")
        if (stripTask != null) {
            stripTask.dependsOn(fixTask)
        }
        // With ABI splits there is one package task per ABI plus the universal one
        // (packageDebug, packageDebugArm64_v8a, ...) — every one of them must run
        // after the alignment fix, since they all zip the shared merged_native_libs.
        tasks.matching { it.name.startsWith("package$cap") }.configureEach {
            dependsOn(fixTask)
        }
    }
}

dependencies {
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.biometric)

    // Secure storage for cert hashes and PQ key material is implemented
    // in-module (SecureStorage.kt) using the Android Keystore + AES-256-GCM,
    // avoiding the Tink/Gson transitive deps of androidx.security.crypto.

    // QR (TOFU) camera preview uses the framework Camera2 API directly (no CameraX)
    // so the APK does not bundle CameraX's unaligned native library
    // (libimage_processing_util_jni.so). ML Kit performs the barcode decoding.
    // Standalone ML Kit Barcode scanner (com.google.mlkit:barcode-scanning) —
    // a different artifact from the previously used play-services variant. The
    // bundled libbarhopper_v3.so is re-aligned to 16 KB by the align16kb task.
    implementation(libs.mlkit.barcode.scanning)

    // Local message store (Room). Message bodies are encrypted at the
    // application layer with AES-256-GCM (see PqcCrypto), so we do NOT pull in
    // SQLCipher: its prebuilt libsqlcipher.so is not 16 KB ELF-aligned and
    // crashes on Android 15+ (16 KB page) devices.
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    kapt(libs.room.compiler)
    kapt("org.jetbrains.kotlin:kotlin-metadata-jvm:2.4.0")

    // Post-quantum cryptography (ML-KEM-768) and AES-256-GCM.
    implementation(libs.bouncycastle.prov)
    implementation(libs.bouncycastle.pkix)
    implementation(libs.bouncycastle.util)

    // Argon2id password hashing — provided by BouncyCastle (bcprov), no extra dependency needed.

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Structured logging (Timber) with custom debug/file/release trees.
    implementation(libs.timber)

    // WorkManager: used by BootReceiver to start the foreground service via an
    // expedited work request, which is the only sanctioned way to launch a
    // dataSync foreground service after BOOT_COMPLETED on Android 14+.
    implementation(libs.workmanager)

    // WebTransport via DitchOoM/socket (QUIC/HTTP3, RFC 9220). Uses quiche
    // under the hood — no `@hide` framework API, works on any Android device.
    // Since 3.9.x, the quiche native library ships as a proper Android AAR
    // (socket-quic-quiche-android, jni/<abi>/libquiche{,_jni}.so for
    // arm64-v8a/armeabi-v7a/x86_64), pulled in transitively via
    // socket-http3 → socket-quic-default → socket-quic-quiche. NativeLibLoader
    // resolves it with System.loadLibrary — no manual extraction needed.
    // buffer/flow are pulled transitively via socket-http3 — do not declare explicitly.
    implementation("com.ditchoom:socket-http3:3.9.5")
    implementation("com.ditchoom:socket-quic:3.9.5")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
