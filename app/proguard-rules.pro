-optimizationpasses 5
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-verbose
-keepattributes *Annotation*,InnerClasses,Signature,EnclosingMethod,SourceFile,LineNumberTable

# Keep all application models, JNI bridges, and reflection targets
-keep class com.tvflux.pro.** { *; }

# Keep WireGuard tunnel & native GoBackend classes
-keep class com.wireguard.** { *; }
-dontwarn com.wireguard.**

# Keep Media3 / ExoPlayer classes & extractors
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Keep OkHttp & Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Keep LibVLC JNI & reflection classes in release builds
-keep class org.videolan.** { *; }
-dontwarn org.videolan.**
