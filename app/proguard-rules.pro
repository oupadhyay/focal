# Keep Rust JNI bridge class and native entrypoints intact under R8
-keep class app.focal.adblock.RustAdblockBridge { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# Keep kotlinx.serialization wire models & generated serializers for E2EE Sync Relay
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class app.focal.sync.**$$serializer { *; }
-keepclassmembers class app.focal.sync.** {
    *** Companion;
}
-keepclasseswithmembers class app.focal.sync.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep Room generated database implementation
-keep class * extends androidx.room.RoomDatabase
-keep class app.focal.database.FocalDatabase_Impl { *; }

# Suppress benign warnings from optional OkHttp / Play Services / Coroutines references
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn com.google.errorprone.annotations.**
