# Runtime-specific keep rules will be added with the isolated inference service.

# Ktor's optional JVM-only debugger detector references java.management. Android does not
# ship those classes and the detector is not used by the app server.
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean
