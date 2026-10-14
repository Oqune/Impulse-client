# Supported ABI transport on Android emulators

The current x86_64 APK contains libquiche.so and libquiche_jni.so, but WebTransportClient rejects every emulator before native initialization. This prevents real local relay testing and rejects a supported ABI.

Replace the blanket emulator rejection with an ABI capability check shared by physical devices and emulators. Supported native ABIs are arm64-v8a, armeabi-v7a and x86_64 as verified in packaged APKs; x86 remains unsupported. Preserve certificate pinning, auth, Wire v3 and connection error behavior. Catch native linkage failure as an explicit connection error without a process crash. No test bypass, trust override, mock transport or build-only switch.

Validation: unit test supported x86_64 emulator, supported ARM devices, unsupported x86/unknown ABIs; build/test/lint; actual API36 x86_64 emulator connection to a locally launched relay. Package presence alone is not handshake evidence.
