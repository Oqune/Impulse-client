# UI capability label cleanup — 2026-10-13

## Changes

- `client/app/src/main/java/com/example/impulse/ui/screens/HomeScreen.kt`: the home technology badge now says `Dilithium3` / `Post-Quantum Signature`. A nearby code comment records that the current Bouncy Castle legacy API is not an ML-DSA-65/FIPS 204 implementation.
- `client/app/src/main/res/values/strings.xml`: key-reset description and warning now name the ML-KEM-768 key pair and Dilithium3 signing keys accurately.
- `client/app/src/main/res/values-ru/strings.xml`: matching Russian text now names ML-KEM-768 and Dilithium3 signing key pairs accurately.
- Resource identifiers, the rest of the UI, crypto APIs, protocol fields, and runtime behavior are unchanged.

## Validation and exclusions

- Searched live UI Kotlin and localized resources for ML-DSA-65, FIPS 204, Dilithium, and Zero-Knowledge/Zero-Metadata claims. The Home badge and the two localized key-reset descriptions were the user-facing signature labels found in scope.
- Resource-key names were preserved. No bulk rename was applied; documentation about future ML-DSA migration and historical roadmap material remains unchanged.
- No Gradle build or compile was run because the root owner has an active Gradle build.
- Crypto APIs, serialized names, protocol behavior, styling, `LiveRelayTest.kt`, and transport/agent/outbox files were not changed.
