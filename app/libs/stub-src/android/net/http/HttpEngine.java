package android.net.http;

/**
 * Compile-time stub for {@code android.net.http.HttpEngine} (API 33+).
 *
 * This is NOT packaged into the APK — the real implementation is provided by the
 * Android framework at runtime on API 33+ devices. It exists only so the project
 * compiles in build environments whose platform stubs omit these symbols.
 *
 * NOTE: the real {@code HttpEngine.Builder} constructor accepts an
 * {@code android.content.Context}; the parameter type is intentionally omitted
 * here to keep the stub self-contained (no Android framework bootclasspath).
 */
public class HttpEngine {
    public static class Builder {
        public Builder(Object context) { }
        public HttpEngine build() { return new HttpEngine(); }
    }
}
