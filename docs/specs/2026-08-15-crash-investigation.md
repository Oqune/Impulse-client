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

---

## Security audit findings (2026-08-15, read-only)

> Источник: параллельный аудит 3 агентов (security red-team, protocol, cleanup/mines).
> Код НЕ правился — только зафиксировано. Приоритеты для будущего SPEC-фикса.

### CRITICAL — C1: подмена KEM/DSA-ключей через неаутентифицированный key exchange
- Сервер релеит `0x0C` (KeyExchangeKemDsa) всем сессиям без проверки происхождения
  (`server/src/relay/auth.rs:285-338`).
- Клиент **слепо мёржит** чужие ключи по fingerprint
  (`data/PublicKeyRepository.kt:13-34` `cacheKey`, `ChatController.kt:1254-1271`
  `onCombinedKeyExchange` — только parse-check, без proof-of-possession KEM-ключа
  и без связки KEM↔DSA подписью).
- **Impact:** MITM + подделка/обрыв сообщений любого пира (атакующий публикует
  `kemFp=<fp жертвы>` со своим DSA-ключом). Самая серьёзная дыра.
- **Fix (SPEC):** требовать attestation — подписать `KEM_pub||DSA_pub` KEM-приватником
  (или единый combined key); клиент НЕ перезаписывает ключи существующего fingerprint
  без ре-аттестации; сервер ставит/эхоит `0x0C` с id исходной сессии.

### HIGH — C2: outbox хранит plaintext сообщения
- `ChatController.kt:626-643` `persistOutbox` пишет `obj.put("p", e.plaintext)` в
  plain `SharedPreferences` (`impulse_outbox`), не `SecureStorage`.
- **Fix:** хранить только уже-зашифрованный фрейм, либо шифровать at-rest через KeyStore.

### MEDIUM — C3: OP_AUTH шлёт raw-пароль (doc/impl mismatch)
- `Protocol.kt:143-159` шлёт raw password bytes + HMAC; docs говорят «SHA-256 hash».
- **Fix:** PAKE (OPAQUE/CPace) или хотя бы `HMAC(Argon2(pw,salt), nonce)` без raw-пароля.

### MEDIUM — C4: нет replay-кэша (nonce/ts не проверяются)
- `processVerifiedMessage` проверяет подпись, но НЕ кэширует `(fp, nonce)`.
  Сервер может реплеить старые blob с новым `serverMsgId`.
- **Fix:** seen-set `(senderFingerprint, nonce)` + отказ дубликатов.

### MEDIUM — X1: Argon2id params hardcoded (19456/2) ≠ OWASP (47104/3)
- `Protocol.kt:165-183` и `crypto/mod.rs:29-33` используют слабые дефолты;
  расходятся с AGENTS.md. Если сервер «исправят» на OWASP — клиенты перестанут
  аутентифицироваться (silently).
- **Fix:** выводить params из salt/params, которые сервер шлёт в AuthChallenge.

### LOW / INFO
- C5: display name не привязан к проверяющему ключу (spoofable).
- C6: client нет aggregate receive-buffer cap (S1/S2 server-side есть) → OOM от злого сервера.
- C7: хрупкий substring-JSON парсер подписанного envelope (`Protocol.kt:556-589`).
- C8: negative temp-id коллизия при двух отправках в ту же миллисекунду.
- S1: Argon2 DoS — нет глобального rate-limit, длина пароля не ограничена до хеша.
- S2: nonce не single-use. S4: TLS-ключ — unencrypted PEM.
- **ПОЗИТИВ:** AES-256-GCM nonce reuse НЕТ (свежий SecureRandom IV/вызов); BCPQC-unavailable
  fails secure (ERROR, без insecure fallback); constant-time HMAC/Argon2; сервер НЕ дешифрует payloads.

### Топ-5 приоритетов (из аудита)
1. C1/J1 — закрыть key-substitution (attestation + no-overwrite).
2. C2 — убрать plaintext из outbox.
3. C3/J5 — убрать raw-пароль из wire (PAKE/минимум HMAC-only).
4. C4/C5 — replay-cache + identity binding.
5. S1/S2/C6 — auth DoS hardening + client buffer cap.
