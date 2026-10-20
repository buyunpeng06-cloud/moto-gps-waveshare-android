package top.maler.motogps.maps

import top.maler.motogps.gateway.Gcj02Point

/** Offline map storage boundary; tile IDs use WGS84 Web Mercator while returned feature geometry is GCJ-02. */
interface MapStore {
    suspend fun downloadRegion(regionId: String, boundsWgs84: BoundsWgs84, onProgress: (DownloadProgress) -> Unit)
    suspend fun downloadAlongRoute(route: List<Gcj02Point>, corridorMeters: Int = 1_000, onProgress: (DownloadProgress) -> Unit)
    suspend fun pause(downloadId: String)
    suspend fun resume(downloadId: String)
    suspend fun delete(packageId: String)
    suspend fun setCacheBudgetBytes(maxBytes: Long)
}

data class BoundsWgs84(val west: Double, val south: Double, val east: Double, val north: Double)
data class DownloadProgress(val downloadId: String, val completed: Long, val total: Long, val paused: Boolean, val failure: String?)
