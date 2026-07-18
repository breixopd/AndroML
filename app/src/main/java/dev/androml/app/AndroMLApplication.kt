package dev.androml.app

import android.app.Application
import dev.androml.core.database.AndroMlDatabase
import dev.androml.core.database.ModelCatalogRepository
import dev.androml.core.files.FileArtifactStore
import dev.androml.core.network.HuggingFaceArtifactDownloader
import dev.androml.core.network.HuggingFaceModelClient
import java.io.File
import okhttp3.OkHttpClient

class AndroMLApplication : Application() {
    val httpClient: OkHttpClient by lazy { OkHttpClient() }

    val huggingFaceClient: HuggingFaceModelClient by lazy {
        HuggingFaceModelClient(httpClient)
    }

    val artifactStore: FileArtifactStore by lazy {
        FileArtifactStore(File(filesDir, "model-artifacts"))
    }

    val artifactDownloader: HuggingFaceArtifactDownloader by lazy {
        HuggingFaceArtifactDownloader(httpClient, artifactStore)
    }

    val catalogDatabase: AndroMlDatabase by lazy {
        AndroMlDatabase.open(this)
    }

    val catalogRepository: ModelCatalogRepository by lazy {
        ModelCatalogRepository(catalogDatabase.modelCatalogDao())
    }
}
