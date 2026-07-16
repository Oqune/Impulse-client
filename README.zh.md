<div align="center">

[🇺🇸 English](README.md) | [🇷🇺 Русский](README.ru.md) | [🇨🇳 **中文**](README.zh.md)

![logo](logo.png)

[![Kotlin](https://img.shields.io/badge/Kotlin-2.0%2B-purple?logo=kotlin)](https://kotlinlang.org)
[![Android](https://img.shields.io/badge/Platform-Android-lightgrey)](https://www.android.com)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![WebSocket](https://img.shields.io/badge/WebSocket-OkHttp-green)](https://square.github.io/okhttp/)

极简、可自托管的端到端加密局域网聊天 Android 客户端。配合 [Impulse 服务器](https://github.com/Oqune/Impulse-server/) 使用。

</div>

## 功能

- 🔌 持久化 WebSocket 连接，支持自动重连（指数退避）。
- 🛡️ 每个服务器可选的端到端消息加密（AES，密钥在应用内设置）。
- 📱 基于 Jetpack Compose（Material 3）的原生 Android 界面。
- 🌗 支持浅色 / 深色 / 跟随系统主题。
- 🔒 可选的启动生物识别锁（指纹 / 面部），基于 Android BiometricPrompt。
- 🗂️ 每个服务器独立的聊天历史记录。
- 💬 清爽的「气泡」对话——自己发送的消息不会重复显示（已过滤服务器回显），系统通知、加入/离开事件与错误信息不会污染聊天记录。
- ⌨️ 针对软键盘做了优化：输入栏永远不会遮挡消息。

## 构建与运行

环境要求：Android Studio（AGP 8.13+）、JDK 17、Android SDK 34+。

```bash
git clone https://github.com/Oqune/Impulse-client.git
cd Impulse-client
./gradlew assembleDebug        # 或在 Android Studio 中运行 'app'
```

将 `app/build/outputs/apk/debug/` 中的 APK 安装到设备或模拟器。

## 配置

1. 打开应用 → **服务器** → 添加服务器地址（如 `wss://192.168.1.50:8443`）。
2. （可选）设置与服务器共享的加密密钥。
3. 选择显示名称即可开始聊天。

> Impulse 服务器**仅**监听安全的 `wss://` 端点（TLS 为强制要求）。对于自签名的局域网证书，客户端信任服务器证书并跳过主机名校验——在您同时掌控两端的自托管局域网聊天中是可接受的。

## 项目结构

```
app/src/main/java/com/example/impulse/
├── MainActivity.kt
├── websocket/WebSocketManager.kt        # 单一 WebSocket 单例 + 重连
├── service/WebSocketForegroundService.kt # 后台保活连接
├── ui/screens/ChatScreen.kt           # 聊天界面 + ChatHistoryManager
├── ui/screens/*.kt                    # 主页、设置、服务器、用户、生物识别…
├── data/                              # ServerConfig、偏好设置
├── encryption/MessageEncryption.kt    # AES 辅助方法
└── util/                              # NameGenerator、LogStorage、BiometricHelper
```

## 许可证

MIT —— 见 [LICENSE](LICENSE)。客户端与服务器应配合使用。
