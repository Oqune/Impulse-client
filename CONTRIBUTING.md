# Contributing — Impulse Client (Android/Kotlin)

> Разработка ведется по стандартам **AI-Assisted Engineering** (см. `../AI_MANIFESTO.md` и `../AGENTS.md`).

## Стандарты кода и коммитов
- **Conventional Commits:** `feat:`, `fix:`, `refactor:`, `test:`, `docs:`, `chore(release):`.
- **Чистота репозитория:** Запрещено коммитить `keystore/`, пароли keystore, скомпилированные `.apk`, логи и временные каталоги агентов.
- **Ветвление:** Trunk-based (`master` готов к релизу). Разработка ведется в коротких ветках `feat/*` или `fix/*`.

## Требования к окружению и сборке
- **JDK:** Версия 17 или 21 (рекомендуется Eclipse Temurin или Amazon Corretto).
- **Android SDK:** Compile SDK 35, Min SDK 26, Target SDK 34/35.
- **Команды проверки:**
  ```powershell
  ./gradlew testDebugUnitTest
  ./gradlew assembleDebug
  ```

## Безопасность и криптография
- Любые изменения в `PqcCrypto.kt`, `SecureKeyManager.kt` или логике обмена ключами `KeyExchangeHandler.kt` требуют предварительной спецификации в `docs/specs/`.
- Запрещено понижать версии BouncyCastle ниже 1.84.
