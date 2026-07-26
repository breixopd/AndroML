package dev.androml.runtime.service

import dev.androml.runtime.api.RuntimeAdapter
import dev.androml.runtime.api.RuntimeId
import dev.androml.runtime.api.RuntimePackCatalog
import dev.androml.runtime.api.RuntimePackInfo
import dev.androml.runtime.litertlm.LiteRtLmRuntimeAdapter
import dev.androml.runtime.litert.LiteRtRuntimeAdapter
import dev.androml.runtime.onnx.OnnxRuntimeAdapter
import dev.androml.runtime.executorch.ExecuTorchRuntimeAdapter
import dev.androml.runtime.llamacpp.LlamaCppRuntimeAdapter

/** Creates only adapters whose implementation is bundled in this APK. */
class RuntimeRegistry(
    private val modelPath: String,
) {
    fun packs(): List<RuntimePackInfo> = RuntimePackCatalog.production

    fun adapterFor(runtimeId: RuntimeId): RuntimeAdapter {
        // The application advertises the native pack during Application.onCreate. This keeps
        // the shared catalogue truthful in the no-vendor unit-test/build configuration.
        if (runtimeId.value == "llamacpp" && System.getProperty("androml.runtime.llamacpp.bundled") == null) {
            dev.androml.runtime.llamacpp.LlamaCppRuntimeAvailability.advertise()
        }
        val pack = RuntimePackCatalog.find(runtimeId)
            ?: throw IllegalArgumentException("runtime is not known")
        check(pack.usable) { "runtime is not installed" }
        return when (runtimeId.value) {
            "litertlm" -> LiteRtLmRuntimeAdapter(modelPath)
            "litert" -> LiteRtRuntimeAdapter(modelPath)
            "onnxruntime" -> OnnxRuntimeAdapter(modelPath)
            "executorch" -> ExecuTorchRuntimeAdapter(modelPath)
            "llamacpp" -> LlamaCppRuntimeAdapter(modelPath)
            else -> error("runtime adapter is not bundled")
        }
    }
}
