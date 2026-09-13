# Release Policy — Impulse Client (Android)

Пошаговый процесс выпуска подписанных APK. Версия приложения:
`versionCode` / `versionName` в `app/build.gradle.kts` (сейчас 14 / "2.9.0").

## 1. Подготовка
- Убедиться, что `master` чист и тесты зелёные (требуется JDK 17+):
  ```powershell
  ./gradlew testDebugUnitTest
  ```
- Поднять `versionCode` (+1) и `versionName` в `app/build.gradle.kts`.
- Закоммитить изменение версии (Conventional Commits: `chore(release): vX.Y.Z`).

## 2. Сборка подписанного APK (локально, ПК)
Подпись идёт локальным keystore — **keystore НЕ покидает ПК**.
Файлы: `keystore/impulse-release.jks` + `keystore/keystore-password.txt`
(оба в `.gitignore`).

```powershell
./gradlew assembleRelease
```
Результат: `app/build/outputs/apk/release/ImpulseClient-{abi}-release.apk`
(arm64-v8a, armeabi-v7a, x86_64, x86 + universal).

> ⚠️ **ABI x86:** `socket-quic-quiche-android` не поставляет x86 native lib →
> на x86 QUIC деградирует до эмулятор-гварда (по дизайну, не краш).
> Universal APK собирается, но x86-устройства будут без WebTransport.

## 3. Заливка в GitHub Release
1. Создать git-tag `vX.Y.Z` и запушить: `git tag vX.Y.Z && git push origin vX.Y.Z`.
2. В GitHub → Releases → New release (tag `vX.Y.Z`) → прикрепить APK из
   `app/build/outputs/apk/release/`.

> Текущий рабочий процесс (используется агентом): сборка локально (п.2) +
> ручная заливка APK в GitHub Release (п.3). GitHub Actions CI сейчас НЕ
> используется для релиза (см. Known Issues).

## 4. GitHub CI (`client-build.yml`) — Автоматизация релизов
- Триггер: push в `master`/`main` или tag `v*`, PR.
- **Подпись в CI:** При push тега `v*` воркфлоу проверяет наличие `secrets.ANDROID_KEYSTORE_BASE64` и `secrets.KEYSTORE_PASSWORD`.
  - При наличии секретов: восстанавливает keystore и пароль, валидирует ненулевой размер (`ksFile.length() > 0`) и автоматически подписывает релизные APK.
  - При отсутствии секретов: собирает неподписанный release APK без падения пайплайна (устранена ошибка `Tag number over 30 is not supported`, возникавшая из-за попытки чтения пустого 0-байтного файла).
- Релизы на GitHub создаются с унифицированным заголовком `Impulse Client v<Major>.<Minor>.<Patch>`.

## 5. Стандарт версионирования (Protocol-Locked SemVer)
Формат версий: `v<ProtocolMajor>.<ComponentMinor>.<Patch>`
- **ProtocolMajor (первая цифра, эра v3+):** Сетевая совместимость E2EE Wire-протокола (опкоды `0x11`–`0x34`). Любой клиент `v3.x` совместим с любым сервером `v3.y`.
- **ComponentMinor:** Независимый функциональный инкремент Android-клиента (`v3.0.x`).
- **Patch:** Багфиксы, безопасность и UI-оптимизации.

## 6. Безопасность секретов
Keystore и пароль хранятся локально в `keystore/` (в `.gitignore`). Для автоматической сборки в GitHub Actions они загружаются в зашифрованные **GitHub Repository Secrets** (`ANDROID_KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`) через sealed box Libsodium. Локальные файлы ключей никогда не коммитятся в git.
