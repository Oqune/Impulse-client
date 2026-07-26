# BouncyCastle PQC — only keep the PQC provider and algorithm classes used
# (ML-KEM-768 via Kyber, ML-DSA-65 via Dilithium)
-keep class org.bouncycastle.pqc.jcajce.provider.BouncyCastlePQCProvider { *; }
-keep class org.bouncycastle.pqc.jcajce.spec.KyberParameterSpec { *; }
-keep class org.bouncycastle.pqc.jcajce.spec.DilithiumParameterSpec { *; }
-keep class org.bouncycastle.pqc.jcajce.SecretKeyWithEncapsulation { *; }
-keep class org.bouncycastle.pqc.jcajce.spec.KEMGenerateSpec { *; }
-keep class org.bouncycastle.pqc.jcajce.spec.KEMExtractSpec { *; }
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
-keep class com.google.mlkit.vision.barcode.** { *; }
-dontwarn com.google.mlkit.vision.**

# Stack traces
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
