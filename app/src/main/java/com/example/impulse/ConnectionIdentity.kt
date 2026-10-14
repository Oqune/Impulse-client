package com.example.impulse

/** Identifies one transport incarnation for callback and outbox routing. */
internal data class ConnectionIdentity(val generation: Long, val serverId: String)

internal fun matchesConnectionIdentity(
    expected: ConnectionIdentity,
    current: ConnectionIdentity,
    expectedTransport: Any?,
    currentTransport: Any?
): Boolean = expected == current && expectedTransport != null && expectedTransport === currentTransport
