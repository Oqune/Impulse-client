# Release Policy — Impulse Client (Android)

Пошаговый процесс выпуска подписанных APK. Версия приложения:
`versionCode` / `versionName` в рабочем дереве `app/build.gradle.kts`: `24` / `3.2.1` (кандидат, не релиз). Последние опубликованные Git-теги: Client `v3.2.0`, Server `v3.1.0`. В рабочем дереве Server manifest указана кандидатная `v3.1.1`; оба кандидата требуют прохождения релизных гейтов и публикации тегов.

## 1. Подготовка
- Убедиться, что `master` чист и гейты зелёные (CI использует JDK 21):
  ```powershell
  ./gradlew assembleDebug testDebugUnitTest lintDebug
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
Результат локальной Gradle-сборки: `app/build/outputs/apk/release/app-{abi}-release.apk`.
Перед загрузкой переименовать в `ImpulseClient-vX.Y.Z-{abi}-release.apk`
(arm64-v8a, armeabi-v7a, x86_64, x86 + universal).

> **ABI x86 (32-bit):** `socket-quic-quiche-android` не поставляет x86 native
> QUIC-библиотеки; WebTransport на этом ABI недоступен. Runtime-проверка
> ограничивает подключение поддерживаемыми native ABI, а не запрещает эмуляторы
> как класс. ABI `x86_64` поддерживается, включая Android-эмуляторы. APK может
> включать x86 split/universal package, но его наличие не означает поддержки
> WebTransport на 32-bit x86.

## 3. Заливка в GitHub Release
1. Создать git-tag `vX.Y.Z` и запушить: `git tag vX.Y.Z && git push origin vX.Y.Z`.
2. В GitHub → Releases → New release (tag `vX.Y.Z`) → прикрепить APK из
   `app/build/outputs/apk/release/`.

Тег должен указывать на проверенную ревизию. Workflow создаёт черновик из
`docs/releases/vX.Y.Z.md`; отсутствие notes является ошибкой. До публикации
проверить подпись каждого APK, исполнение release-сборки и наличие всех
ссылок таблицы архитектур. Локальная сборка и ручная загрузка допустимы.

## 4. GitHub CI (`client-build.yml`) — Автоматизация релизов
- Триггер: push в `master`/`main` или tag `v*`, PR.
- **Подпись в CI:** При push тега `v*` воркфлоу проверяет наличие `secrets.ANDROID_KEYSTORE_BASE64` и `secrets.KEYSTORE_PASSWORD`.
  - При наличии секретов: восстанавливает keystore и пароль, валидирует ненулевой размер (`ksFile.length() > 0`) и автоматически подписывает релизные APK.
  - При отсутствии секретов: собирает неподписанный release APK без падения пайплайна (устранена ошибка `Tag number over 30 is not supported`, возникавшая из-за попытки чтения пустого 0-байтного файла).
- Релизы на GitHub создаются с унифицированным заголовком `Impulse Client v<Major>.<Minor>.<Patch>`.
- Release job принимает только подписанные `*-release.apk`; unsigned APK может
  быть CI build artifact, но не опубликованным приложением. При отсутствии
  подписанных APK release job завершается ошибкой. Черновик публикуется после
  проверки фактических assets и гейтов, а не автоматически по одному тегу.

## 5. Стандарт версионирования (Protocol-Locked SemVer)
Формат версий: `v<ProtocolMajor>.<ComponentMinor>.<Patch>`
- **ProtocolMajor (первая цифра, эра v3+):** Версия E2EE Wire-протокола (опкоды `0x11`–`0x34`). Релизы одного ProtocolMajor должны быть wire-совместимы; каждая поддерживаемая пара client/server должна подтверждаться матрицей межверсионных проверок.
- **ComponentMinor:** Независимый функциональный инкремент Android-клиента. Последний опубликованный тег Client — `v3.2.0`; кандидат `v3.2.1` в рабочем манифесте не является релизом до прохождения гейтов и публикации тега.
- **Patch:** Багфиксы, безопасность и UI-оптимизации.

## 6. Безопасность секретов
Keystore и пароль хранятся локально в `keystore/` (в `.gitignore`). Для автоматической сборки в GitHub Actions они загружаются в зашифрованные **GitHub Repository Secrets** (`ANDROID_KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`) через sealed box Libsodium. Локальные файлы ключей никогда не коммитятся в git.
