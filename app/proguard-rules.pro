# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# OkHttp platform-specific implementations
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Keep Groq API client and exception classes
-keep class com.groqandroid.GroqApiClient { *; }
-keep class com.groqandroid.TranscriptionException { *; }
-keep class com.groqandroid.AuthenticationException { *; }
-keep class com.groqandroid.RetryableException { *; }

# AndroidX Security / EncryptedSharedPreferences
-keep class androidx.security.crypto.** { *; }
-dontwarn com.google.crypto.tink.**
-keep class com.google.crypto.tink.** { *; }

# Kotlin coroutines
-dontwarn kotlinx.coroutines.**
-keep class kotlinx.coroutines.** { *; }

# Keep accessibility service metadata
-keep class com.groqandroid.TranscriptionOverlayService { *; }
