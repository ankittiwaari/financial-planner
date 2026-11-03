# Keep kotlinx.serialization generated serializers for API DTOs.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.algopraxis.dhanniti.** {
    *** Companion;
}
-keepclasseswithmembers class com.algopraxis.dhanniti.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Navigation Compose looks up enum route arguments (e.g. TransactionEditorRoute.direction) by class name with
# Class.forName, so R8 must not rename enum classes. Their members can still be shrunk.
-keepnames enum com.algopraxis.dhanniti.** { *; }
