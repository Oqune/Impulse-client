<div align="center">

[🇬🇧 English](README.md) | [🇷🇺 **Русский**](README.ru.md) | [🇨🇳 中文](README.zh.md)

![logo](logo.png)

[![Kotlin](https://img.shields.io/badge/Kotlin-2.0%2B-purple?logo=kotlin)](https://kotlinlang.org)
[![Android](https://img.shields.io/badge/Platform-Android%20%7C%20Wear%20OS-lightgrey)](https://www.android.com)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![WebSocket](https://img.shields.io/badge/WebSocket-OkHttp-green)](https://square.github.io/okhttp/)

Минимальный, самостоятельно развёртываемый, зашифрованный LAN-клиент чата для Android. Работает в паре с [сервером Impulse](https://github.com/Oqune/Impulse-server/).

</div>

## Возможности

- 🔌 Постоянное WebSocket-соединение с автоматическим переподключением (экспоненциальная задержка).
- 🛡️ Опциональное шифрование сообщений для каждого сервера (AES, ключ задаётся в приложении).
- 📱 Нативный Android-интерфейс на Jetpack Compose (Material 3).
- 🌗 Поддержка светлой / тёмной / системной темы.
- 🔒 Опциональная биометрическая блокировка запуска приложения (отпечаток / лицо) через Android BiometricPrompt.
- 🗂️ Изолированная история чата для каждого сервера.
- 💬 Чистые «пузыри» сообщений — ваши собственные сообщения не дублируются (эхо от сервера отфильтровывается), а системные уведомления, события входа/выхода и ошибки не засоряют чат.
- ⌨️ Настроенная работа с экранной клавиатурой: строка ввода никогда не перекрывает сообщения.

## Сборка и запуск

Требования: Android Studio (AGP 8.13+), JDK 17, Android SDK 34+.

```bash
git clone https://github.com/Oqune/Impulse-client.git
cd Impulse-client
./gradlew assembleDebug        # либо откройте в Android Studio и запустите 'app'
```

Установите APK из `app/build/outputs/apk/debug/` на устройство или эмулятор.

## Настройка

1. Откройте приложение → **Серверы** → добавьте URL сервера (например, `wss://192.168.1.50:8443`).
2. (По желанию) укажите ключ шифрования, общий с сервером.
3. Выберите отображаемое имя и начинайте общение.

> Сервер Impulse принимает соединения **только** по защищённому `wss://` (TLS обязателен). Для самоподписанного LAN-сертификата клиент доверяет сертификату сервера и пропускает проверку имени хоста — это допустимо для самостоятельно развёрнутого LAN-чата, где вы контролируете оба конца.

## Структура проекта

```
app/src/main/java/com/example/impulse/
├── MainActivity.kt
├── websocket/WebSocketManager.kt        # единый WebSocket-синглтон + переподключение
├── service/WebSocketForegroundService.kt # держит сокет живым в фоне
├── ui/screens/ChatScreen.kt           # UI чата + ChatHistoryManager
├── ui/screens/*.kt                    # Home, Settings, Server, User, Biometric…
├── data/                              # ServerConfig, настройки
├── encryption/MessageEncryption.kt    # помощники AES
└── util/                              # NameGenerator, LogStorage, BiometricHelper
```

## Лицензия

MIT — см. [LICENSE](LICENSE). Клиент и сервер предназначены для совместного использования.
