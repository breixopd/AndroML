package dev.androml.core.database

import androidx.room.Entity

@Entity(tableName = "cluster_peers")
data class ClusterPeerEntity(
    @androidx.room.PrimaryKey val peerId: String,
    val fingerprint: String,
    val displayName: String,
    val host: String,
    val port: Int,
    val pairedAtEpochMillis: Long,
    val certificateExpiresAtEpochMillis: Long,
    val paired: Boolean,
    val revoked: Boolean,
    val certificateDer: ByteArray,
    val protocolMajor: Int,
    val protocolMinor: Int,
    val supportedWorkloads: String,
    val modelHashes: String,
    val maxConcurrentJobs: Int,
    val availableRamBytes: Long,
    val queueDepth: Int,
    val thermalSeverity: Int,
    val batteryPercent: Int,
    val charging: Boolean,
    val lastSeenEpochMillis: Long,
)
