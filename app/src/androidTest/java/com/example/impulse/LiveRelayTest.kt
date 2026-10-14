package com.example.impulse

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.impulse.data.ServerConfig
import com.example.impulse.security.TrustedCertManager
import com.example.impulse.transport.ConnectionState
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in integration against an actual external relay. No transport/trust mocks.
 * Run on two distinct Android devices with relayHost, relayPort, relayHash,
 * relayPassword, relayRun and relayRole (sender/receiver) instrumentation args.
 * Without arguments this suite is explicitly skipped, never counted as live PASS.
 */
@RunWith(AndroidJUnit4::class)
class LiveRelayTest {
    @Test
    fun realRelayAuthEncryptionDirectAndOfflineReplay() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("External relay arguments are required", args.containsKey("relayHost"))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val run = args.getString("relayRun") ?: error("relayRun required")
        val role = args.getString("relayRole") ?: error("relayRole required")
        require(role == "sender" || role == "receiver")
        val hash = args.getString("relayHash") ?: error("relayHash required")
        require(hash.matches(Regex("[0-9a-fA-F]{64}")))
        val server = ServerConfig(
            id = "audit-$run",
            name = "Local audit relay",
            ipAddress = args.getString("relayHost")!!,
            port = args.getString("relayPort", "4433").toInt(),
            description = "Isolated live integration test",
            password = args.getString("relayPassword") ?: error("relayPassword required")
        )
        val certs = TrustedCertManager(context)
        certs.trustHash(server.id, hash)
        val controller = ChatController(context)
        val messages = CopyOnWriteArrayList<ChatController.DecryptedMessage>()
        controller.addMessageListener { if (!it.isOwn) messages.add(it) }
        suspend fun awaitMessage(text: String) = withTimeout(90_000) {
            while (messages.none { it.plaintext == text }) delay(100)
        }
        suspend fun connectReady() {
            controller.connect(server, "Audit-$role")
            withTimeout(60_000) {
                while (controller.state.value != ConnectionState.READY) {
                    check(controller.state.value != ConnectionState.ERROR) {
                        "Connection failed: ${controller.lastError.value}"
                    }
                    delay(100)
                }
            }
        }
        try {
            connectReady()
            val groupMessages = args.getString("relayMessages", "10").toInt()
            require(groupMessages in 1..1000)
            val peer = withTimeout(60_000) {
                var peers = controller.knownPeers(server.id)
                while (peers.none { it.first != controller.ownFingerprint() }) {
                    delay(200)
                    peers = controller.knownPeers(server.id)
                }
                peers.first { it.first != controller.ownFingerprint() }.first
            }
            if (role == "sender") {
                repeat(groupMessages) { assertTrue(controller.sendChat("$run-group-$it")) }
                assertTrue(controller.sendDirectMessage("$run-direct", peer))
                awaitMessage("$run-online-ack")
                delay(3_000)
                assertTrue(controller.sendDirectMessage("$run-offline", peer))
                awaitMessage("$run-offline-ack")
            } else {
                repeat(groupMessages) { awaitMessage("$run-group-$it") }
                val deliveredGroup = messages.filter { it.plaintext.startsWith("$run-group-") }
                assertEquals(groupMessages, deliveredGroup.size)
                assertEquals(groupMessages, deliveredGroup.map { it.plaintext }.distinct().size)
                // Initial Sync and live broadcast can interleave on the relay.
                // Check persisted chronological history, the source of chat UI,
                // instead of requiring callbacks to arrive in relay-ID order.
                assertEquals((0 until groupMessages).map { "$run-group-$it" },
                    controller.decryptHistory(server.id)
                        .filter { it.plaintext.startsWith("$run-group-") }.map { it.plaintext })
                awaitMessage("$run-direct")
                assertTrue(messages.first { it.plaintext == "$run-direct" }.conversationId.startsWith("dm:"))
                assertTrue(controller.sendDirectMessage("$run-online-ack", peer))
                controller.disconnect()
                assertTrue(controller.state.value == ConnectionState.DISCONNECTED)
                delay(8_000)
                connectReady()
                awaitMessage("$run-offline")
                assertTrue(controller.sendDirectMessage("$run-offline-ack", peer))
                assertTrue(messages.count { it.plaintext == "$run-offline" } == 1)
            }
            val soakRounds = args.getString("relaySoakRounds", "0").toInt()
            require(soakRounds in 0..720)
            val interval = args.getString("relaySoakIntervalMs", "10000").toLong()
            require(interval in 1000..30000)
            repeat(soakRounds) { round ->
                val request = "$run-soak-$round"
                val response = "$request-ack"
                if (role == "sender") {
                    assertTrue(controller.sendDirectMessage(request, peer))
                    awaitMessage(response)
                    delay(interval)
                } else {
                    awaitMessage(request)
                    assertTrue(messages.count { it.plaintext == request } == 1)
                    assertTrue(controller.sendDirectMessage(response, peer))
                }
                assertTrue("Connection must remain READY during soak", controller.state.value == ConnectionState.READY)
                if (round % 12 == 0) {
                    val progress = android.os.Bundle()
                    progress.putString("stream", "Live relay $role soak progress ${round + 1}/$soakRounds\n")
                    InstrumentationRegistry.getInstrumentation().sendStatus(0, progress)
                }
            }
            assertFalse(controller.decryptHistory(server.id).isEmpty())
        } finally {
            controller.destroy()
            certs.forget(server.id)
        }
    }

    @Test
    fun nonResponsiveEndpointHasFiniteConnectDeadline() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit non-responsive test endpoint required", args.containsKey("relayDeadlineHost"))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val server = ServerConfig("audit-connect-deadline", "Deadline test",
            args.getString("relayDeadlineHost")!!, 59999, "No relay expected", "audit-only")
        val certs = TrustedCertManager(context)
        certs.trustHash(server.id, args.getString("relayHash") ?: error("relayHash required"))
        val controller = ChatController(context)
        controller.setAutoReconnect(false)
        val sessionOpened = AtomicBoolean(false)
        val observer = launch(start = CoroutineStart.UNDISPATCHED) {
            controller.state.collect { state ->
                if (state == ConnectionState.CONNECTED || state == ConnectionState.AUTHENTICATING ||
                    state == ConnectionState.AUTHENTICATED || state == ConnectionState.READY) sessionOpened.set(true)
            }
        }
        val started = android.os.SystemClock.elapsedRealtime()
        try {
            controller.connect(server, "Audit-deadline")
            withTimeout(25_000) {
                while (controller.state.value != ConnectionState.ERROR) delay(50)
            }
            val elapsed = android.os.SystemClock.elapsedRealtime() - started
            assertFalse("No session may open at the non-responsive endpoint", sessionOpened.get())
            assertTrue("Setup must be bounded to the application deadline", elapsed < 25_000)
            val progress = android.os.Bundle()
            progress.putString("stream", "Connect deadline reached ERROR after ${elapsed}ms\n")
            InstrumentationRegistry.getInstrumentation().sendStatus(0, progress)
        } finally {
            observer.cancel()
            controller.destroy()
            certs.forget(server.id)
        }
    }

    @Test
    fun wrongPasswordAndWrongPinFailClosed() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("External relay arguments are required", args.containsKey("relayHost"))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val run = args.getString("relayRun") ?: error("relayRun required")
        val certs = TrustedCertManager(context)
        val server = ServerConfig("audit-negative-$run", "Audit negative",
            args.getString("relayHost")!!, args.getString("relayPort", "4433").toInt(),
            "Negative live checks", "deliberately-wrong-audit-password")
        suspend fun rejects(hash: String, password: String, authRejection: Boolean) {
            require(hash.matches(Regex("[0-9a-fA-F]{64}")))
            certs.forget(server.id)
            certs.trustHash(server.id, hash)
            val controller = ChatController(context)
            val sessionOpened = AtomicBoolean(false)
            val observer = launch(start = CoroutineStart.UNDISPATCHED) {
                controller.state.collect { state ->
                    if (state == ConnectionState.CONNECTED || state == ConnectionState.AUTHENTICATING ||
                        state == ConnectionState.AUTHENTICATED || state == ConnectionState.READY) {
                        sessionOpened.set(true)
                    }
                }
            }
            try {
                controller.connect(server.copy(password = password), "Audit-negative")
                withTimeout(60_000) {
                    while (controller.state.value != ConnectionState.ERROR) {
                        assertFalse(controller.state.value == ConnectionState.READY)
                        delay(100)
                    }
                }
                if (authRejection) {
                    withTimeout(3_000) {
                        while (controller.lastError.value?.startsWith("Аутентификация отклонена сервером") != true) delay(50)
                    }
                    assertTrue("Wrong password must reach the pinned session and explicit auth rejection", sessionOpened.get())
                } else {
                    assertFalse("Wrong pin must fail before opening a WebTransport session", sessionOpened.get())
                }
            } finally { observer.cancel(); controller.destroy() }
        }
        try {
            val validHash = args.getString("relayHash") ?: error("relayHash required")
            val validPassword = args.getString("relayPassword") ?: error("relayPassword required")
            // Prove the same endpoint is reachable with valid credentials.
            certs.trustHash(server.id, validHash)
            val control = ChatController(context)
            try {
                control.connect(server.copy(password = validPassword), "Audit-control")
                withTimeout(60_000) {
                    while (control.state.value != ConnectionState.READY) {
                        check(control.state.value != ConnectionState.ERROR) { "Positive control failed" }
                        delay(100)
                    }
                }
            } finally { control.destroy() }
            rejects(validHash, server.password, authRejection = true)
            // A correct password ensures a broken pin check cannot pass merely
            // because the subsequent authentication would have failed.
            rejects("00".repeat(32), validPassword, authRejection = false)
        } finally { certs.forget(server.id) }
    }
}
