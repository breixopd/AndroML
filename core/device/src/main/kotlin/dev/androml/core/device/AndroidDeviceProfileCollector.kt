package dev.androml.core.device

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import dev.androml.core.model.DeviceProfile
import dev.androml.core.model.ThermalStatus

class AndroidDeviceProfileCollector(private val context: Context) {
    fun collect(): DeviceProfile {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memoryInfo)

        val statFs = StatFs(context.filesDir.absolutePath)
        val powerManager = context.getSystemService(PowerManager::class.java)
        val batteryManager = context.getSystemService(BatteryManager::class.java)
        val packageManager = context.packageManager

        return DeviceProfile(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            androidApi = Build.VERSION.SDK_INT,
            supportedAbis = Build.SUPPORTED_ABIS.toList(),
            cpuCoreCount = Runtime.getRuntime().availableProcessors(),
            totalMemoryBytes = memoryInfo.totalMem.takeIf { it > 0 },
            availableMemoryBytes = memoryInfo.availMem.takeIf { it > 0 },
            availableStorageBytes = statFs.availableBlocksLong * statFs.blockSizeLong,
            isCharging = batteryManager?.isCharging == true,
            thermalStatus = powerManager.toThermalStatus(),
            hasVulkan = packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL),
        )
    }

    private fun PowerManager?.toThermalStatus(): ThermalStatus {
        if (this == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return ThermalStatus.Unknown
        }

        return when (currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE,
            PowerManager.THERMAL_STATUS_LIGHT,
            -> ThermalStatus.Nominal

            PowerManager.THERMAL_STATUS_MODERATE -> ThermalStatus.Warm
            PowerManager.THERMAL_STATUS_SEVERE -> ThermalStatus.Severe
            PowerManager.THERMAL_STATUS_CRITICAL,
            PowerManager.THERMAL_STATUS_EMERGENCY,
            PowerManager.THERMAL_STATUS_SHUTDOWN,
            -> ThermalStatus.Critical

            else -> ThermalStatus.Unknown
        }
    }
}
