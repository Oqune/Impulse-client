<div align="center">

[🇺🇸 English](README.md) | [🇷🇺 **Русский**](README.ru.md)

![logo](logo.png)

[![Kotlin](https://img.shields.io/badge/Kotlin-2.0%2B-purple?logo=kotlin)](https://kotlinlang.org)
[![Android](https://img.shields.io/badge/Platform-Android%2013%2B-lightgrey)](https://www.android.com)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![WebTransport](https://img.shields.io/badge/Transport-WebTransport-blue)](https://developer.android.com/reference/android/net/http/WebTransport)
[![PQC](https://img.shields.io/badge/Crypto-ML--KEM--768%20%2F%20AES--256--GCM-green)](https://en.wikipedia.org/wiki/ML-KEM)

Минимальный, самостоятельно развёртываемый, **постквантовый сквозь-шифрованный** LAN-клиент чата для Android.
Работает в паре с [сервером Impulse](https://github.com/Oqune/Impulse-server/).

</div>

## Что изменилось в v2.0 (полная переписывание)

Клиент полностью переписан под современный, устойчивый к квантовым компьютерам стек:

| Область | Старое (удалено) | Новое |
|---------|------------------|------|
| Транспорт | `WebSocket` (OkHttp, `wss://`) | **`WebTransport`** (Android `android.net.http`, API 33+, HTTPS/QUIC) |
| Аутентиф. сервера | доверять любому самоподписанному | **Привязка сертификата** через `serverCertificateHashes` (TOFU) |
| Обмен ключами | нет / статический ключ | **ML-KEM-768** (Kyber) постквантовый KEM |
| Шифр сообщений | слабый AES-ECB | **AES-256-GCM** с групповым общим секретом |
| Локальное хранилище | открытый `SharedPreferences` | **Room + SQLCipher** (шифрование при хранении, TTL 72 ч) |
| Подключение | ручной URL + ключ | **Сканирование QR** для хеша сертификата |

## Возможности

- 🚀 **WebTransport**-транспорт (Android 13+), полностью заменяющий WebSocket.
- 🔐 **Постквантовое E2EE**: генерация ML-KEM-768, обмен через сервер,
  независимый от порядка групповой секрет и шифрование AES-256-GCM.
- 📷 **Привязка сертификата (TOFU)** через QR-код (`impulse-cert:<sha256>`). Хеш
  сохраняется в `EncryptedSharedPreferences`; хранится до двух хешей
  (текущий + следующий) для плавной ротации сертификата.
- 🗄️ **Зашифрованная история** на Room + SQLCipher. Сообщения хранят
  `server_id`, `server_msg_id`, `sender`, `ciphertext`, `iv`, `timestamp`.
  Автоматическая очистка по **TTL 72 часа**.
- 📱 Интерфейс на Jetpack Compose (Material 3): список чата, строка ввода,
  сканер QR, настройки.
- 🔁 Автоматическое переподключение с экспоненциальной задержкой; foreground-сервис
  держит соединение живым; receiver переподключает после перезагрузки.
- 🌗 Светлая / тёмная / системная темы и опциональная биометрическая блокировка.

## Требования

- **Android 13 (API 33)** или новее (устройство/эмулятор).
- Android Studio (AGP 8.13+), **JDK 17**, Android SDK 34+.
- Сервер, отдающий **WebTransport** handshake по HTTPS/QUIC на
  настроенном host:port (по умолчанию `11000`). Сервер ретранслирует JSON-кадры
  `hello`, `key_exchange`, `chat`, `history` и `cert_hash` (см.
  [`transport/Protocol.kt`](app/src/main/java/com/example/impulse/transport/Protocol.kt)).

## Сборка и установка

```bash
git clone https://github.com/Oqune/Impulse-client.git
cd Impulse-client
./gradlew assembleDebug        # либо откройте в Android Studio и запустите 'app'
```

Установите APK из `app/build/outputs/apk/debug/` на устройство/эмулятор
(API 33+). Разрешение камеры запрашивается при первом сканировании QR.

## Первый запуск

1. Откройте приложение → **Главная** → нажмите **Подключиться**.
2. Так как хеш сертификата ещё не доверен, состояние станет
   *«Ошибка / нужен QR»*.
3. Перейдите на вкладку **QR** и отсканируйте QR-код сервера (формат
   `impulse-cert:<hex-sha256-сертификата>`). Хеш привязывается, и клиент
   сразу переподключается.
4. После успешного handshake сервер может прислать *следующий* хеш
   (`cert_hash`); он сохраняется как вторая запись для ротации.
5. Публичные ключи ML-KEM-768 обмениваются автоматически; когда известны
   секреты всех участников, вычисляется групповой секрет и чат полностью E2EE.

## Структура проекта

```
app/src/main/java/com/example/impulse/
├── MainActivity.kt                     # точка входа, биометрия, foreground-сервис
├── ChatController.kt                   # оркестрация транспорта + крипты + хранилища + обмена ключами
├── transport/
│   ├── WebTransportClient.kt           # сессия WebTransport + serverCertificateHashes + переподключение
│   ├── Protocol.kt                     # JSON-протокол (hello/chat/key_exchange/history/cert_hash)
│   └── ConnectionState.kt
├── security/
│   ├── PqcCrypto.kt                    # ML-KEM-768 KEM + AES-256-GCM + групповой секрет
│   ├── SecureStorage.kt                # EncryptedSharedPreferences (ключи + хеши сертификатов)
│   └── TrustedCertManager.kt           # TOFU, ротация до 2 хешей
├── data/
│   ├── ServerConfig.kt                 # WebTransport URL (https://host:port)
│   ├── ServerPreferences.kt
│   └── db/                             # Room-сущности, DAO, SQLCipher БД, репозиторий (TTL 72 ч)
├── service/WebTransportForegroundService.kt
├── receiver/BootReceiver.kt
└── ui/screens/                        # MainScreen, HomeScreen, ChatScreen, QrScanScreen, Settings…
```

## Тестирование

```bash
./gradlew test            # юнит-тесты: PqcCrypto (KEM + AES, групповой секрет)
./gradlew connectedAndroidTest   # инструментальные: TrustedCertManager (TOFU, max-2-hash)
```

## Лицензия

MIT — см. [LICENSE](LICENSE). Клиент и сервер предназначены для совместного использования.
