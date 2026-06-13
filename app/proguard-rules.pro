# OpenWeigh R8 / ProGuard rules.
#
# Hilt, Room, Kotlin coroutines and AndroidX ship their own consumer rules, so most of the app
# shrinks safely. The rules below cover the reflection-driven Google API client (play flavor only)
# and the model classes that cross (de)serialization boundaries.

# --- Keep generic signatures & annotations needed for reflection-based (de)serialization ---------
-keepattributes Signature, *Annotation*, RuntimeVisibleAnnotations, AnnotationDefault, EnclosingMethod, InnerClasses

# --- Google API / HTTP client (play flavor) ------------------------------------------------------
# The Google HTTP client maps JSON onto model classes via reflection on @Key-annotated fields, and
# the Drive service model classes must not be stripped/renamed. These rules are inert in the foss
# flavor (the classes simply aren't on the classpath).
-keepclassmembers class * {
  @com.google.api.client.util.Key <fields>;
}
-keep class com.google.api.client.** { *; }
-keep class com.google.api.services.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.api.client.**
-dontwarn com.google.api.services.**
-dontwarn com.google.common.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn org.apache.http.**
-dontwarn javax.naming.**
-dontwarn java.beans.**

# --- App model classes that are serialized to JSON/CSV snapshots ---------------------------------
# Keep field names stable so Drive/local backup snapshots round-trip across obfuscated builds.
-keep class io.github.openweigh.ble.model.** { *; }
-keep class io.github.openweigh.data.repo.Measurement { *; }
-keep class io.github.openweigh.data.db.** { *; }

# --- Kotlin metadata -----------------------------------------------------------------------------
-keep class kotlin.Metadata { *; }
-dontwarn kotlinx.**
