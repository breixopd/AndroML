package dev.androml.core.network

/** Stable user-facing sort choices mapped to the Hub's official model-list API. */
enum class HuggingFaceModelSort(
    val label: String,
    val queryValue: String,
    val direction: Int,
) {
    Popular("Popular", "downloads", -1),
    MostLiked("Most liked", "likes", -1),
    RecentlyUpdated("Recently updated", "lastModified", -1),
    Newest("Newest", "createdAt", -1),
}
