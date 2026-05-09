# --- Retrofit (https://github.com/square/retrofit/blob/master/retrofit/src/main/resources/META-INF/proguard/retrofit2.pro)
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn javax.annotation.**
-dontwarn kotlin.Unit
-dontwarn retrofit2.KotlinExtensions
-dontwarn retrofit2.KotlinExtensions$*

# --- OkHttp (platform classes unused on Android)
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- Gson: keep reflective models & generic signatures used by GsonConverterFactory
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class io.github.ntufar.stasi.data.** { *; }

# --- MapLibre GL / JNI (avoid stripping native-facing APIs)
-keep class org.maplibre.** { *; }
-keepclassmembers class org.maplibre.** { *; }
-dontwarn org.slf4j.**
-dontwarn org.commonmark.**

# --- Room (embedded rules usually sufficient; extra safety for reflection)
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**
