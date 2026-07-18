package dev.androml.core.database

import dev.androml.cluster.core.ClusterPeer
import dev.androml.cluster.core.ClusterWorkload
import dev.androml.cluster.core.ContentHash
import dev.androml.cluster.core.NodeCapabilities
import dev.androml.cluster.core.PeerEndpoint
import dev.androml.cluster.core.PeerId
import dev.androml.cluster.core.StoredClusterPeer
import dev.androml.core.api.CertificateFingerprint
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ClusterPeerRepository(
    private val dao: ClusterPeerDao,
) {
    fun observe(): Flow<List<StoredClusterPeer>> = dao.observe().map { entities ->
        entities.map(ClusterPeerStorageMapper::toDomain)
    }

    suspend fun snapshot(): List<StoredClusterPeer> = dao.list().map(ClusterPeerStorageMapper::toDomain)

    suspend fun upsert(peer: StoredClusterPeer) {
        dao.upsert(ClusterPeerStorageMapper.toEntity(peer))
    }

    suspend fun revoke(peerId: PeerId) {
        check(dao.revoke(peerId.value) == 1) { "cluster peer does not exist" }
    }

    suspend fun remove(peerId: PeerId) {
        check(dao.delete(peerId.value) == 1) { "cluster peer does not exist" }
    }
}

object ClusterPeerStorageMapper {
    fun toEntity(stored: StoredClusterPeer): ClusterPeerEntity {
        val peer = stored.peer
        return ClusterPeerEntity(
            peerId = peer.id.value,
            fingerprint = peer.fingerprint.value,
            displayName = peer.displayName,
            host = peer.endpoint.host,
            port = peer.endpoint.port,
            pairedAtEpochMillis = peer.pairedAtEpochMillis,
            certificateExpiresAtEpochMillis = peer.certificateExpiresAtEpochMillis,
            paired = peer.paired,
            revoked = peer.revoked,
            certificateDer = stored.certificateDer.copyOf(),
            protocolMajor = peer.capabilities.protocolMajor,
            protocolMinor = peer.capabilities.protocolMinor,
            supportedWorkloads = peer.capabilities.supportedWorkloads
                .map { it.name.lowercase(Locale.ROOT) }
                .sorted()
                .joinToString(","),
            modelHashes = peer.capabilities.modelHashes
                .map { it.value }
                .sorted()
                .joinToString(","),
            maxConcurrentJobs = peer.capabilities.maxConcurrentJobs,
            availableRamBytes = peer.capabilities.availableRamBytes,
            queueDepth = peer.capabilities.queueDepth,
            thermalSeverity = peer.capabilities.thermalSeverity,
            batteryPercent = peer.capabilities.batteryPercent,
            charging = peer.capabilities.charging,
            lastSeenEpochMillis = peer.capabilities.lastSeenEpochMillis,
        )
    }

    fun toDomain(entity: ClusterPeerEntity): StoredClusterPeer {
        val supportedWorkloads = entity.supportedWorkloads
            .split(',')
            .filter(String::isNotBlank)
            .map { raw ->
                ClusterWorkload.entries.firstOrNull {
                    it.name.equals(raw, ignoreCase = true)
                } ?: throw IllegalArgumentException("unknown persisted cluster workload")
            }
            .toSet()
        val modelHashes = entity.modelHashes
            .split(',')
            .filter(String::isNotBlank)
            .map(ContentHash::parse)
            .toSet()
        return StoredClusterPeer(
            peer = ClusterPeer(
                id = PeerId.parse(entity.peerId),
                fingerprint = CertificateFingerprint.parse(entity.fingerprint),
                displayName = entity.displayName,
                endpoint = PeerEndpoint(entity.host, entity.port),
                pairedAtEpochMillis = entity.pairedAtEpochMillis,
                certificateExpiresAtEpochMillis = entity.certificateExpiresAtEpochMillis,
                paired = entity.paired,
                revoked = entity.revoked,
                capabilities = NodeCapabilities(
                    protocolMajor = entity.protocolMajor,
                    protocolMinor = entity.protocolMinor,
                    supportedWorkloads = supportedWorkloads,
                    modelHashes = modelHashes,
                    maxConcurrentJobs = entity.maxConcurrentJobs,
                    availableRamBytes = entity.availableRamBytes,
                    queueDepth = entity.queueDepth,
                    thermalSeverity = entity.thermalSeverity,
                    batteryPercent = entity.batteryPercent,
                    charging = entity.charging,
                    lastSeenEpochMillis = entity.lastSeenEpochMillis,
                ),
            ),
            certificateDer = entity.certificateDer.copyOf(),
        )
    }
}
