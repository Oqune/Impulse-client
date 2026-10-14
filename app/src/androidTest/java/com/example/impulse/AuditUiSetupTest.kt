package com.example.impulse

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.impulse.data.ServerConfig
import com.example.impulse.data.ServerPreferences
import com.example.impulse.security.TrustedCertManager
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in setup for manual UI checks on an isolated audit device only.
 * Saves actual settings through production APIs; it does not fake connection
 * status or alter transport/security. Retains settings for subsequent UI use.
 */
@RunWith(AndroidJUnit4::class)
class AuditUiSetupTest {
    @Test
    fun prepareIsolatedDeviceForLiveUiChecks() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit isolated audit device is required", args.getString("auditUiSetup") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val hash = args.getString("relayHash") ?: error("relayHash required")
        require(hash.matches(Regex("[0-9a-fA-F]{64}")))
        val server = ServerConfig("audit-ui", "Audit local relay",
            args.getString("relayHost") ?: error("relayHost required"),
            args.getString("relayPort", "4433").toInt(), "Local emulator verification",
            args.getString("relayPassword") ?: error("relayPassword required"))
        val prefs = ServerPreferences(context)
        prefs.saveCustomServers(listOf(server))
        prefs.saveSelectedServer(server)
        prefs.saveClientName("AuditReceiver")
        prefs.saveAllowScreenshots(true)
        prefs.setServerAutoReconnect(server.id, true)
        val biometric = args.getString("auditBiometric", "false") == "true"
        if (biometric) {
            assertTrue("Enroll a device credential on the isolated audit device first",
                com.example.impulse.util.BiometricHelper(context).isHardwareAvailable())
        }
        prefs.saveBiometricEnabled(biometric)
        TrustedCertManager(context).trustHash(server.id, hash)
        // The runner exits immediately, unlike an Activity lifecycle. Wait for
        // prior asynchronous preference writes so setup survives runner exit.
        assertTrue(context.getSharedPreferences("server_preferences", Context.MODE_PRIVATE).edit().commit())
        assertTrue(context.getSharedPreferences("impulse_secure_storage", Context.MODE_PRIVATE).edit().commit())
    }
}
