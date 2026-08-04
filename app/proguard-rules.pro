# BouncyCastle PQC — R8 strips provider algorithm classes (registered via SPI
# strings, not direct references), which made release builds throw
# "NoSuchAlgorithmException: no such algorithm: Kyber for provider BCPQC".
# Keep the whole PQC provider tree (incl. the Kyber/Dilithium service impls).
-keep class org.bouncycastle.pqc.jcajce.provider.** { *; }
-keep class org.bouncycastle.pqc.jcajce.spec.** { *; }
-keep class org.bouncycastle.pqc.jcajce.** { *; }
-keep class org.bouncycastle.pqc.math.** { *; }
-keep class org.bouncycastle.pqc.asn1.** { *; }
-keep class org.bouncycastle.jcajce.spec.** { *; }
-keep class org.bouncycastle.jcajce.SecretKeyWithEncapsulation { *; }
-keep class org.bouncycastle.jcajce.spec.KEMGenerateSpec { *; }
-keep class org.bouncycastle.jcajce.spec.KEMExtractSpec { *; }
-keepclassmembers class org.bouncycastle.jcajce.provider.** { *; }
-dontwarn org.bouncycastle.pqc.**
-dontwarn org.bouncycastle.jcajce.**

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
-dontwarn com.ditchoom.socket.**
-dontwarn com.ditchoom.buffer.**

# ML Kit — reflection-based barcode scanning initialization
-keep class com.google.mlkit.vision.barcode.** { *; }
-dontwarn com.google.mlkit.vision.barcode.internal.**

# Stack traces
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
