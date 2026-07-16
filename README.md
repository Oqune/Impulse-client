<!-- Logo: to be added (e.g. ![Impulse](app/src/main/res/mipmap-xxxhdpi/ic_launcher.png)) -->

# Impulse

**Impulse** is a minimal, self-hosted, end-to-end-encrypted LAN chat client for Android, designed to pair with the [Impulse server](https://github.com/Oqune/Impulse-server/).

- 🛡️ Optional per-server message encryption (AES, key exchanged in-app)
- 🔌 Persistent WebSocket connection with automatic reconnect
- 📱 Native Android UI built with Jetpack Compose (Material 3)
- 🌗 Light / Dark / System theme support
- 🔒 Optional biometric lock to open the app
- 🗂️ Per-server isolated chat history
- 🌍 Trilingual UI strings planned (EN / RU / ZH)

---

## English

### Overview
Impulse is the Android front-end for the Impulse real-time chat system. It connects to your own Impulse server over WebSocket (with optional TLS for self-signed LAN certificates) and shows a clean, bubble-style conversation. Messages you send are rendered immediately and de-duplicated when the server echoes them back, so you never see your own message twice. System, join/leave and error events are kept out of the conversation and only shown as transient status.

### Features
- Connect to multiple servers, each with its own saved history.
- Biometric app lock (fingerprint / face) via Android BiometricPrompt.
- Foreground service keeps the WebSocket alive while the app is backgrounded.
- Auto-reconnect with exponential backoff on unexpected drops.
- Adjustable soft-input handling so the keyboard never covers your messages.

### Build & run
Requirements: Android Studio (AGP 8.13+), JDK 17, Android SDK 34+.

```bash
git clone https://github.com/Oqune/Impulse-client.git
cd Impulse-client
./gradlew assembleDebug        # or open in Android Studio and Run 'app'
```

Install the APK from `app/build/outputs/apk/debug/` onto a device or emulator.

### Configuration
1. Open the app and go to **Servers** → add your server URL (e.g. `ws://192.168.1.50:8080` or `wss://…`).
2. (Optional) Set an encryption key shared with the server.
3. Pick a display name and start chatting.

### Project layout
```
app/src/main/java/com/example/impulse/
├── MainActivity.kt
├── websocket/WebSocketManager.kt      # single WebSocket singleton + reconnect
├── service/WebSocketForegroundService.kt
├── ui/screens/ChatScreen.kt           # chat UI + ChatHistoryManager
├── ui/screens/*.kt                    # Home, Settings, Server, User, Biometric…
├── data/                              # ServerConfig, preferences
├── encryption/MessageEncryption.kt    # AES helpers
└── util/                              # NameGenerator, LogStorage, BiometricHelper
```

---

## Русский

### Кратко
**Impulse** — это Android-клиент для самостоятельно развёрнутого зашифрованного LAN-чата, работающий в паре с [сервером Impulse](https://github.com/Oqune/Impulse-server/).

### Возможности
- Подключение к нескольким серверам с отдельной историей для каждого.
- Биометрическая блокировка приложения (отпечаток / лицо) через Android BiometricPrompt.
- Фоновый сервис поддерживает WebSocket активным при сворачивании приложения.
- Автоматическое переподключение с экспоненциальной задержкой.
- Корректная работа с экранной клавиатурой: она не перекрывает сообщения.
- Отправленные вами сообщения не дублируются (эхо от сервера отфильтровывается).
- Системные уведомления, события входа/выхода и ошибки не засоряют чат.

### Сборка и запуск
Требования: Android Studio (AGP 8.13+), JDK 17, Android SDK 34+.

```bash
git clone https://github.com/Oqune/Impulse-client.git
cd Impulse-client
./gradlew assembleDebug
```

Установите APK из `app/build/outputs/apk/debug/` на устройство или эмулятор.

### Настройка
1. Откройте приложение → **Серверы** → добавьте URL сервера (`ws://192.168.1.50:8080` или `wss://…`).
2. (По желанию) укажите ключ шифрования, общий с сервером.
3. Выберите отображаемое имя и начинайте общение.

---

## 中文

### 简介
**Impulse** 是一个极简、可自托管的端到端加密局域网聊天 Android 客户端，配合 [Impulse 服务器](https://github.com/Oqune/Impulse-server/) 使用。

### 功能
- 支持连接多台服务器，每台服务器拥有独立的历史记录。
- 应用生物识别锁（指纹 / 面部），基于 Android BiometricPrompt。
- 前台服务在应用退到后台时保持 WebSocket 连接。
- 意外断开后自动重连（指数退避）。
- 软键盘处理经过优化，绝不会遮挡消息。
- 自己发送的消息不会重复显示（已过滤服务器回显）。
- 系统通知、加入/离开事件与错误信息不会污染聊天记录。

### 构建与运行
环境要求：Android Studio（AGP 8.13+）、JDK 17、Android SDK 34+。

```bash
git clone https://github.com/Oqune/Impulse-client.git
cd Impulse-client
./gradlew assembleDebug
```

将 `app/build/outputs/apk/debug/` 中的 APK 安装到设备或模拟器。

### 配置
1. 打开应用 → **服务器** → 添加服务器地址（如 `ws://192.168.1.50:8080` 或 `wss://…`）。
2. （可选）设置与服务器共享的加密密钥。
3. 选择显示名称即可开始聊天。

---

## License
See the server repository for the project license. Client and server are intended to be used together.
