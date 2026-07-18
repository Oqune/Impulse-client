package android.net.http;

/**
 * Compile-time stub for {@code android.net.http.WebTransportCallback} (API 33+).
 * Real implementation is provided by the Android framework at runtime.
 */
public abstract class WebTransportCallback {
    public void onSessionReady(WebTransportSession session) { }
    public void onSessionError(int error) { }
    public void onSessionClosed(int info) { }
}
