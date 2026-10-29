# Keep kotlinx.serialization generated serializers for API DTOs.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.algopraxis.dhanniti.** {
    *** Companion;
}
-keepclasseswithmembers class com.algopraxis.dhanniti.** {
    kotlinx.serialization.KSerializer serializer(...);
}
