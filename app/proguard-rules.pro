# kotlinx.serialization keeps generated serializers via @Serializable; the
# default Android rules plus the plugin handle most of this. Keep the DTO
# serializers explicitly to be safe under R8 in release builds.
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.warden.android.data.model.**$$serializer { *; }
-keepclassmembers class com.warden.android.data.model.** {
    *** Companion;
}
