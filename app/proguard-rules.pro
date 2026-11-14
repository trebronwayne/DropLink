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
