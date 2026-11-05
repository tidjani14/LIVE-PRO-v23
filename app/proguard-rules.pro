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

# Keep Media3 / ExoPlayer classes, extractors, FFmpeg JNI decoder & RTMP extension
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**
-keep class androidx.media3.decoder.ffmpeg.** { *; }
-dontwarn androidx.media3.decoder.ffmpeg.**
-keep class androidx.media3.datasource.rtmp.** { *; }
-dontwarn androidx.media3.datasource.rtmp.**
-keep class io.antmedia.rtmp_client.** { *; }
-dontwarn io.antmedia.rtmp_client.**

# Keep OkHttp & Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
