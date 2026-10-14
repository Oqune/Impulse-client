# Re-lock biometric gate after backgrounding

Date: 2026-10-14
Status: Approved by project owner

## Problem

The biometric gate currently keeps `isUnlocked` in `rememberSaveable`, so it survives background/foreground and can also be restored after process recreation. Meanwhile `MainActivity.onStart` connects the selected relay before the user passes the gate.

## Requirements

- When biometric lock is enabled and available, leaving the foreground must return the UI to the lock screen and disconnect the relay as today.
- The lock screen may remain rendered while stopped, but it must not launch a system authentication prompt until the Activity is started again.
- Do not persist unlocked state into saved instance state or process restoration.
- Preserve unlock state across configuration changes only, using Activity-retained `ViewModel` state; configuration recreation must not prompt again.
- Do not connect to the selected server until biometric unlock succeeds. On returning to foreground after a real background stop, require a fresh unlock before reconnecting.
- When biometric locking is not enabled/available, preserve the existing open-app and automatic-connect behavior.
- Keep the existing biometric authentication flow, device-credential allowance, UI text, and cryptographic/security helper APIs unchanged.
- Add only a lifecycle gate to the existing lock screen prompt so a relock during `onStop` does not prompt from the background.
- If platform enrollment or device credential support is unavailable, report that runtime verification is limited; do not invent a new credential fallback or change enrollment UX in this patch.

## Validation

- Project owner will run the existing build and exercise unlock, background/foreground relock, no-background relay disconnect, and configuration recreation on an enrolled emulator/device if platform support permits.
- Verify that the selected server is not connected before unlock when lock is enabled.
