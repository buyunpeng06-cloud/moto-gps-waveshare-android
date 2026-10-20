package top.maler.motogps.location

import kotlinx.coroutines.flow.Flow

/** Coordinates are explicitly tagged at the provider boundary; do not assume every Android device has Google Play Services. */
interface LocationSource {
    val fixes: Flow<LocationFix>
    suspend fun start()
    fun stop()
}

data class LocationFix(
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val coordinateSystem: CoordinateSystem,
    val horizontalAccuracyMeters: Float?,
    val speedMetersPerSecond: Float?,
    val bearingDegrees: Float?,
    val timestampEpochMillis: Long,
    val isMock: Boolean = false,
) {
    enum class CoordinateSystem { WGS84, GCJ02, UNKNOWN }

    fun isFresh(nowEpochMillis: Long, maxAgeMillis: Long = 5_000L): Boolean =
        timestampEpochMillis <= nowEpochMillis && nowEpochMillis - timestampEpochMillis <= maxAgeMillis
}
