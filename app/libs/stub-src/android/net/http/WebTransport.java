package android.net.http;

import java.util.concurrent.Executor;

/**
 * Compile-time stub for {@code android.net.http.WebTransport} (API 34+).
 *
 * Real implementation is provided by the Android framework at runtime. NOTE: the
 * real {@code Builder} constructor is {@code Builder(String uri, Executor
 * executor, HttpEngine engine)} — the stub MUST declare exactly that signature,
 * otherwise the compiled call site references a {@code <init>} that does not
 * exist on the device's framework class and throws NoSuchMethodError at runtime
 * (the same trap that previously broke {@code HttpEngine.Builder}).
 */
public class WebTransport {
    public static class Builder {
        public Builder(String url, Executor executor, HttpEngine engine) { }
        public Builder setServerCertificateHashes(WebTransportServerCertificateHashes hashes) { return this; }
        public WebTransport build() { return new WebTransport(); }
    }

    public void createSession(WebTransportCallback callback) { }

    public interface BidirectionalStream {
        abstract class Callback {
            public void onStreamReady(WebTransportBidirectionalStream stream) { }
            public void onStreamFailed(int errorCode) { }
        }
    }
}
