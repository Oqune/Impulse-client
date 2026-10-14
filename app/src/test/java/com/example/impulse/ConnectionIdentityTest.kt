package com.example.impulse

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionIdentityTest {
    @Test
    fun acceptsOnlySameGenerationServerAndTransport() {
        val oldTransport = Any()
        val currentTransport = Any()
        val serverA = ConnectionIdentity(generation = 4, serverId = "server-a")
        val serverB = ConnectionIdentity(generation = 5, serverId = "server-b")

        assertTrue(matchesConnectionIdentity(serverA, serverA, oldTransport, oldTransport))
        assertFalse(matchesConnectionIdentity(serverA, serverB, oldTransport, currentTransport))
        assertFalse(matchesConnectionIdentity(serverA, serverA.copy(generation = 5), oldTransport, oldTransport))
        assertFalse(matchesConnectionIdentity(serverA, serverA, oldTransport, currentTransport))
        assertFalse(matchesConnectionIdentity(serverA, serverA, null, null))
    }
}
