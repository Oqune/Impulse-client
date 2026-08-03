# Impulse Client 2.7.0 — Visual Rework + No-Background Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Unify the client visual layer, fix old-OS shadow/translucency, add adaptive glass + Material You theme variant, and fully remove background connection.

**Architecture:** Single `ThemeVariant` enum replaces boolean OLED/UC flags; surfaces get explicit tonal elevation + 1px borders (clean on all APIs); `GlassSurface` blurs only on API 31+; background service is deleted and lifecycle drives connect/disconnect.

**Tech Stack:** Kotlin, Compose Material3 1.4.0, minSdk 28. Build with JDK 17.

## Global Constraints

- Repo root: `D:\Data\Projects\ImpulseProject\client`. All commands run there.
- JDK: `C:\Program Files\Java\jdk-17` (`$env:JAVA_HOME`). Gradle: `gradlew.bat -p <root>`.
- No wire-protocol changes. No new dependencies.
- Every task ends with `:app:compileDebugKotlin` + `:app:testDebugUnitTest` green, then a commit.

---

### Task 1: ThemeVariant enum + Material You

**Files:**
- Modify: `app/src/main/java/com/example/impulse/ui/theme/ThemeSettings.kt`
- Modify: `app/src/main/java/com/example/impulse/ui/theme/ThemePreferences.kt`
- Modify: `app/src/main/java/com/example/impulse/ui/theme/Theme.kt`
- Modify: `app/src/main/java/com/example/impulse/ui/screens/AppSettingsScreen.kt`

**Interfaces:**
- Produces: `ThemeVariant { CLASSIC, MATERIAL_YOU, OLED, ULTRA_CONTRAST }`; `ThemeSettings.themeVariant: ThemeVariant`; `ThemeSettings.setThemeVariant(v)`; `ThemePreferences.saveThemeVariant`/`themeVariantFlow`.

- [ ] **Step 1: Add ThemeVariant + replace booleans**

In `ThemeSettings.kt` add the enum and swap `_oledEnabled`/`_ultraContrastEnabled` for `_themeVariant`; keep setters mutually exclusive.

```kotlin
enum class ThemeVariant { CLASSIC, MATERIAL_YOU, OLED, ULTRA_CONTRAST }
```

- [ ] **Step 2: Persist + migrate in ThemePreferences**
- [ ] **Step 3: Theme.kt uses variant (Material You via dynamicDark/LightColorScheme on API 31+, fallback CLASSIC)**
- [ ] **Step 4: AppSettingsScreen UI: variant selector replacing OLED/UC toggles**
- [ ] **Step 5: Build + test + commit**

### Task 2: Elevation & surface borders

**Files:** `ui/theme/ComponentStyles.kt` (ImpulseCard, ImpulseMenuCard)

- [ ] **Step 1: Add `ImpulseElevation` + 1px `outlineVariant` border to cards**
- [ ] **Step 2: Build + commit**

### Task 3: GlassSurface composable

**Files:**
- Create: `ui/theme/GlassSurface.kt`
- Modify: `ui/screens/MainScreen.kt`, `ui/screens/ChatScreen.kt` (apply to nav pill + composer)

- [ ] **Step 1: `GlassSurface` with SDK>=S blur branch, else plain Surface**
- [ ] **Step 2: Apply to nav pill + chat composer**
- [ ] **Step 3: Build + commit**

### Task 4: Component unification + hardcoded-color cleanup

**Files:** `ui/theme/ComponentStyles.kt` (new shared composables), `ui/screens/HomeScreen.kt`, `ChatListScreen.kt`, `SettingsScreen.kt`, `QrScanScreen.kt`

- [ ] **Step 1: Extract `ServerStatusRow`, `PubKeyHashChip`, `ExpandableSettingsSection`**
- [ ] **Step 2: Use them in the 3 duplicated call sites**
- [ ] **Step 3: Replace QR accent + white shimmer + low-alpha labels with theme colors**
- [ ] **Step 4: Build + commit**

### Task 5: Splash screen

**Files:** `res/values/themes.xml`, `res/values-night/themes.xml` (create), `res/drawable/splash_bg.xml`

- [ ] **Step 1: `windowSplashScreenBackground` themed (API 31) + night fallback**
- [ ] **Step 2: Build + commit**

### Task 6: Remove background connection

**Files:**
- Delete: `service/WebTransportForegroundService.kt`, `service/StartServiceWorker.kt`, `receiver/BootReceiver.kt`
- Modify: `AndroidManifest.xml`, `MainActivity.kt`, `ImpulseApplication.kt`

- [ ] **Step 1: Delete service/receiver files + Manifest entries**
- [ ] **Step 2: `MainActivity.onStop` → `ConnectionManager.disconnectAll()`; `onStart` → auto-connect selected server**
- [ ] **Step 3: Remove FGS/wakelock/battery-request imports from MainActivity + ImpulseApplication**
- [ ] **Step 4: Build + commit**

### Task 7: Version + release 2.7.0

- [ ] **Step 1: `versionCode=8`, `versionName="2.7.0"`**
- [ ] **Step 2: `assembleRelease` signed APKs (keystore at `keystore/impulse-release.jks`)**
- [ ] **Step 3: `gh release create v2.7.0` with downloads table + EN/RU changelog**
