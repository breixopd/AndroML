package dev.androml.runtime.service

import dev.androml.runtime.api.RuntimeAdapter
import dev.androml.runtime.api.RuntimeId
import dev.androml.runtime.api.RuntimePackCatalog
import dev.androml.runtime.api.RuntimePackInfo
import dev.androml.runtime.litertlm.LiteRtLmRuntimeAdapter

/** Creates only adapters whose implementation is bundled in this APK. */
class RuntimeRegistry(
    private val modelPath: String,
) {
    fun packs(): List<RuntimePackInfo> = RuntimePackCatalog.production

    fun adapterFor(runtimeId: RuntimeId): RuntimeAdapter {
        val pack = RuntimePackCatalog.find(runtimeId)
            ?: throw IllegalArgumentException("runtime is not known")
        check(pack.usable) { "runtime is not installed" }
        return when (runtimeId.value) {
            "litertlm" -> LiteRtLmRuntimeAdapter(modelPath)
            else -> error("runtime adapter is not bundled")
        }
    }
}
