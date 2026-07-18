package android.net.http;

/**
 * Compile-time stub for {@code android.net.http.WebTransport} (API 33+).
 * Real implementation is provided by the Android framework at runtime.
 */
public class WebTransport {
    public static class Builder {
        public Builder(String url) { }
        public Builder setServerCertificateHashes(WebTransportServerCertificateHashes hashes) { return this; }
        public WebTransport build() { return new WebTransport(); }
    }

    public void createSession(WebTransportCallback callback) { }
}
