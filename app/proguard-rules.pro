# Media3 / ExoPlayer
-dontwarn androidx.media3.**
-keep class androidx.media3.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# JNA
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { *; }
-dontwarn com.sun.jna.**

# hotaudio native bindings
-keep class net.hotaudio.extractor.** { *; }
-keepclassmembers class net.hotaudio.extractor.** { *; }

# rustls-platform-verifier
-keep, includedescriptorclasses class org.rustls.platformverifier.** { *; }

# Keep line numbers for crash reports without exposing the full mapping in logs.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
