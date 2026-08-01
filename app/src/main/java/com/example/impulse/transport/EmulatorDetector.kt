package com.example.impulse.transport

/**
 * Returns true only for actual emulator builds, so that real devices (including
 * GSI/AOSP-based budget phones with a generic fingerprint) are never rejected.
 *
 * An emulator is recognized by either:
 *  - the emulator kernel hardware id (goldfish / ranchu), or
 *  - a `generic` fingerprint AND an emulator-looking model string.
 */
internal fun isEmulatorBuild(hardware: String, model: String, fingerprint: String): Boolean {
    val hw = hardware.lowercase()
    if (hw == "goldfish" || hw == "ranchu") return true
    val genericFingerprint = fingerprint.startsWith("generic", ignoreCase = true)
    val emulatorModel = model.contains("sdk", ignoreCase = true) ||
        model.contains("emulator", ignoreCase = true) ||
        model.contains("google_sdk", ignoreCase = true)
    return genericFingerprint && emulatorModel
}
