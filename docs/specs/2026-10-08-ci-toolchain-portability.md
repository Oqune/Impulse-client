# Portable Gradle daemon toolchain

GitHub validation of the published branding PR failed before any project task ran. The tracked daemon criteria required JetBrains Java 21 and pinned Foojay download URLs; the Linux x86_64 URL returned HTTP 400 even though CI had installed Temurin 21.

Keep the daemon's required Java major at 21, remove the IDE-specific vendor and stale provisioning URLs. CI and local builds can reuse any compatible installed Java 21. Kotlin/Java bytecode target, runtime APIs, signing and cryptographic settings stay unchanged. Hosts without Java 21 must install it explicitly rather than silently relying on a broken vendor download.

Validation: testDebugUnitTest locally with the installed Java 21; publish the fix on the existing PR and check the GitHub build, including release APK assembly.

Local testDebugUnitTest and assembleDebug passed after removing the vendor pin (127 existing unit tests). The GitHub run will validate the Linux Temurin path.
