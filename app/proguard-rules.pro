# SummaryRecorder ProGuard Rules

# Keep application classes used by reflection
-keepattributes *Annotation*
-keep class com.kohei.summaryrecorder.data.db.** { *; }
-keep class com.kohei.summaryrecorder.data.api.** { *; }

# Hilt
-dontwarn dagger.hilt.**
-keep class dagger.hilt.** { *; }

# Retrofit
-keepattributes Signature, Exceptions
-keepclassmembers,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Gson
-keepattributes Signature
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# Room
-keep class * extends androidx.room.RoomDatabase { *; }
-dontwarn androidx.room.paging.**

# Gemini SDK
-keep class com.google.ai.** { *; }
-dontwarn com.google.ai.**

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# WorkManager
-keep class androidx.work.** { *; }
-dontwarn androidx.work.**

# Embed API keys securely (BuildConfig obfuscation via string encryption)
# Note: For maximum security, use Android Keystore or NDK instead.
-assumesideeffects class com.kohei.summaryrecorder.BuildConfig {
    *** get*(***);
    *** *(***);
}
