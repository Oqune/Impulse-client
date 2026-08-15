# Crash Investigation — Impulse Client (DRAFT / PLAN)

> Черновик-план расследования. Код НЕ править до утверждения SPEC.
> Статус 2026-08-15: в OKF истории крашей НЕТ (только лимит `CrashLog = 5`
> как LOW-пункт аудита). Реальные инциденты не зафиксированы — нужен сбор фактов.

## Симптом
Краши приложения на «некоторых телефонах» (модели/Android API не уточнены).
Гипотеза владельца: причина в «встроенных библиотеках» (native libs / PQC provider).

## Гипотезы (из кода + структуры)

### H1. BouncyCastle PQC provider недоступен на части устройств
- `security/PqcCrypto.kt` использует `org.bouncycastle.pqc.jcajce.provider.BouncyCastlePQCProvider`
  (ML-KEM-768 / Kyber, ML-DSA-65 / Dilithium) из `bcprov-jdk18on`.
- `ChatController.kt` **уже ловит** `«BouncyCastle PQC provider unavailable»`:
  генерация ML-KEM+ML-DSA ключей может бросить на некоторых устройствах.
  Поймано, но НЕ анализируется по модели/API — нужен лог.
- `libs.versions.toml`: `bouncycastle = "1.79"`. Аудит (v2.6.0) и AGENTS.md
  требовали **1.84** (CVE-фиксы) — расхождение, не обновлено.
- Проверить: загружается ли BC PQC provider на падающем устройстве; версия BC.

### H2. Native `.so` и 16 KB page-size (Android 15+, API 35+)
- В `app/build.gradle.kts` уже есть задача `fixNativeLibsAlign` (скрипт
  `scripts/align16kb.py`), переравнивающая `.so` до 16 KB.
- Затронутые либы: `libbarhopper_v3.so` (ML Kit barcode),
  `libandroidx.graphics.path.so` (Compose), `libquiche_jni.so` (QUIC).
- Проверить: применяется ли align на падающем устройстве; `extractNativeLibs`
  (`packaging.jniLibs.useLegacyPackaging = true`).

### H3. ABI x86 (не краш, но деградация)
- `socket-quic-quiche-android` не шлёт x86 native lib → на x86 QUIC деградирует.
- Это НЕ краш по дизайну, но может проявляться как «не подключается».

### H4. Прочее
- `minSdk = 28` (WebTransport через socket-http3). На API 28–32 могут быть
  особенности TLS/QUIC.
- `isMinifyEnabled = true` в release + ProGuard — возможны obfuscation-краши
  (проверить `proguard-rules.pro`, keep-правила для BouncyCastle PKIX).

## План сбора фактов (doing)
1. Включить экспорт логов (LOW-пункт: «No in-app log export UI») ИЛИ снять
   `logcat` с падающего устройства:
   ```bash
   adb logcat -d > impulse-crash.log
   ```
2. Зафиксировать: модель телефона, Android API/версию, ABI (`adb shell getprop ro.product.cpu.abi`),
   версию приложения, шаги воспроизведения.
3. Найти в logcat: стектрейс (Exception), упоминание `BouncyCastle`, `PQC`,
   `UnsatisfiedLinkError` (native lib), `16 KB` / `page size`.
4. Сверить с H1–H4, выписать конкретную причину.
5. Только после фактов — SPEC + правка (через workflow AGENTS.md).

## Чеклист перед правкой (НЕ сейчас)
- [ ] Собран logcat с падающего устройства.
- [ ] Модель/API/ABI/версия зафиксированы.
- [ ] Гипотеза подтверждена фактами (не догадкой).
- [ ] Написан SPEC в `docs/specs/YYYY-MM-DD-crash-<topic>.md`.
- [ ] Получено одобрение владельца.
