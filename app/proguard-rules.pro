# R8 rules of the dogfood build. The default optimize rules keep JNI native methods and their classes;
# kotlinx-serialization, Tink and protobuf ship their own consumer rules.

# Shrunk and renamed, the SSH stack (ConnectBot sshlib with Kaitai Struct, kstatemachine and the pure Kotlin
# crypto libraries) fails the handshake with a protocol error: keep it as it is. App code stays optimized.
-keep class org.connectbot.sshlib.** { *; }
-keep class io.kaitai.struct.** { *; }
-keep class ru.nsk.kstatemachine.** { *; }
-keep class org.kotlincrypto.** { *; }
-keep class asia.hombre.** { *; }
-keep class org.mindrot.jbcrypt.** { *; }

# JVM-only classes referenced by Ktor's debugger detection and the optional SLF4J providers.
-dontwarn java.lang.management.**
-dontwarn org.slf4j.**

# Readable connection states in logcat (only state names are logged).
-keepnames class io.github.totocastaldi.herdrdesktop.coreclient.connection.** { *; }
