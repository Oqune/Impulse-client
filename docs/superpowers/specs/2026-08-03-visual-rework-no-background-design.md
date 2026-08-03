# Impulse Client 2.7.0 — Visual Rework + No-Background Privacy Design

> **Status:** Approved 2026-08-03. Implementation follows via writing-plans.
> **Scope:** Client (`D:\Data\Projects\ImpulseProject\client`), Compose, Material3 1.4.0, minSdk 28.

## Goal

Unify the visual layer into one consistent design system, fix shadow/translucency
degradation on old OS versions (API 28-30), add adaptive glass, add Material You
as a theme variant, and fully remove background connection — honoring the
privacy-first philosophy (no background delivery; connection lives only while
the app is foregrounded).

## Decisions (approved)

1. **No background service.** Remove `WebTransportForegroundService`,
   `StartServiceWorker`, `BootReceiver`. Foregrounding reconnects; backgrounding
   disconnects cleanly.
2. **Adaptive glass fallback.** Real blur only on API 31+; API 28-30 get a
   clean M3 surface (semi-transparent color, no blur). Same code, branch on SDK.
3. **Unified theme variants.** One `ThemeVariant` enum; exactly one active:
   `CLASSIC` (hue-based light/dark), `MATERIAL_YOU` (API 31+ wallpaper color,
   fallback CLASSIC), `OLED` (dark only, pure black), `ULTRA_CONTRAST`.
4. **Fix shadows via elevation + borders.** Consistent `tonalElevation` plus a
   1px `outlineVariant` border on surfaces — renders cleanly on all APIs instead
   of relying on soft-shadow rasterization that breaks on API 28-30.
5. **Approach A (minimal surgery).** Unify existing components; no full
   redesign of screens.

## Section 1 — Theme architecture

Replace boolean `oledEnabled`/`ultraContrastEnabled` with a single
`ThemeVariant`:

```kotlin
enum class ThemeVariant { CLASSIC, MATERIAL_YOU, OLED, ULTRA_CONTRAST }
```

- `ThemeSettings.themeVariant: ThemeVariant` (single active; setters are
  mutually exclusive like the current `setOledEnabled`/`setUltraContrastEnabled`).
- `ThemePreferences`: persist variant as string; migrate old boolean keys.
- `Theme.kt` palette selection:
  - `MATERIAL_YOU` && API>=31 → `dynamicDarkColorScheme`/`dynamicLightColorScheme`
  - `MATERIAL_YOU` && API<31 → fallback `CLASSIC`
  - `OLED` → pure-black palette (dark only; disabled in light mode in UI)
  - `ULTRA_CONTRAST` → high-contrast palette
  - else `CLASSIC` (hue-based)
- Hue slider drives `CLASSIC` (and the base hue of OLED/UC as today).

## Section 2 — Elevation & surfaces

- `ImpulseElevation` object: `card=1.dp`, `menu=2.dp`, `overlay=6.dp` tonal elevations.
- `ImpulseCard` / `ImpulseMenuCard`: explicit `cardElevation` + a 1px
  `outlineVariant` border. On all APIs this reads as a clean raised surface.
- Keep `surfaceContainerHigh` container colors (unchanged).

## Section 3 — Glass panels (adaptive)

- `GlassSurface(modifier, shape, content)`:
  - API>=31: `background = surface.copy(alpha=0.65f)` + `Modifier.blur(8.dp)` on
    a backdrop layer + 1px light top edge.
  - API<31: plain `Surface` with `surface.copy(alpha=0.85f)`.
- Apply to: chat composer, floating nav pill, dialog/sheet panels, settings cards.

## Section 4 — Component unification & cleanup

- Extract shared composables into `ComponentStyles.kt`:
  - `ServerStatusRow` (currently 3 duplicated implementations: HomeScreen,
    ChatListScreen, SettingsScreen).
  - `PubKeyHashChip` (3 dup copies).
  - `ExpandableSettingsSection` (3 copies inside ServerExpandableSettings).
  - `BackupTransferDialog` (dedupe BackupScreen vs UserSettingsScreen).
- Remove hardcoded colors:
  - QR scan accent `Color(0xFF3DDC84)` / `Color(0xFF4F8CFF)` → theme colors.
  - White shimmer `Color.White.copy(alpha=...)` → theme-based.
  - Low-contrast `onSurfaceVariant.copy(alpha=0.5f)` labels → >= 0.75 alpha or
    `onSurface`.
- Splash: `windowSplashScreenBackground` themed (API 31+) + `values-night`
  variant; fallback to `Theme.Impulse` background on older APIs.

## Section 5 — No-background connection

- Remove service/receiver declarations from `AndroidManifest.xml`; delete
  `service/WebTransportForegroundService.kt`, `service/StartServiceWorker.kt`,
  `receiver/BootReceiver.kt`.
- `MainActivity`: drop `onStart`/`onStop` FGS starts.
- On background (`onStop`): `ConnectionManager.disconnectAll()`.
- On foreground (`onStart`): auto-connect to the selected server.
- Keep (they make reconnect clean): dead-peer detection (90 s read timeout),
  exponential reconnect backoff, cert-rotation trust (0x07), outbox persistence.
- NetworkMonitor stays (reconnect on network change while foregrounded).

## Versioning

- `versionName = "2.7.0"`, `versionCode = 8`.

## Testing

- Unit tests stay green (protocol/crypto are untouched).
- Manual smoke: old API 28-30 emulator (no blur, borders render) and API 34
  (glass + Material You).
