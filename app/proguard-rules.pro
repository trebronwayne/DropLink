# DropLink R8 / ProGuard rules (used by release builds only)

# Keep file names and line numbers so crash reports from the "Email log" button can still be read.
# The CI run also stores mapping.txt, which turns the obfuscated names back into the real ones.
-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod,*Annotation*
-renamesourcefileattribute SourceFile

# ---- Ktor server (CIO engine) and coroutines ----
-keepclassmembers class io.ktor.** { volatile <fields>; }
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-keep class io.ktor.server.cio.** { *; }
# Desktop-only classes that Ktor and coroutines mention but Android does not have.
-dontwarn java.lang.management.**
-dontwarn java.lang.instrument.**
-dontwarn javax.management.**
-dontwarn sun.misc.**
-dontwarn io.netty.**
-dontwarn reactor.blockhound.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
-dontwarn javax.annotation.**
-dontwarn org.slf4j.**

# ---- BouncyCastle (creates the phone's self-signed HTTPS certificate) ----
# BouncyCastle looks some algorithm classes up by name, so the provider packages stay.
-keep class org.bouncycastle.jce.provider.** { *; }
-keep class org.bouncycastle.jcajce.provider.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**

# ---- Aubree, the mascot (states and pictures are looked up by name) ----
-keep class com.trebronwayne.aubree.** { *; }

# ---- ZXing (QR codes) ----
-dontwarn com.google.zxing.**

# ---- Ktor server startup fix (release build crashed in engine.start with "Array has more than one element") ----
# Ktor builds its application and routing through reflection and generics at start-up. R8 renames or merges the
# classes it looks at, so the lookup finds more than one match. Keeping Ktor whole is the simplest safe fix.
-keep class io.ktor.** { *; }
-keep class kotlin.reflect.** { *; }
-keep class kotlin.Metadata { *; }
-keepclassmembers class **$WhenMappings { <fields>; }
-keep class org.slf4j.** { *; }
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-keep class kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keep class kotlinx.coroutines.CoroutineExceptionHandler { *; }
-keep class * implements kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keep class * implements kotlinx.coroutines.CoroutineExceptionHandler { *; }

# Desktop-JVM classes that Ktor mentions (file watching, autoreload, management) but Android does not have.
# Now that Ktor is kept whole, R8 sees these references, so they must be silenced.
-dontwarn com.sun.nio.file.**
-dontwarn com.sun.**
-dontwarn java.nio.file.**
-dontwarn javax.naming.**
-dontwarn java.beans.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.apache.log4j.**
-dontwarn ch.qos.logback.**
-dontwarn io.netty.**
-dontwarn org.eclipse.jetty.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn io.ktor.server.http.content.**
