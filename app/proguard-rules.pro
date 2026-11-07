# Keep model fields used by SQLite cursor mapping / reflection-free code.
-keepclassmembers class com.xsr.companion.data.** { *; }
-keepclassmembers class com.xsr.companion.protocol.** { *; }
-keepclassmembers class com.xsr.companion.sync.** { *; }

# Tink / androidx.security-crypto annotations not present at runtime
-dontwarn javax.annotation.**
-dontwarn java.lang.ClassValue
-dontwarn com.google.errorprone.annotations.**
