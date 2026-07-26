# BouncyCastle PQC (ML-KEM, ML-DSA) — reflection-based provider registration
-keep class org.bouncycastle.pqc.** { *; }
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.crypto.engines.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Room DAOs and entities
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao interface *
-keepclassmembers class * {
    @androidx.room.* <methods>;
}

# Compose
-dontwarn androidx.compose.**
-keep class androidx.compose.** { *; }

# Timber custom trees
-keep class * extends timber.log.Timber.Tree { *; }

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# Socket HTTP3 / QUIC
-dontwarn com.ditchoom.**
-keep class com.ditchoom.** { *; }

# Stack traces
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
