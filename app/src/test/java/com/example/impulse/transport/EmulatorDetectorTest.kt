package com.example.impulse.transport

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmulatorDetectorTest {

    @Test
    fun realSamsungDeviceIsNotEmulator() {
        assertFalse(isEmulatorBuild("beyond1q", "SM-G973F", "samsung/beyond1q/beyond1q:12/SP1A.210812.016/G973FXXS9FVG1:user/release-keys"))
    }

    @Test
    fun gsiDeviceWithGenericFingerprintIsNotEmulator() {
        // Real budget/GSI phones ship a generic fingerprint but a real model.
        assertFalse(isEmulatorBuild("beyond1q", "Redmi Note 8", "generic/generic/generic:11/RKQ1.200826.002/V11.0.2.0:user/release-keys"))
    }

    @Test
    fun goldfishHardwareIsEmulator() {
        assertTrue(isEmulatorBuild("goldfish", "sdk_gphone_x86", "google/sdk_gphone_x86/generic_x86:12/SE2A.220304.001/8372401:user/release-keys"))
    }

    @Test
    fun ranchuHardwareIsEmulator() {
        assertTrue(isEmulatorBuild("ranchu", "Android SDK built for x86_64", "google/sdk_gphone_x86_64/emu64x:11/RQ1A.210105.003/7139966:user/release-keys"))
    }

    @Test
    fun genericFingerprintPlusSdkModelIsEmulator() {
        assertTrue(isEmulatorBuild("beyond1q", "Android SDK built for x86", "generic/generic/generic:12/SE2A.220304.001/8372401:user/release-keys"))
    }
}
