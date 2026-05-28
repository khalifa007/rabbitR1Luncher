# R8 keep rules for CarrotOS R1Launcher.
# Audit: only kotlinx.serialization + BouncyCastle have reflection risk.
# Compose / OkHttp / ZXing / nanohttpd ship their own consumer rules via AAR.

-keepattributes *Annotation*, InnerClasses, Signature, Exceptions, EnclosingMethod
-dontnote kotlinx.serialization.AnnotationsKt

# kotlinx.serialization: the compiler plugin generates `$$serializer` companions
# accessed reflectively via `Companion.serializer()`. These three rules are the
# pattern from the official kotlinx.serialization docs, scoped to our package.
-keep,includedescriptorclasses class com.r1.launcher.**$$serializer { *; }
-keepclassmembers class com.r1.launcher.** {
    *** Companion;
}
-keepclasseswithmembers class com.r1.launcher.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# BouncyCastle: heavy internal Class.forName lookups across providers.
# Used by DeviceIdentityStore for EC keypair handling. Keep wholesale.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# JavaMail (com.sun.mail:android-mail) + JAF activation, used by SmtpSender for
# the Meetings email export. These do heavy reflective provider lookup —
# Transport/Store protocol classes via Class.forName, and content handlers
# (com.sun.mail.handlers.*) resolved by name through the activation framework.
# R8 would strip/rename them and multipart SMTP send would break ONLY in the
# minified release build (never in the debug dev loop). Keep wholesale; the
# handler class names are also referenced as strings in SmtpSender.ensureMailcap.
-keep class com.sun.mail.** { *; }
-keep class javax.mail.** { *; }
-keep class javax.activation.** { *; }
-keep class com.sun.activation.** { *; }
-dontwarn com.sun.mail.**
-dontwarn javax.mail.**
-dontwarn javax.activation.**
-dontwarn com.sun.activation.**
-dontwarn myjava.awt.datatransfer.**

# OkHttp / Okio / Conscrypt warnings (SSL platform-detection paths we don't hit).
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# nanohttpd: optional SSL classes referenced by name on init.
-dontwarn org.nanohttpd.**

# Google Tink (transitive via androidx.security:security-crypto) references
# compile-time errorprone annotations that are not on the runtime classpath.
-dontwarn com.google.errorprone.annotations.**

# Compose lambdas survive R8 fine, but optimize-passes occasionally rewrite the
# state-snapshot machinery in ways that confuse stack traces. Keep line numbers
# so on-device tombstones stay legible.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
