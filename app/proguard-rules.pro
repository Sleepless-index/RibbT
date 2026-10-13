# Nothing special needed: the app uses org.json and plain HttpURLConnection (no reflection).

# ffmpeg bundle: only its unzip helper is used; the rest of the library isn't on the classpath.
-dontwarn com.yausername.youtubedl_android.**
-dontwarn org.apache.commons.**
-dontwarn org.tukaani.**
-dontwarn com.github.luben.**
-dontwarn org.brotli.**
-dontwarn org.objectweb.asm.**
-keep class com.yausername.youtubedl_common.** { *; }
-keep class com.chaquo.python.** { *; }
-keep class org.apache.commons.compress.** { *; }
