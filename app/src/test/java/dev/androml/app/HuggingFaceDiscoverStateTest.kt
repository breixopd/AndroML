package dev.androml.app

import dev.androml.core.network.HuggingFaceNetworkError
import dev.androml.core.network.HuggingFaceNetworkException
import org.junit.Assert.assertEquals
import org.junit.Test

class HuggingFaceDiscoverStateTest {
    @Test
    fun unauthorizedMetadataRequestsExplainReadTokenRequirement() {
        val message = huggingFaceUserMessage(
            HuggingFaceNetworkException(
                code = HuggingFaceNetworkError.Unauthorized,
                message = "request rejected",
                httpStatus = 401,
            ),
        )

        assertEquals(
            "Hugging Face rejected the request. Add a read token after approving access to the model.",
            message,
        )
    }

    @Test
    fun gatedMetadataRequestsExplainBrowserApproval() {
        val message = huggingFaceUserMessage(
            HuggingFaceNetworkException(
                code = HuggingFaceNetworkError.Forbidden,
                message = "request rejected",
                httpStatus = 403,
            ),
        )

        assertEquals(
            "Hugging Face denied access. Approve the gated model in a browser, then use a read token.",
            message,
        )
    }

    @Test
    fun rateLimitedMetadataRequestsSuggestRetrying() {
        val message = huggingFaceUserMessage(
            HuggingFaceNetworkException(
                code = HuggingFaceNetworkError.RateLimited,
                message = "request rejected",
                httpStatus = 429,
                retryAfterSeconds = 30,
            ),
        )

        assertEquals("Hugging Face rate-limited this request. Try again in about 30 seconds.", message)
    }
}
