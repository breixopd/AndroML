package dev.androml.app

import dev.androml.core.model.HuggingFaceModelReference

data class HuggingFaceImportState(
    val modelId: String = "",
    val revision: String = "",
    val reference: HuggingFaceModelReference? = null,
    val errorMessage: String? = null,
) {
    val isValid: Boolean
        get() = reference != null && errorMessage == null

    fun validate(): HuggingFaceImportState {
        if (modelId.isBlank() || revision.isBlank()) {
            return copy(
                reference = null,
                errorMessage = "Enter a model ID and a full commit SHA.",
            )
        }
        return try {
            copy(
                reference = HuggingFaceModelReference.parse(modelId.trim(), revision.trim()),
                errorMessage = null,
            )
        } catch (_: IllegalArgumentException) {
            copy(
                reference = null,
                errorMessage = if (revision.trim().length != FULL_COMMIT_LENGTH) {
                    "Use a full 40-character commit SHA."
                } else {
                    "Enter a valid Hugging Face model ID."
                },
            )
        }
    }

    private companion object {
        const val FULL_COMMIT_LENGTH = 40
    }
}
