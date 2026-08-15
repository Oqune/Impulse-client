# Contributing — Impulse Client (Android/Kotlin)

> Дополнение к корневому `../AGENTS.md` и `docs/policies/policies.md`.
> Процесс релиза и работы с подписанными APK описан в `docs/policies/release.md`.

## Commits
- Conventional Commits: `feat:` `fix:` `docs:` `refactor:` `style:` `test:` `chore(release):`
- Язык сообщений — английский. Без эмодзи.
- Атомарно: одна логическая правка = один коммит.
- **НЕ коммить:** `keystore/` (в т.ч. `impulse-release.jks`, `keystore-password.txt`),
  `*.apk`, `release-apks/`, `.superpowers/`, `docs/superpowers/`, `*.log`.

## Branching (trunk-based)
- `master` — стабильная, развёртываемая.
- Фича/фикс → короткая ветка `feat/<topic>` / `fix/<topic>` → merge в `master`.
- Висячих веток «на потом» нет (старые `v26-wip` удалены/потеряны — не искать).

## Test-gate (ОБЯЗАТЕЛЬНО перед коммитом)
Клиент требует **JDK 17+**. На ПК установлены JDK 8/17/21/22/23/24, но в `PATH`
по умолчанию JDK 8 (Adoptium), который НЕ подходит для AGP 8.13.

```bash
# Windows (PowerShell/Git Bash)
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
$env:PATH = "$env:JAVA_HOME\bin;" + $env:PATH
./gradlew testDebugUnitTest
```

Проверено 2026-08-15: `testDebugUnitTest` → BUILD SUCCESSFUL (JDK 17.0.12).

## Forbidden zones (требуют SPEC + координации)
- Wire-протокол (`transport/Protocol.kt`, опкоды 0x01–0x0C) — только с server + SPEC.
- Crypto (ML-KEM-768, ML-DSA-65, AES-256-GCM, Argon2id) — только через SPEC + аудит.

## Релиз (подписанные APK)
См. `docs/policies/release.md`. Кратко:
- **Рабочий способ (используется сейчас):** локальная сборка агентом на ПК →
  `./gradlew assembleRelease` (читает `keystore/impulse-release.jks` +
  `keystore-password.txt`) → APK в `app/build/outputs/apk/release/` →
  ручная заливка в GitHub Release.
- **GitHub CI (`client-build.yml`):** НЕ работает (последние 5 runs failure).
  Причина: `KeytoolException: Tag number over 30` — keystore сгенерирован в
  новом JDK, на CI (ubuntu JDK 17) не читается. Требует фикса (см. release.md).
- Владелец подписи: локальный агент на ПК. Keystore НЕ покидает ПК.

## Что делать при краше на устройстве
См. `docs/specs/crash-investigation.md`. Кратко: собрать `logcat`, модель/Android
API/ABI устройства, версию приложения. Известные гипотезы: BouncyCastle PQC
provider недоступен на части устройств; native `.so` и 16 KB page-size
(Android 15+); ABI x86 (QUIC-деградация).
