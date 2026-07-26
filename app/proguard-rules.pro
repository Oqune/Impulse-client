# BouncyCastle PQC (ML-KEM, ML-DSA) — reflection-based provider registration
# Only keep the PQC provider classes actually used at runtime
-keep class org.bouncycastle.pqc.jcajce.provider.** { *; }
-keep class org.bouncycastle.pqc.jcajce.spec.** { *; }
-keep class org.bouncycastle.pqc.crypto.** { *; }
-keep class org.bouncycastle.jcajce.provider.** { *; }
-dontwarn org.bouncycastle.**

# Room DAOs and entities
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao interface *
-keepclassmembers class * {
    @androidx.room.* <methods>;
}

# Compose — ships its own consumer ProGuard rules; only suppress warnings
-dontwarn androidx.compose.**

# Timber custom trees
-keep class * extends timber.log.Timber.Tree { *; }

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# Socket HTTP3 / QUIC — ships its own consumer rules; suppress warnings only
-dontwarn com.ditchoom.**

# ML Kit — reflection-based barcode scanning initialization
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# Stack traces
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
