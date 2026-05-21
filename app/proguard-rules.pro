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

# Log4j 2
-dontwarn org.apache.logging.log4j.**

# Apache POI
-dontwarn org.apache.poi.**
-dontwarn java.awt.**
-dontwarn javax.xml.stream.**
-dontwarn com.sun.msv.**
-dontwarn org.relaxng.datatype.**
-dontwarn net.sf.saxon.**

# PDFBox Android
-dontwarn com.tom_roush.pdfbox.**
-dontwarn com.gemalto.jp2.**

# RapidOCR is kept compile-only for the experimental OCR parser. The app does
# not register that parser while testing the normal hierarchical chunker path.
-dontwarn io.github.hzkitty.**

# Annotations & OSGi
-dontwarn aQute.bnd.annotation.**
-dontwarn edu.umd.cs.findbugs.annotations.**
-dontwarn org.osgi.framework.**
-dontwarn org.checkerframework.**
-dontwarn javax.annotation.**
