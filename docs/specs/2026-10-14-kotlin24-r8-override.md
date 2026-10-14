# R8 override for Kotlin 2.4 release shrinking

Date: 2026-10-14  
Status: Approved for implementation by project owner

## Problem

The client uses Kotlin 2.4.0 with AGP 8.13.0. AGP bundles its own R8, and the release shrink step emits unsupported Kotlin metadata warnings with the bundled version. The official Android compatibility table requires R8 9.1.29 for Kotlin 2.4.

## Change

- Keep Kotlin 2.4.0 and AGP 8.13.0 unchanged.
- In `settings.gradle.kts` `pluginManagement.buildscript.dependencies`, add only the stable `com.android.tools:r8:9.1.29` override following the official R8 replacement instructions.
- Add the official R8 release Maven repository (`https://storage.googleapis.com/r8-releases/raw`) to the nested plugin-management buildscript repositories. The R8 POM and JAR for 9.1.29 return HTTP 200 there; Aliyun Google and Maven Central return 404 for this artifact.
- Do not add a project runtime dependency, upgrade AGP, alter shrinking rules, or change application code.

## Validation

- The coordinating agent will run the release build and confirm it selects R8 9.1.29 and removes unsupported Kotlin metadata warnings.
- This agent does not run Gradle.

## References

- Android's Kotlin compatibility table lists R8 9.1.29 as the minimum for Kotlin 2.4 and says the R8 version matters when overriding AGP's bundled compiler: <https://developer.android.com/build/kotlin-support>
- The official R8 instructions show overriding AGP's embedded compiler by adding `classpath("com.android.tools:r8:<version>")` under `pluginManagement.buildscript.dependencies` in top-level `settings.gradle(.kts)`: <https://r8.googlesource.com/r8/+/refs/heads/main/README.md#125>
- The official R8 release repository is `https://storage.googleapis.com/r8-releases/raw`, as specified by the same R8 replacement instructions.
