plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.kapt)
}

android {
    namespace = "com.example.impulse"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.example.impulse"
        minSdk = 33 // WebTransport API requires Android 13 (API 33)
        targetSdk = 36
        versionCode = 2
        versionName = "2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
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
// The standalone ML Kit Barcode library (com.google.mlkit:barcode-scanning)
// bundles libbarhopper_v3.so whose PT_LOAD segments are 4 KB aligned
// (p_align = 4096, p_offset not 16 KB aligned). Android 15+ devices that use
// 16 KB memory pages reject such libraries ("has invalid alignment").
// `zipalign` only aligns the *zip entry offset*, not the ELF-internal
// p_offset/p_vaddr, so it cannot fix this by itself. This task rewrites every
// lib/*.so inside the APK so each PT_LOAD segment gets p_align = 16384 AND a
// 16 KB-aligned p_offset/p_vaddr (zero bias: p_offset == p_vaddr), then re-runs
// zipalign -p 16. This makes the prebuilt native library 16 KB compatible
// without recompiling it.
// ---------------------------------------------------------------------------
val align16kbScript = layout.projectDirectory.file("scripts/align16kb.py")
val zipalignExecutable = File(
    System.getenv("ANDROID_HOME")
        ?: (System.getenv("ANDROID_SDK_ROOT") ?: "${System.getProperty("user.home")}/AppData/Local/Android/Sdk"),
    "build-tools/36.1.0/zipalign.exe"
).absolutePath

afterEvaluate {
    val python = "python"
    // Debug signing config (matches the AGP default debug keystore) so we can
    // RE-SIGN the APK after re-zipping it for 16 KB alignment. Re-zipping the
    // signed APK destroys the v1/v2/v3 signature, so we must re-sign with
    // apksigner to keep the APK installable.
    val androidHome = System.getenv("ANDROID_HOME")
        ?: (System.getenv("ANDROID_SDK_ROOT") ?: "${System.getProperty("user.home")}/AppData/Local/Android/Sdk")
    val apksignerExecutable = File(androidHome, "build-tools/36.1.0/apksigner.bat").absolutePath
    val debugKeystore = File("${System.getProperty("user.home")}/.android/debug.keystore").absolutePath
    val keyPass = "android"
    val storePass = "android"
    val keyAlias = "androiddebugkey"

    listOf("debug", "release").forEach { variant ->
        val cap = variant.replaceFirstChar { it.uppercase() }
        val packageTask = tasks.findByName("package$cap") ?: return@forEach
        val alignTask = tasks.register("align16kb$cap") {
            // The script itself is an input: if it changes, re-align even when the
            // APK is cached up-to-date.
            inputs.file(align16kbScript)
            doLast {
                // Locate the APK produced by the package task (name may vary, e.g.
                // app-debug.apk / app-debug-unsigned.apk). Search the output dir.
                val outDir = File(buildDir, "outputs/apk/$variant")
                val apk = (outDir.listFiles { f -> f.name.endsWith(".apk") }
                    ?.sortedByDescending { it.lastModified() }
                    ?: emptyList()).firstOrNull()
                if (apk == null) {
                    logger.lifecycle("align16kb: APK not found in $outDir, skipping")
                    return@doLast
                }
                // 1) Re-align every lib/*.so to a 16 KB page size (zero bias).
                exec { commandLine(python, align16kbScript.asFile.absolutePath, apk.absolutePath) }
                // 2) Re-zipalign so zip entry offsets stay 16 KB aligned after the rewrite.
                val aligned = File(outDir, "aligned_${apk.name}")
                exec { commandLine(zipalignExecutable, "-p", "16", apk.absolutePath, aligned.absolutePath) }
                apk.delete()
                aligned.renameTo(apk)
                // 3) Re-sign: re-zipping destroyed the v1/v2/v3 signature, so sign again.
                val signed = File(outDir, "signed_${apk.name}")
                exec {
                    commandLine(
                        apksignerExecutable, "sign",
                        "--ks", debugKeystore,
                        "--ks-key-alias", keyAlias,
                        "--ks-pass", "pass:$storePass",
                        "--key-pass", "pass:$keyPass",
                        "--out", signed.absolutePath,
                        apk.absolutePath
                    )
                }
                apk.delete()
                signed.renameTo(apk)
                // 4) Verify: fail the build if any .so is still not 16 KB aligned so the
                //    incompatible APK can never be uploaded to Google Play silently.
                val verify = exec {
                    isIgnoreExitValue = true
                    commandLine(python, align16kbScript.asFile.absolutePath, "--check", apk.absolutePath)
                }
                if (verify.exitValue != 0) {
                    error("align16kb: verification FAILED for ${apk.name} - not 16 KB aligned")
                }
                logger.lifecycle("align16kb: re-aligned, re-signed and verified ${apk.name} to 16 KB page size")
            }
        }
        // finalizedBy guarantees the re-alignment + re-sign runs after *any* packaging
        // path (assemble, install, bundle, or invoking package$cap directly).
        packageTask.finalizedBy(alignTask)
        tasks.findByName("assemble$cap")?.dependsOn(alignTask)
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

    // Post-quantum cryptography (ML-KEM-768) and AES-256-GCM.
    implementation(libs.bouncycastle.prov)
    implementation(libs.bouncycastle.pkix)
    implementation(libs.bouncycastle.util)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Structured logging (Timber) with custom debug/file/release trees.
    implementation(libs.timber)

    // WorkManager: used by BootReceiver to start the foreground service via an
    // expedited work request, which is the only sanctioned way to launch a
    // dataSync foreground service after BOOT_COMPLETED on Android 14+.
    implementation(libs.workmanager)

    // WebTransport / HttpEngine transport. The real classes live in the Android
    // framework (android.net.http.*, API 33+). Because some SDK platform stubs do
    // not ship these symbols, we compile against a local stub JAR (compileOnly,
    // not packaged) and rely on the device's framework implementation at runtime.
    compileOnly(files("libs/android-net-http-stub.jar"))

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
