# UI cryptography labels — 2026-10-13

## Purpose

Correct user-visible client labels that currently identify the signing implementation as ML-DSA-65. The checked-in provider calls the Bouncy Castle `Dilithium` API with `DilithiumParameterSpec.dilithium3`; UI text must say Dilithium3 and must not imply FIPS 204 conformance.

## Scope

- Update the Home screen cryptography badge to display `Dilithium3`.
- Update the existing RU/EN key-reset descriptions to identify the signing key as Dilithium3 while retaining the current resource keys and UI behavior.
- Preserve the ML-KEM-768 labels, layout, theme, resource identifiers, and all cryptographic APIs and protocol behavior.
- Do not rename symbols or protocol fields; do not label the current implementation ML-DSA-65/FIPS 204. A future ML-DSA migration requires an approved protocol SPEC and synchronized implementation.

## Validation

Inspect the changed UI literals and translations; search runtime UI resources for stale ML-DSA-65/FIPS 204 labels. Do not build or compile as the active owner is running a Gradle build.
