-keep class com.google.** { *; }
-keep class androidx.** { *; }
-keep class com.anvit.localai.** { *; }
-dontwarn com.google.**
-dontwarn androidx.**

# iText7 — reflection-heavy, breaks in release builds without these
-keep class com.itextpdf.** { *; }
-keepclassmembers class com.itextpdf.** { *; }
-dontwarn com.itextpdf.**

# iText7 transitive deps
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-keep class org.slf4j.** { *; }
-dontwarn org.slf4j.**
-dontwarn javax.xml.**
-dontwarn org.w3c.dom.**
