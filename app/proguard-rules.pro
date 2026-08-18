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

# sherpa-onnx (Supertonic TTS): its native JNI layer looks up Kotlin class
# fields/methods by name (GetFieldID/GetMethodID-style calls), so a renamed or
# stripped member breaks silently at runtime instead of failing to compile.
# The AAR may ship its own consumer-rules.pro — this is a defensive duplicate,
# not a substitute, since that could not be verified without the real AAR
# (see app/libs/README.md).
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclassmembers class com.k2fsa.sherpa.onnx.** { *; }

# Never keep any hard-coded secret in the release mapping — secrets live only in
# the Android Keystore-backed store, never in code (docs/SECURITY.md §21).
