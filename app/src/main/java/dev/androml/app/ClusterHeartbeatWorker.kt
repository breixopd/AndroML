package dev.androml.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException

/**
 * Maintains the capability freshness contract used by the router. It is deliberately a
 * best-effort worker: a phone that is asleep or off-LAN is retried, never revoked.
 */
class ClusterHeartbeatWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val application = applicationContext.applicationContext as? AndroMLApplication
            ?: return Result.failure(workDataOf("error" to "application-unavailable"))
        return try {
            application.clusterController.refreshLocalCapabilities()
            val peers = application.clusterPeerRepository.snapshot()
                .filter { it.peer.paired && !it.peer.revoked }
            var refreshed = 0
            var failed = 0
            peers.forEach { stored ->
                try {
                    application.clusterController.refreshPeer(stored.peer.id)
                    refreshed += 1
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    failed += 1
                }
            }
            if (failed > 0 && refreshed == 0 && peers.isNotEmpty()) {
                Result.retry()
            } else {
                Result.success(workDataOf("refreshed" to refreshed, "failed" to failed))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
