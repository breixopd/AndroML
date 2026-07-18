# Runtime-specific keep rules will be added with the isolated inference service.

# Ktor's optional JVM-only debugger detector references java.management. Android does not
# ship those classes and the detector is not used by the app server.
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean

# Netty includes optional JVM integrations that are not shipped by Android:
# native OpenSSL (netty-tcnative), JFR, JNDI LDAP, and optional Log4j bindings.
# The server uses the JDK TLS provider on Android, so these code paths are not
# part of the runtime surface.
-dontwarn io.netty.internal.tcnative.AsyncSSLPrivateKeyMethod
-dontwarn io.netty.internal.tcnative.AsyncTask
-dontwarn io.netty.internal.tcnative.Buffer
-dontwarn io.netty.internal.tcnative.CertificateCallback
-dontwarn io.netty.internal.tcnative.CertificateCompressionAlgo
-dontwarn io.netty.internal.tcnative.CertificateVerifier
-dontwarn io.netty.internal.tcnative.Library
-dontwarn io.netty.internal.tcnative.SSL
-dontwarn io.netty.internal.tcnative.SSLContext
-dontwarn io.netty.internal.tcnative.SSLPrivateKeyMethod
-dontwarn io.netty.internal.tcnative.SSLSessionCache
-dontwarn io.netty.internal.tcnative.SessionTicketKey
-dontwarn io.netty.internal.tcnative.SniHostNameMatcher
-dontwarn javax.naming.ldap.LdapName
-dontwarn javax.naming.ldap.Rdn
-dontwarn jdk.jfr.Category
-dontwarn jdk.jfr.DataAmount
-dontwarn jdk.jfr.Description
-dontwarn jdk.jfr.Enabled
-dontwarn jdk.jfr.Event
-dontwarn jdk.jfr.FlightRecorder
-dontwarn jdk.jfr.Label
-dontwarn jdk.jfr.MemoryAddress
-dontwarn jdk.jfr.Name
-dontwarn org.apache.log4j.Level
-dontwarn org.apache.log4j.Logger
-dontwarn org.apache.log4j.Priority
-dontwarn org.apache.logging.log4j.Level
-dontwarn org.apache.logging.log4j.LogManager
-dontwarn org.apache.logging.log4j.Logger
-dontwarn org.apache.logging.log4j.message.MessageFactory
-dontwarn org.apache.logging.log4j.spi.ExtendedLogger
-dontwarn org.apache.logging.log4j.spi.ExtendedLoggerWrapper
