# Keep line numbers for readable crash reports.
-keepattributes SourceFile,LineNumberTable

# The video engine reads yt-dlp's answers with Jackson (reflection): keep its classes and fields.
-keep class com.yausername.** { *; }
-keep class com.fasterxml.jackson.** { *; }
# The engine unpacks Python and ffmpeg with Apache Commons Compress, which creates its zip helper classes by
# reflection. Shrinking them broke the engine in release builds ("s2.e" errors), so keep them whole.
-keep class org.apache.commons.compress.** { *; }
-keep class org.apache.commons.io.** { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-dontwarn com.fasterxml.jackson.**
-dontwarn org.apache.commons.**
-dontwarn java.beans.**
