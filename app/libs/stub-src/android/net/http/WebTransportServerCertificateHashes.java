package android.net.http;

/**
 * Compile-time stub for {@code android.net.http.WebTransportServerCertificateHashes} (API 33+).
 * Real implementation is provided by the Android framework at runtime.
 */
public final class WebTransportServerCertificateHashes {
    public static class Builder {
        public Builder() { }
        public Builder addSha256Hash(byte[] hash) { return this; }
        public WebTransportServerCertificateHashes build() {
            return new WebTransportServerCertificateHashes();
        }
    }
}
