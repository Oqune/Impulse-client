package com.example.impulse.data

import java.util.UUID

data class ServerConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val ipAddress: String,
    val port: Int = 4433,
    val description: String,
    val password: String = ""
) {
    /**
     * WebTransport endpoint URL (HTTPS/QUIC / HTTP/3).
     *
     * Adheres to RFC 3986 and RFC 6874:
     * - IPv4: https://192.168.1.1:4433
     * - Hostname / mDNS: https://impulse.local:4433
     * - IPv6: https://[2001:db8::1]:4433
     * - IPv6 Link-Local with Zone ID: https://[fe80::1%25wlan0]:4433 (% is percent-encoded)
     */
    fun getWebTransportUrl(): String {
        val (cleanHost, _) = try {
            parseServerEndpoint(ipAddress, port)
        } catch (_: Exception) {
            Pair(ipAddress, port)
        }
        val formattedHost = if (cleanHost.contains(":") && !cleanHost.startsWith("[")) {
            val encoded = cleanHost.replace("%", "%25")
            "[$encoded]"
        } else if (cleanHost.startsWith("[") && cleanHost.endsWith("]")) {
            val inner = cleanHost.substring(1, cleanHost.length - 1).replace("%", "%25")
            "[$inner]"
        } else {
            cleanHost
        }
        return "https://$formattedHost:$port"
    }

    /**
     * Display-friendly address for UI labels, logs, and dialogs.
     * Preserves raw human-readable Zone ID (e.g. `[fe80::1%wlan0]:4433`).
     */
    fun getDisplayAddress(): String {
        val (cleanHost, _) = try {
            parseServerEndpoint(ipAddress, port)
        } catch (_: Exception) {
            Pair(ipAddress, port)
        }
        val formattedHost = if (cleanHost.contains(":") && !cleanHost.startsWith("[")) {
            "[$cleanHost]"
        } else {
            cleanHost
        }
        return "$formattedHost:$port"
    }

    companion object {
        val production = ServerConfig(
            id = "prod_001",
            name = "Main Relay",
            ipAddress = "88.83.201.154",
            port = 4433,
            description = "Основной сервер Impulse",
            password = ""
        )

        val defaultServer = production
        val builtInServers = listOf(production)
    }
}

/**
 * Parse an endpoint string into a clean host and integer port according to RFC 3986.
 *
 * Accepted formats:
 * - IPv4: `192.168.1.100` or `192.168.1.100:4433`
 * - IPv6 bracketed: `[2001:db8::1]` or `[2001:db8::1]:4433`
 * - IPv6 Link-Local: `[fe80::1%wlan0]:4433` or `[fe80::1%wlan0]`
 * - IPv6 bare literal: `2001:db8::1` or `fe80::1%wlan0`
 * - Hostname / mDNS: `impulse.local` or `impulse.local:4433`
 */
fun parseServerEndpoint(raw: String, defaultPort: Int = 4433): Pair<String, Int> {
    val trimmed = raw.trim()
    require(trimmed.isNotEmpty()) { "Адрес сервера не может быть пустым" }
    return if (trimmed.startsWith("[")) {
        val closingBracket = trimmed.indexOf(']')
        require(closingBracket > 0) { "Некорректный формат IPv6 адреса: отсутствует закрывающая скобка" }
        val host = trimmed.substring(1, closingBracket)
        val portPart = trimmed.substring(closingBracket + 1)
        val port = if (portPart.startsWith(":")) {
            val p = portPart.substring(1).toIntOrNull()
            require(p != null && p in 1..65535) { "Некорректный порт: ${portPart.substring(1)}" }
            p
        } else {
            defaultPort
        }
        Pair(host, port)
    } else if (trimmed.count { it == ':' } > 1) {
        // Bare unbracketed IPv6 literal (e.g. 2001:db8::1, ::1, fe80::1%wlan0)
        Pair(trimmed, defaultPort)
    } else if (trimmed.contains(':')) {
        // IPv4 or hostname with port (192.168.1.1:4433 or impulse.local:4433)
        val parts = trimmed.split(':')
        val p = parts[1].toIntOrNull()
        require(p != null && p in 1..65535) { "Некорректный порт: ${parts[1]}" }
        Pair(parts[0], p)
    } else {
        // IPv4 or hostname without port
        Pair(trimmed, defaultPort)
    }
}

/** Validate whether a string is a syntactically valid IPv4 address. */
fun isValidIpv4(host: String): Boolean {
    val parts = host.split('.')
    if (parts.size != 4) return false
    return parts.all { part ->
        val num = part.toIntOrNull() ?: return false
        num in 0..255 && (part == "0" || !part.startsWith("0"))
    }
}

/** Validate whether a string is a syntactically valid IPv6 address (with optional %scope). */
fun isValidIpv6(host: String): Boolean {
    val withoutScope = host.substringBefore('%')
    val colons = withoutScope.count { it == ':' }
    if (colons < 2 || colons > 7) return false
    val hasDoubleColon = withoutScope.contains("::")
    if (hasDoubleColon && withoutScope.indexOf("::") != withoutScope.lastIndexOf("::")) {
        return false // Only one "::" allowed
    }
    val parts = withoutScope.split(':')
    for (part in parts) {
        if (part.isEmpty()) continue
        if (part.length > 4) return false
        if (part.any { c -> c !in '0'..'9' && c !in 'a'..'f' && c !in 'A'..'F' }) return false
    }
    return true
}

/** Validate whether a string is a syntactically valid FQDN / mDNS / local hostname. */
fun isValidHostname(host: String): Boolean {
    if (host.isEmpty() || host.length > 253) return false
    val labels = host.split('.')
    // Reject all-numeric labels to prevent invalid IPv4s (e.g. 192.168.1.300) masquerading as hostnames
    if (labels.all { label -> label.all { c -> c.isDigit() } }) {
        return false
    }
    return labels.all { label ->
        label.isNotEmpty() && label.length <= 63 &&
            !label.startsWith('-') && !label.endsWith('-') &&
            label.all { c -> c.isLetterOrDigit() || c == '-' }
    }
}

/** Validate whether a host (clean or bracketed) is a valid IPv4, IPv6, or hostname. */
fun isValidHost(host: String): Boolean {
    val h = host.trim()
    if (h.isEmpty()) return false
    val clean = if (h.startsWith("[") && h.endsWith("]")) {
        h.substring(1, h.length - 1)
    } else {
        h
    }
    return isValidIpv4(clean) || isValidIpv6(clean) || isValidHostname(clean)
}

/** Validate a complete server endpoint string (host + optional port). */
fun isValidServerEndpoint(raw: String): Boolean {
    return try {
        val (host, port) = parseServerEndpoint(raw)
        isValidHost(host) && port in 1..65535
    } catch (_: Exception) {
        false
    }
}
