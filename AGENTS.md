# AGENTS.md — Impulse Client (Android/Kotlin)

Дополнение к корневому `../AGENTS.md`. Специфика client-части.

## Стек
- Kotlin 2.0+, Jetpack Compose (Material 3), Gradle (KSP, не kapt).
- Точка входа: `app/src/main/java/com/example/impulse/`.
- Логика: `ChatController` (оркестратор) + `AuthManager`, `MessageEncryptor`,
  `MessageDecryptor`, `KeyExchangeHandler`, `ConnectionOrchestrator` (DI через `AppContainer`).
- Crypto: `security/PqcCrypto.kt` (ML-KEM-768, ML-DSA-65, AES-256-GCM),
  `security/SecureKeyManager.kt`, `security/SecureStorage.kt`.
- Хранение: Room (`data/db/`), TTL 72ч, шифр AES-256-GCM.

## Команды
- Сборка debug: `./gradlew assembleDebug`
- Тесты: `./gradlew testDebugUnitTest` (androidTest: `MessageDaoTest`, `TrustedCertManagerTest`)

## Правила
- **Главный поток:** НЕ `runBlocking` на main (исправлено в аудите).
  Сетевые операции — в корутинах.
- **Race conditions:** атомарность через `AtomicReference` (не `@Volatile` для
  read-then-clear). См. `AuthManager.kt`.
- **Подпись сообщений:** БД сообщения отображаются ТОЛЬКО после проверки
  ML-DSA-65 (исправлено в аудите).
- **Key material:** зероить в памяти после использования (`clearInMemoryKeys`).
- **Backup:** DSA-ключи + IV сохраняются; не удалять бэкап при частичном сбое.
- **BouncyCastle:** версия ≥ 1.84 (CVE-фиксы). ProGuard-правила для PKIX сохранены.
- **Wire-протокол:** `transport/Protocol.kt` (опкоды 0x01–0x0C). Правки — с server + SPEC.

## Тесты (покрыть, из аудита)
- `ConnectionManager`, TOFU-верификация (`TrustedCertManager`), lifecycle `MessageRepository`.
- Интеграционные с реальным сервером — пока нет (TODO).
