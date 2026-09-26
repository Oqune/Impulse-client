package com.example.impulse.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ServerEndpointTest {

    @Test
    fun parseServerEndpoint_ipv4() {
        val (host1, port1) = parseServerEndpoint("192.168.1.1")
        assertEquals("192.168.1.1", host1)
        assertEquals(4433, port1)

        val (host2, port2) = parseServerEndpoint("192.168.1.1:8080")
        assertEquals("192.168.1.1", host2)
        assertEquals(8080, port2)
    }

    @Test
    fun parseServerEndpoint_ipv6Bracketed() {
        val (host1, port1) = parseServerEndpoint("[2001:db8::1]")
        assertEquals("2001:db8::1", host1)
        assertEquals(4433, port1)

        val (host2, port2) = parseServerEndpoint("[2001:db8::1]:4433")
        assertEquals("2001:db8::1", host2)
        assertEquals(4433, port2)

        val (host3, port3) = parseServerEndpoint("[::1]:9000")
        assertEquals("::1", host3)
        assertEquals(9000, port3)
    }

    @Test
    fun parseServerEndpoint_ipv6LinkLocalScoped() {
        val (host1, port1) = parseServerEndpoint("[fe80::1%wlan0]:4433")
        assertEquals("fe80::1%wlan0", host1)
        assertEquals(4433, port1)

        val (host2, port2) = parseServerEndpoint("[fe80::1%eth0]")
        assertEquals("fe80::1%eth0", host2)
        assertEquals(4433, port2)

        val (host3, port3) = parseServerEndpoint("fe80::1%wlan0")
        assertEquals("fe80::1%wlan0", host3)
        assertEquals(4433, port3)
    }

    @Test
    fun parseServerEndpoint_ipv6BareLiteral() {
        val (host1, port1) = parseServerEndpoint("2001:db8::1")
        assertEquals("2001:db8::1", host1)
        assertEquals(4433, port1)

        val (host2, port2) = parseServerEndpoint("::1")
        assertEquals("::1", host2)
        assertEquals(4433, port2)
    }

    @Test
    fun parseServerEndpoint_hostnameAndMdns() {
        val (host1, port1) = parseServerEndpoint("impulse.local")
        assertEquals("impulse.local", host1)
        assertEquals(4433, port1)

        val (host2, port2) = parseServerEndpoint("impulse.local:4433")
        assertEquals("impulse.local", host2)
        assertEquals(4433, port2)

        val (host3, port3) = parseServerEndpoint("relay.example.com:8443")
        assertEquals("relay.example.com", host3)
        assertEquals(8443, port3)

        val (host4, port4) = parseServerEndpoint("localhost")
        assertEquals("localhost", host4)
        assertEquals(4433, port4)
    }

    @Test
    fun parseServerEndpoint_invalidFormatsThrow() {
        val invalidInputs = listOf(
            "",
            "   ",
            "[2001:db8::1",
            "192.168.1.1:70000",
            "192.168.1.1:0",
            "192.168.1.1:abc",
            "[2001:db8::1]:99999",
            "[2001:db8::1]:xyz",
        )

        for (input in invalidInputs) {
            try {
                parseServerEndpoint(input)
                fail("Expected parseServerEndpoint to throw for: '$input'")
            } catch (e: IllegalArgumentException) {
                // Expected
            }
        }
    }

    @Test
    fun isValidHost_ipv4() {
        assertTrue(isValidHost("192.168.1.1"))
        assertTrue(isValidHost("10.0.0.1"))
        assertTrue(isValidHost("0.0.0.0"))
        assertTrue(isValidHost("127.0.0.1"))
        assertTrue(isValidHost("255.255.255.255"))

        assertFalse(isValidHost("192.168.1.256"))
        assertFalse(isValidHost("192.168.1"))
        assertFalse(isValidHost("192.168.1.1.1"))
        assertFalse(isValidHost("01.02.03.04"))
        assertFalse(isValidHost("192.168.1.300"))
    }

    @Test
    fun isValidHost_ipv6() {
        assertTrue(isValidHost("::1"))
        assertTrue(isValidHost("[::1]"))
        assertTrue(isValidHost("2001:db8::1"))
        assertTrue(isValidHost("[2001:db8::1]"))
        assertTrue(isValidHost("fe80::1%wlan0"))
        assertTrue(isValidHost("[fe80::1%wlan0]"))
        assertTrue(isValidHost("::"))
        assertTrue(isValidHost("[::]"))

        assertFalse(isValidHost(":::"))
        assertFalse(isValidHost("2001:db8:::1"))
        assertFalse(isValidHost("not_an_ip_or_domain!"))
    }

    @Test
    fun isValidHost_hostname() {
        assertTrue(isValidHost("impulse.local"))
        assertTrue(isValidHost("relay.example.com"))
        assertTrue(isValidHost("localhost"))
        assertTrue(isValidHost("node-1.lan"))

        assertFalse(isValidHost("-invalid"))
        assertFalse(isValidHost("invalid-"))
        assertFalse(isValidHost("with space"))
        assertFalse(isValidHost(""))
    }

    @Test
    fun isValidServerEndpoint_validation() {
        assertTrue(isValidServerEndpoint("192.168.1.1:4433"))
        assertTrue(isValidServerEndpoint("192.168.1.1"))
        assertTrue(isValidServerEndpoint("[2001:db8::1]:4433"))
        assertTrue(isValidServerEndpoint("[fe80::1%wlan0]:4433"))
        assertTrue(isValidServerEndpoint("impulse.local:4433"))
        assertTrue(isValidServerEndpoint("impulse.local"))

        assertFalse(isValidServerEndpoint("192.168.1.1:90000"))
        assertFalse(isValidServerEndpoint("[2001:db8::1"))
        assertFalse(isValidServerEndpoint("192.168.1.300:4433"))
        assertFalse(isValidServerEndpoint(""))
    }

    @Test
    fun serverConfig_urlGenerationAndDisplay() {
        // IPv4
        val s1 = ServerConfig(name = "v4", ipAddress = "192.168.1.1", port = 4433, description = "")
        assertEquals("https://192.168.1.1:4433", s1.getWebTransportUrl())
        assertEquals("192.168.1.1:4433", s1.getDisplayAddress())

        // Bare IPv6
        val s2 = ServerConfig(name = "v6_bare", ipAddress = "2001:db8::1", port = 4433, description = "")
        assertEquals("https://[2001:db8::1]:4433", s2.getWebTransportUrl())
        assertEquals("[2001:db8::1]:4433", s2.getDisplayAddress())

        // Bracketed IPv6
        val s3 = ServerConfig(name = "v6_bracket", ipAddress = "[2001:db8::1]", port = 4433, description = "")
        assertEquals("https://[2001:db8::1]:4433", s3.getWebTransportUrl())
        assertEquals("[2001:db8::1]:4433", s3.getDisplayAddress())

        // Link-Local with Zone ID (RFC 6874 percent-encoded in URL, plain in display)
        val s4 = ServerConfig(name = "v6_link_local", ipAddress = "fe80::1%wlan0", port = 4433, description = "")
        assertEquals("https://[fe80::1%25wlan0]:4433", s4.getWebTransportUrl())
        assertEquals("[fe80::1%wlan0]:4433", s4.getDisplayAddress())

        // Hostname / mDNS
        val s5 = ServerConfig(name = "mdns", ipAddress = "impulse.local", port = 4433, description = "")
        assertEquals("https://impulse.local:4433", s5.getWebTransportUrl())
        assertEquals("impulse.local:4433", s5.getDisplayAddress())
    }
}
