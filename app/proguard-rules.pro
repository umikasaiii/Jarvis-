# JARVIS Mobile ProGuard/R8 rules.

# kotlinx.serialization: keep @Serializable metadata and generated serializers.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keep,includedescriptorclasses class com.simone.jarvismobile.**$$serializer { *; }
-keepclassmembers class com.simone.jarvismobile.** {
    *** Companion;
}
-keepclasseswithmembers class com.simone.jarvismobile.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Never keep any hard-coded secret in the release mapping — secrets live only in
# the Android Keystore-backed store, never in code (docs/SECURITY.md §21).
