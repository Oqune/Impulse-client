<div align="center">

[🇺🇸 **English**](README.md) | [🇷🇺 Русский](README.ru.md)

![logo](logo.png)

[![Kotlin](https://img.shields.io/badge/Kotlin-2.0%2B-purple?logo=kotlin)](https://kotlinlang.org)
[![Android](https://img.shields.io/badge/Platform-Android%2013%2B-lightgrey)](https://www.android.com)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![WebTransport](https://img.shields.io/badge/Transport-WebTransport-blue)](https://developer.android.com/reference/android/net/http/WebTransport)
[![PQC](https://img.shields.io/badge/Crypto-ML--KEM--768%20%2F%20AES--256--GCM-green)](https://en.wikipedia.org/wiki/ML-KEM)

Minimal, self-hosted, **post-quantum end-to-end-encrypted** LAN chat client for Android.
Pairs with the [Impulse server](https://github.com/Oqune/Impulse-server/).

</div>

## What changed in v2.0 (full rewrite)

The client was completely rewritten around a modern, quantum-resistant stack:

| Area | Old (removed) | New |
|------|---------------|-----|
| Transport | `WebSocket` (OkHttp, `wss://`) | **`WebTransport`** (Android `android.net.http`, API 33+, HTTPS/QUIC) |
| Wire format | newline-delimited JSON | **Binary protocol** (opcodes `0x01`–`0x08`, length-prefixed frames) |
| Server auth | trust-any self-signed cert | **Certificate pinning** via `serverCertificateHashes` (TOFU) |
| Key exchange | none / static key | **ML-KEM-768** (Kyber) + **Ed25519** sender signatures |
| Group secret | KEM encapsulate | **Deterministic** `SHA-256(sorted pubkeys)` (server only relays pubkeys) |
| Message crypto | weak AES-ECB | **AES-256-GCM** with the derived group key |
| Local store | plaintext `SharedPreferences` | **Room** (message bodies AES-256-GCM encrypted at the app layer, 72 h TTL) |
| Onboarding | manual URL + key | **QR-code scan** for the server cert hash |

## Features

- 🚀 **WebTransport** transport (Android 13+), replacing WebSocket entirely.
- 🔐 **Post-quantum E2EE**: ML-KEM-768 key generation, deterministic group-secret
  derivation from the set of observed public keys, and AES-256-GCM message
  encryption. Every message is signed with **Ed25519** so receivers can
  authenticate the sender (TOFU for signatures, same trust model as cert pinning).
- 📷 **TOFU certificate pinning** via QR scan (`impulse-cert:<sha256>`). The pinned
  hash is stored in an in-module encrypted store (`SecureStorage`, Android
  Keystore + AES-256-GCM); up to two hashes (current + next) are kept for
  seamless certificate rotation. The QR screen also offers a flashlight toggle
  and a manual hash entry fallback.
- 🗄️ **Encrypted local history** with Room. Each message body is encrypted
  with AES-256-GCM (see `PqcCrypto`) before it is written, so the
  `ciphertext` column never holds plaintext. Messages store
  `server_id`, `server_msg_id`, `sender`, `ciphertext`, `timestamp`.
  Automatic **72-hour TTL** cleanup runs on connect and every 6 hours via a
  `WorkManager` periodic worker (`TtlPurgeWorker`).
- 📱 Jetpack Compose (Material 3) UI with Fluent-style motion: chat list, input
  bar, QR scanner, settings, status indicators.
- 🔁 Automatic reconnect with exponential backoff; foreground service keeps the
  connection alive; boot receiver re-connects after reboot.
- 🌗 Light / Dark / System themes and optional biometric app lock.

## Binary protocol (opcodes `0x01`–`0x08`)

Every frame starts with a single opcode byte. Field encoding is little-endian:
`u8` (1 B), `u16` (2 B length prefix), `u32` (4 B), `u64` (8 B), `bytes`
(`u16` length + raw). The server never sees plaintext metadata — the sender,
signature and content live inside the AES-256-GCM `OP_DATA` payload.

| Opcode | Name | Direction | Body |
|--------|------|-----------|------|
| `0x01` | `OP_AUTH` | C→S | `password` (utf8) |
| `0x02` | `OP_AUTH_RESULT` | S→C | `success(u8)` [error utf8 if !success] |
| `0x03` | `OP_SYNC` | C→S | `last_seen_id(u64)` |
| `0x04` | `OP_SYNC_RESPONSE` | S→C | `count(u32)` { `id(u64)`, `timestamp(u64)`, `len(u16)`, `payload(bytes)` } |
| `0x05` | `OP_DATA` | both | C→S: `len(u16)`+`payload`. S→C relay: `server_msg_id(u64)`+`timestamp(u64)`+`len(u16)`+`payload` |
| `0x06` | `OP_HEARTBEAT` | both | (no body) |
| `0x07` | `OP_NEW_CERT_HASH` | S→C | `hash_len(u16)`+`hash`+`expiry(u64)` |
| `0x08` | `OP_KEY_EXCHANGE` | both | `key_len(u16)`+`public_key` (ML-KEM **or** Ed25519, distinguished by length) |

The client sends `OP_DATA` as `len+payload`; the server prepends
`server_msg_id` + `timestamp` when it relays the message back to all peers. The
client stores the authoritative row keyed by the real (non-negative)
`server_msg_id`; its own optimistic copy uses a **negative** temp id so it can
never collide.

## Group secret (deterministic, no KEM exchange)

The server has **no** ML-KEM key and only relays `OP_KEY_EXCHANGE` pubkeys. Each
client therefore derives the group AES key locally and deterministically:

```
group_key = SHA-256( concat( sort_lexicographically( all_observed_ml_kem_pubkeys ) ) )[0..32]
```

Because the set is sorted before hashing, every participant that has observed the
same set of public keys derives the **identical** 32-byte key — enabling
cross-client decryption without any KEM encapsulate/decapsulate step. The
`AUTHENTICATED` state is reached only after the group secret is established, and
the channel becomes `READY` (chat enabled) once key exchange completes.

## Requirements

- **Android 13 (API 33)** or newer device/emulator.
- Android Studio (AGP 8.13+), **JDK 17**, Android SDK 34+.
- A server that serves the **WebTransport** handshake over HTTPS/QUIC on the
  configured host:port (default `11000`) and speaks the binary protocol above
  (see [`transport/Protocol.kt`](app/src/main/java/com/example/impulse/transport/Protocol.kt)).

## Build & install

```bash
git clone https://github.com/Oqune/Impulse-client.git
cd Impulse-client
./gradlew assembleDebug        # or open in Android Studio and Run 'app'
```

Install the APK from `app/build/outputs/apk/debug/` onto a device/emulator
(API 33+). Camera permission is requested on first QR scan.

## First-run flow

1. Open the app → **Home** → tap **Подключиться** (Connect).
2. Because no certificate hash is trusted yet, the state becomes
   *"Ошибка / нужен QR"* (Error / QR needed).
 3. Go to the **QR** tab and scan the server's QR code (payload
    `impulse-cert:<hex-sha256-of-cert>`). The hash is pinned and the client
    immediately (re)connects. You can also tap the flashlight or enter the hash
    manually if scanning fails. **Strict validation:** only a payload that
    begins with the `impulse-cert:` prefix followed by exactly 64 hex characters
    is accepted — a bare 64-hex token is rejected to prevent pinning the wrong
    certificate.
 4. After a successful handshake the server may push a *next* cert hash
    (`OP_NEW_CERT_HASH`); it is stored as the second slot for rotation.
 5. ML-KEM-768 + Ed25519 public keys are exchanged automatically; once the group
    secret is derived, chat is fully E2EE and the state becomes *READY* (the
    connection state machine is `CONNECTING → CONNECTED → AUTHENTICATING →
    AUTHENTICATED → READY`; sending is blocked until `READY`).

## Project layout

```
app/src/main/java/com/example/impulse/
├── MainActivity.kt                     # entry point, biometric lock, foreground service, TTL purge
├── ChatController.kt                   # orchestrates transport + crypto + storage + key exchange
├── transport/
│   ├── WebTransportClient.kt           # WebTransport session + serverCertificateHashes + reconnect
│   ├── Protocol.kt                     # binary wire protocol (opcodes 0x01–0x08)
│   └── ConnectionState.kt
├── security/
│   ├── PqcCrypto.kt                    # ML-KEM-768 + Ed25519 + AES-256-GCM + deterministic group key
│   ├── SecureStorage.kt                # Android Keystore + AES-256-GCM store (keys + cert hashes)
│   └── TrustedCertManager.kt           # TOFU, max-2-hash rotation
├── data/
│   ├── ServerConfig.kt                 # WebTransport URL builder (https://host:port)
│   ├── ServerPreferences.kt
│   ├── MessageRepository.kt            # Room access + 72h TTL purge
│   └── db/                             # Room entities, DAO, database, repository
├── service/
│   ├── WebTransportForegroundService.kt
│   ├── TtlPurgeWorker.kt               # periodic 72h TTL cleanup (WorkManager)
│   └── StartServiceWorker.kt           # boot-time expedited start
├── receiver/BootReceiver.kt
└── ui/
    ├── ChatViewModel.kt / ChatViewModelFactory.kt   # MVVM bridge (StateFlow<List<DecryptedMessage>>)
    ├── theme/                          # Material 3 theme
    └── screens/                        # MainScreen, HomeScreen, ChatScreen, QrScanScreen, Settings…
```

## Testing

```bash
./gradlew test            # unit tests: PqcCrypto (ML-KEM, Ed25519, AES, group key) + Protocol (opcodes)
./gradlew connectedAndroidTest   # instrumented: TrustedCertManager (TOFU, max-2-hash) + MessageDao (Room, TTL)
```

Test coverage includes:
- `PqcCryptoTest` — ML-KEM keygen, AES-256-GCM round-trip, deterministic group key
  (identical across clients), Ed25519 sign/verify (incl. tamper & wrong-key rejection).
- `ProtocolTest` — all opcodes `0x01`–`0x08` build/parse round-trips.
- `QrParseTest` — **strict** `impulse-cert:<64 hex>` validation (rejects bare 64-hex).
- `ChatControllerIntegrationTest` — full protocol cycle simulated without a network:
  two clients derive the same group secret, auth/key-exchange frames round-trip,
  `OP_DATA` carries the server-assigned id for dedup, optimistic send uses a
  negative temp id, and the `READY` state exists.
- `MessageDaoTest` (instrumented) — upsert dedup (negative temp id → real id),
  ordered select, 72 h TTL purge, `maxServerMsgId`, per-server clear.

## Logging

Structured logging is provided by [`util/LogManager.kt`](app/src/main/java/com/example/impulse/util/LogManager.kt)
(a thin wrapper over [Timber](https://github.com/JakeWharton/timber)) with three
trees:

- **DebugTree** — full logcat output (debug builds only).
- **FileTree** — appends every record to a rotating file set on disk
  (max 5 MB per file, 5 files kept) so logs survive process death and can be
  exported from **Settings → Logs**.
- **ReleaseTree** — only `ERROR`/`ASSERT` to logcat in production (no file, no PII).

Log line format:

```
[2025-07-17T14:30:45.123] [INFO] [ChatController] Message sent, server_id=42
```

**Privacy:** passwords, private keys, full cert hashes and message contents are
never logged. Cert hashes are truncated to the first 8 hex chars via
`LogManager.shortHash`; secrets are replaced with `<redacted>` via
`LogManager.redact`. The in-app **Logs** screen (Settings → Logs) shows the last
1000 records with a level filter (ALL / ERROR / WARN / INFO), export to a file,
and clear. A global uncaught-exception handler
([`ImpulseApplication`](app/src/main/java/com/example/impulse/ImpulseApplication.kt))
also writes a crash report to disk so "crashes without logs" are diagnosable.

## Architecture

```mermaid
flowchart TD
    UI[Compose UI: Main / Home / Chat / QR / Settings] --> VM[ChatViewModel StateFlow]
    VM --> CC[ChatController]
    CC --> WT[WebTransportClient]
    WT -->|HTTPS/QUIC| SRV[(Impulse Server)]
    CC --> CRYPTO[PqcCrypto: ML-KEM-768 + Ed25519 + AES-256-GCM]
    CC --> CERT[TrustedCertManager: TOFU pinning]
    CC --> REPO[MessageRepository → Room]
    CC --> LOG[LogManager: Timber Debug/File/Release trees]
    WT -->|opcodes 0x01-0x08| SRV
    SRV -->|OP_KEY_EXCHANGE pubkeys| CC
    CC -->|deriveGroupKey SHA-256 sorted pubkeys| CRYPTO
```

Connection state machine:

```mermaid
stateDiagram-v2
    [*] --> DISCONNECTED
    DISCONNECTED --> CONNECTING: connect()
    CONNECTING --> CONNECTED: session ready
    CONNECTED --> AUTHENTICATING: auth sent
    AUTHENTICATING --> AUTHENTICATED: auth ok + group secret
    AUTHENTICATED --> READY: key exchange done (chat enabled)
    READY --> ERROR: failure
    CONNECTED --> ERROR: failure
    ERROR --> CONNECTING: reconnect (exp backoff)
    ERROR --> DISCONNECTED: user disconnect
```

## Build notes / implementation details

- **Post-quantum crypto (ML-KEM-768) + Ed25519.** Provided by BouncyCastle
  (`bcprov`/`bcpkix` 1.79). On Android's ART runtime the Kyber/ML-KEM algorithms
  are only reachable through the dedicated `BouncyCastlePQCProvider`
  (`Security.addProvider(BouncyCastlePQCProvider())`); the generic
  `BouncyCastleProvider` does **not** register them. Key generation uses
  `KeyPairGenerator.getInstance("ML-KEM")` with `MLKEMParameterSpec.ml_kem_768`.
  Ed25519 signing/verification uses `Ed25519` via `BouncyCastleProvider`. See
  [`security/PqcCrypto.kt`](app/src/main/java/com/example/impulse/security/PqcCrypto.kt).
- **Deterministic group key.** `PqcCrypto.deriveGroupKey` sorts the observed
  ML-KEM public keys (by bytes) and returns `SHA-256(concat(...))[0..32]`. This
  replaces the earlier KEM-encapsulate approach and lets any client that has seen
  the same pubkey set decrypt the same traffic.
- **Secure storage.** Instead of AndroidX `EncryptedSharedPreferences`
  (which pulls in the Tink + Gson transitive dependencies), the app ships a
  self-contained [`security/SecureStorage.kt`](app/src/main/java/com/example/impulse/security/SecureStorage.kt)
  built directly on the Android `KeyStore` (`AndroidKeyStore`) and
  `AES/GCM/NoPadding`. The public API (`putString`/`getString`/`putBytes`/
  `getBytes`/`remove`/`contains`) is identical, so callers are unchanged.
- **WebTransport stubs.** The `android.net.http.*` (API 33+) symbols are
  provided at compile time by a local stub JAR (`app/libs/android-net-http-stub.jar`,
  `compileOnly`) because some SDK platform stubs do not ship these classes. The
  real implementation comes from the device framework at runtime.
- **Inner envelope JSON (no `org.json` dependency).** The `OP_DATA` inner
  envelope (`{"sender":…,"signature":…,"content":…}`) is encoded/decoded by a
  tiny hand-rolled JSON writer/reader in
  [`transport/Protocol.kt`](app/src/main/java/com/example/impulse/transport/Protocol.kt)
  rather than `org.json.JSONObject`. This removes the Android-framework JSON
  dependency so the same code path is exercised by JVM unit tests without
  Robolectric, and keeps the APK lean. The writer escapes `"`, `\`, control
  characters and emits `\uXXXX` for non-ASCII; the reader tolerantly extracts the
  three known string fields.
- **Unit-test friendliness.** `app/build.gradle.kts` sets
  `testOptions.unitTests.isReturnDefaultValues = true` so framework stubs return
  safe defaults in unit tests. The instrumented `androidTest` suite
  (`MessageDaoTest`, `TrustedCertManagerTest`) runs on-device via
  `AndroidJUnit4` (no Robolectric), since `TrustedCertManager` relies on the
  Android Keystore.
- **16 KB page-size (Android 15+) compatibility.** Android 15+ devices that use
  16 KB memory pages reject native libraries whose **ELF `PT_LOAD` segments**
  have `p_align < 16384` (`dlopen failed: ... has invalid alignment`). Note that
  `zipalign` only aligns the *zip entry offset*, NOT the ELF-internal `p_align`,
  so it cannot fix this by itself.
  - We **do not use SQLCipher** (its `libsqlcipher.so` is 4 KB ELF-aligned); the
    message store is plain Room and every message body is encrypted at the
    application layer with AES-256-GCM (see `PqcCrypto`) before being written.
  - The QR (TOFU) scanner uses the **standalone ML Kit Barcode library**
    (`com.google.mlkit:barcode-scanning:17.3.0`) for decoding, with the camera
    preview implemented directly on the framework **Camera2 API** (no CameraX
    dependency). This avoids bundling CameraX's unaligned native library
    `libimage_processing_util_jni.so` entirely. The standalone ML Kit library
    bundles `libbarhopper_v3.so` whose `PT_LOAD` segments are 4 KB aligned. To
    make the APK 16 KB compatible **without recompiling the third-party
    library**, the build runs a post-package step (`app/scripts/align16kb.py`,
    wired via the `align16kbDebug`/`align16kbRelease` Gradle tasks) that rewrites
    every `lib/*.so` inside the APK so each `PT_LOAD` segment gets
    `p_align = 16384` **and** a 16 KB-aligned `p_offset`/`p_vaddr` (the Android
    16 KB loader requires BOTH the file offset and the virtual address to be 16
    KB aligned, not just the declared `p_align`). The script re-lays-out the ELF
    with a zero bias (`p_offset == p_vaddr`, both 16 KB aligned) which is always
    a valid, loadable layout, then re-runs `zipalign -p 16` and finally **verifies**
    every `lib/*.so` is 16 KB aligned (the build fails if any is not). Verified:
    all remaining `.so` files report `ALL .so 16KB ALIGNED: True`.
  - The manifest also sets `android:extractNativeLibs="true"` together with
    `packaging.jniLibs.useLegacyPackaging = true` so the installer re-pages any
    extracted native libs at install time.

## License

MIT — see [LICENSE](LICENSE). Client and server are intended to be used together.
