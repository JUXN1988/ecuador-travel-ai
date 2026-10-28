# Travesías en Ecuador — reglas R8/ProGuard (solo aplican si activas isMinifyEnabled = true)

# osmdroid
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

# Firebase / Firestore (modelos leídos por reflexión)
-keepattributes Signature, *Annotation*
-keep class ec.travesias.app.data.social.** { *; }

# BuildConfig (Secrets se lee por reflexión)
-keep class ec.travesias.app.BuildConfig { *; }

# Room / Kotlin
-keep class ec.travesias.app.data.local.** { *; }
-dontwarn kotlinx.coroutines.**

# OkHttp / Moshi
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn com.squareup.moshi.**
