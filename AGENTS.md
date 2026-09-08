# AGENTS.md — Impulse Client (Android/Kotlin)

Дополнение к `PROJECT.md`, `AI_MANIFESTO.md` и канону видения `docs/VISION.md`. Специфика Android-клиента.

## Дизайн-код и философия UI
- **Эстетика:** Тактильный минимализм, эргономика GitHub Mobile в сочетании с надежностью современных VPN-клиентов (Mullvad).
- **Визуальные стандарты:** Глубокий OLED Pure Black по умолчанию, адаптивное матовое стекло (`GlassSurface`), открытые криптографические бейджи и хэши, отсутствие soft-теней (`ImpulseElevation.card = 0.dp`), плавные кинетические переходы.

## Стек и архитектура
- **Язык & UI:** Kotlin 2.0+, Jetpack Compose (Material 3 Expressive), Gradle (KSP).
- **Точка входа:** `app/src/main/java/com/example/impulse/`.
- **Архитектура:** Чистая декомпозиция `ChatController` через внедрение зависимостей `AppContainer`:
  - `AuthManager` — рукопожатие, challenge-response аутентификация.
  - `MessageEncryptor` — сквозное шифрование и отправка сообщений.
  - `MessageDecryptor` — проверка подлинности и расшифровка входящих фреймов.
  - `KeyExchangeHandler` — обмен постквантовыми ключами с валидацией аттестации.
  - `ConnectionOrchestrator` — управление жизненным циклом WebTransport/QUIC сессий.
- **Постквантовая криптография:** BouncyCastle 1.84+ (`security/PqcCrypto.kt`):
  - KEM: ML-KEM-768
  - DSA: ML-DSA-65 (проверка подписи всех входящих и исторических сообщений)
  - Симметричное шифрование: AES-256-GCM
  - Хэширование и KDF: HKDF-SHA256, Argon2id (OWASP $m=47104, t=3, p=1$)
- **Хранилище:** Room Database (KSP), зашифрованное хранилище ключей Android KeyStore (`SecureStorage.kt`).

## Инженерные правила для ИИ-агента
1. **Потокобезопасность и корутины:**
   - Никаких вызовов `runBlocking` на `Dispatchers.Main` — сетевые и криптографические операции выполняются строго в корутинах на `Dispatchers.IO` или `Dispatchers.Default`.
   - Использование потокобезопасных примитивов (`AtomicReference` для атомарных операций read-then-clear, StateFlow для UI состояния).
2. **Безопасность ключевого материала:**
   - Все байтовые массивы с приватными ключами и секретами немедленно зануляются в памяти (`fill(0)`) после завершения криптографической операции.
   - Сообщения из локальной БД отображаются в UI ТОЛЬКО после успешной верификации цифровой подписи ML-DSA-65.
3. **Wire-протокол:**
   - Строгая побайтовая синхронизация с сервером по опкодам `0x11`–`0x34` (`transport/Protocol.kt`).

## Test-Gate (Обязательно перед коммитом)
```powershell
# Требуется JDK 17+
./gradlew testDebugUnitTest
./gradlew assembleDebug
```
