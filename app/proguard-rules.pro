# Project specific ProGuard rules can be defined here.

-keep class io.noties.markwon.** { *; }
-keep class ru.noties.jlatexmath.** { *; }
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text_common.** { *; }
-dontwarn ru.noties.jlatexmath.**
-dontwarn org.commonmark.**
-dontwarn okhttp3.**
-dontwarn okio.**
