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

## 4. GitHub CI (`client-build.yml`) — STATUS
- Триггер: push в `master`/`main` или tag `v*`, PR.
- При tag `v*`: восстанавливает keystore из `secrets.ANDROID_KEYSTORE_BASE64`
  + `secrets.KEYSTORE_PASSWORD`, собирает `assembleRelease`, создаёт GitHub Release.
- **KNOWN ISSUE (2026-08-15): CI падает на всех последних runs (v2.9.0, v2.8.1,
  v2.8.0) с ошибкой:**
  ```
  KeytoolException: Failed to read key impulse from store "...impulse-release.jks":
  Tag number over 30 is not supported
  ```
  Причина: keystore сгенерирован в новом JDK (теги >30 в PKCS12), keytool на CI
  (ubuntu, JDK 17) его не читает. Локально собирается, т.к. агент использует
  тот же keystore тем же JDK.
- **TODO (фикс, НЕ делать в фазе подготовки):** пересоздать keystore совместимым
  форматом (JDK 17 `keytool`, PKCS12 без тегов >30) И/ИЛИ обновить CI на JDK 21,
  либо перенести подпись на локальную и оставить CI только для проверки сборки.

## 5. Владелец подписи
Локальный инженер/агент на доверенном ПК (keystore никогда не покидает локальную машину — требование безопасности `AGENTS.md` и `AI_MANIFESTO.md`).
Подпись выполняется локально перед загрузкой артефактов в GitHub Releases.
