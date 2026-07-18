package com.example.impulse.transport

/**
 * Lifecycle of a WebTransport connection, surfaced to the UI.
 *
 * The state machine is strictly ordered so the UI and the controller can reason
 * about what is safe to do at any moment:
 *
 *   DISCONNECTED ──connect()──▶ CONNECTING
 *   CONNECTING   ──session ready──▶ CONNECTED
 *   CONNECTED    ──auth sent──▶ AUTHENTICATING
 *   AUTHENTICATING ──auth ok + group secret──▶ AUTHENTICATED
 *   AUTHENTICATED ──keys exchanged, ready to chat──▶ READY
 *   (any) ──failure──▶ ERROR ──retry──▶ CONNECTING
 *
 * Sending chat messages is ONLY permitted in [READY]. This prevents the
 * "false connected" bug where a message was dispatched before the secure
 * channel was fully established.
 */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,     // WebTransport session being established
    CONNECTED,      // transport handshake done, not yet authenticated
    AUTHENTICATING, // auth frame sent, awaiting server result
    AUTHENTICATED,  // server auth + group secret established (keys exchanged)
    READY,          // fully ready: chat messages may be sent
    ERROR
}
