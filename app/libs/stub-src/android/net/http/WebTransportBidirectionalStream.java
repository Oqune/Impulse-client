package android.net.http;

import java.nio.ByteBuffer;

/**
 * Compile-time stub for {@code android.net.http.WebTransportBidirectionalStream} (API 33+).
 * Real implementation is provided by the Android framework at runtime.
 */
public class WebTransportBidirectionalStream {
    public static class Callback {
        public void onStreamReady(WebTransportBidirectionalStream stream) { }
        public void onStreamFailed(int errorCode) { }
    }

    public void write(ByteBuffer byteBuffer) { }
    public int read(ByteBuffer byteBuffer) { return -1; }
    public void close() { }
}
