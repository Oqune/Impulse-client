# Impulse — Consolidated Audit Report (2026-08-15)

> Read-only audit. Code was NOT modified — only investigated and documented.
> Versions: client 2.9.1, server 2.7.3. Four parallel sub-agents:
> (1) Security red-team, (2) Protocol correctness, (3) Philosophy/stubs/mines,
> (4) Library-vs-self-written. Goal: prep for cleanup, stability, and a
> school project-defense ("AI as a tool, used under unified conventions").

## TL;DR
- **Good:** No self-rolled ciphers/MACs/KDFs/hashes in either repo. Constant-time
  HMAC/Argon2. No AES-GCM nonce reuse. BCPQC-unavailable fails *secure* (ERROR, no
  weaker fallback). Server never decrypts relayed payloads (forbidden-zone holds).
  Binary framing, PQC per-recipient wrapping, TOFU glue are correctly kept in-house.
- **One CRITICAL:** C1 — unauthenticated key exchange lets a malicious peer/server
  substitute a victim's KEM/DSA keys (MITM + message forgery/disruption).
- **Most other findings are Medium/Low + latent "will-rot" mines**, many already in
  the known-issues list. No active plaintext-leak of *message content* to the server.

## 1. Security red-team (client + server)

| ID | Sev | Finding | Location | Fix (SPEC, not done) |
|----|-----|---------|----------|----------------------|
| C1 | **CRITICAL** | Unauthenticated `0x0C` relay + blind client key merge → KEM/DSA substitution / MITM | server `relay/auth.rs:285-338`; client `PublicKeyRepository.kt:13-34`, `ChatController.kt:1254-1271` | Require attestation: sign `KEM_pub||DSA_pub` with KEM private key; client refuses to overwrite an existing fingerprint without re-attestation; server tags `0x0C` with origin session id |
| C2 | HIGH | Outbox persists plaintext message body in plain `SharedPreferences` | `ChatController.kt:626-643`, `:41` (`impulse_outbox`) | Persist only the encrypted frame, or encrypt at rest via KeyStore |
| C3 | MED | `OP_AUTH` sends raw account password (docs say "hash") | `Protocol.kt:143-159`, `server relay/auth.rs:127-131` | PAKE (OPAQUE/CPace) or at minimum `HMAC(Argon2(pw,salt), nonce)`, never cleartext |
| C4 | MED | No `(sender, nonce)` replay cache; dedup only by `serverMsgId` → malicious server can replay | `ChatController.kt:791-843` | Seen-set `(fingerprint, nonce)`; reject duplicates |
| C5 | MED/LOW | Display name not bound to verifying key (spoofable) | `ChatController.kt:752,799-813` | Bind name to fingerprint / show only fingerprint |
| C6 | MED | Client has no aggregate receive-buffer cap (server has one) → OOM from hostile server | `WebTransportClient.kt:327-368`, `Protocol.kt:501-513` | Mirror server `MAX_TOTAL_BUFFERED_BYTES` on client |
| C7 | LOW | Fragile substring JSON envelope parser (self-DoS on adversarial `content`) | `Protocol.kt:535-603` | Real JSON parser / length-delimited fields |
| C8 | LOW | Negative temp-id collision on same-ms sends | `ChatController.kt:469,541` | Use a monotonic counter, not `-System.currentTimeMillis()` |
| S1 | MED | Auth DoS: no global rate-limit; password length unbounded before Argon2; Argon2 mem not counted vs buffer cap | `relay/mod.rs:60,75`, `crypto/mod.rs:29-38`, `protocol.rs:160` | Bound pw length; global auth throttle w/ backoff |
| S2 | MED/LOW | Auth nonce not single-use (replay within 30 s) | `relay/auth.rs:62-97` | Delete nonce after use |
| S3 | LOW | Re-auth allowed on authenticated session | `relay/auth.rs:123-188` | Skip re-auth if already authenticated |
| S4 | LOW | TLS private key is unencrypted PEM at rest | `cert/mod.rs:260-274` | Encrypt key or use OS/hardware keystore |
| — | INFO | AES-256-GCM nonce reuse: **NONE**; BCPQC-unavailable fails secure; secrets redacted in logs/crash | `PqcCrypto.kt:186-193`, `ChatController.kt:149-170`, `LogManager.kt:53-69` | — |

## 2. Protocol correctness (client Protocol.kt vs server protocol.rs / framing.rs)

- **Opcodes agree** on both sides: `0x01..0x08, 0x0B, 0x0C`; `0x09/0x0A` reserved on both.
- **Divergences (doc, not wire):**
  - `OP_AUTH_RESULT` status byte: wire uses `0x01`=success; server/client docs said `0`=ok. **Fixed in README** this session.
  - `OP_AUTH` wire = `[u32 pwd_len][pwd][32 raw HMAC]`; in-code comments (`Protocol.kt:18`, `protocol.rs:10`) wrongly say "SHA-256 hex hash". **Fix comments.**
- **Latent mines:**
  - Client `frameLength` allows `MAX_PAYLOAD_BYTES*2` (2 MiB) but `Reader.bytes()` + server cap enforce 1 MiB → stream desync if server ever emits >1 MiB. **Unify the constant.**
  - Client `buildAuth` makes HMAC optional (`null` nonce default) while server requires it → 32-byte desync if ever sent without nonce.
  - **No protocol version negotiation** → silent auth failure on version skew.
- **Verified:** framing length checks (framing.rs:25-73) bound packets; server `BudgetedBuffer` + per-IP limits present.

## 3. Philosophy / cleanliness / stubs / mines

- **No test stubs, no silent security-downgrading catch→insecure-defaults** in crypto/verify paths (those fail-closed). Forbidden zone respected.
- **No AI-agent artifacts tracked in VCS** (`.superpowers/`, `docs/superpowers/` correctly gitignored).
- Mines to fix first:
  1. **X1 (HIGHEST):** client hardcodes Argon2 `m=19456,t=2` (= server `Params::default()`) but AGENTS.md mandates OWASP `m=47104,t=3`. If server is "corrected", every client silently fails to auth. **Derive client params from server-sent salt/params, or share one constant + integration test.**
  2. **C2:** BouncyCastle `1.79 < 1.84` (`libs.versions.toml:17`) — CVE exposure + convention violation.
  3. **X2:** inconsistent 1 MiB vs 2 MiB payload bounds (`Protocol.kt:453/463/509` vs `:110`).
  4. **C1 (runBlocking):** `ChatController.kt:236` `disconnect()` + `WebTransportClient.kt:102` `destroy()` use `runBlocking` on a plain `fun` → main-thread block / ANR risk (AGENTS forbids this). Make `suspend` or launch on IO.
  5. **C3:** naive substring JSON parser for signed envelope (`Protocol.kt:556-589`).
  6. **C4:** deprecated Camera2 APIs under `@Suppress("DEPRECATION")` (`QrScanScreen.kt:703,818`) — future-break on targetSdk 36.
  7. **C6:** `OP_AUTH` doc drift (real format ≠ comment).

## 4. Library-vs-self-written

- **Server:** only maintained crates (argon2, hmac, sha2, rustls+aws-lc-rs w/ X25519Kyber768, rcgen, x509-parser, wtransport, serde/toml). No hand-rolled primitives. Only nit: `cert/mod.rs:68` hand-rolls hex though `hex` crate is a dep → use `hex::encode`.
- **Client:** BC (PQC + Argon2), JCA (AES/HMAC/SHA), `socket-http3/quic` (WebTransport/QUIC), platform Base64/JSON. No hand-rolled ciphers.
- **Actionable (mostly S-effort):**
  - Bump BC `1.79 → 1.84` (S, highest value).
  - Client `Hex.kt` → `HexFormat`/BC `Hex` (S).
  - Inner-envelope JSON → `kotlinx-serialization-json` (S, low severity — envelope is encrypted+signed before parse).
  - `PqcCrypto.toAesKey` SHA-256 → **HKDF-SHA256** (S, RFC 9180 context binding).
  - `SecureStorage`: keep (avoids Tink deps) but prefer `commit()` + encrypt key names, or evaluate AndroidX `EncryptedSharedPreferences` (M, only if offline-build cost OK).
- **Justified in-house (keep):** binary wire framing, PQC per-recipient KEM-wrapping blob, TOFU trust policy glue, server in-RAM ring-buffer store.

## 5. Top-5 priorities for a future fix-SPEC (code NOT changed yet)

1. **C1/J1** — close key-substitution (attestation + no-overwrite + server origin tag).
2. **C2** — remove plaintext from outbox (encrypt at rest / store only ciphertext).
3. **C3/J5** — remove raw password from wire (PAKE or HMAC-only).
4. **C4/C5** — replay cache + identity binding.
5. **X1/S1/S2/C6** — Argon2 param parity + auth DoS hardening + client buffer cap.

## 6. Release hygiene (done this session)

- client 2.9.1 + server 2.7.3 pushed, tagged, released. CI quirks fixed: client APK
  built locally (keystore `Tag>30`); server `Cargo.lock` committed alongside version bump.
- README (EN/RU ×2) rewritten for production; opcodes corrected; honest platform matrix.
- Unified release-notes style: EN+RU, "What's new / Что нового", asset table.
