package top.maler.motogps.gateway

/** Uses only the existing upstream gateway routes; no AMap Web Service key belongs in this client. */
interface GatewayClient {
    suspend fun searchPlaces(query: String, locationWgs84: Wgs84Point?): List<PlaceResult>
    suspend fun routeOptions(request: RouteRequest): List<RouteOption>
    suspend fun buildRoute(request: RouteRequest, selectedRouteId: String): RouteBundle
    suspend fun surroundingTile(z: Int, x: Int, y: Int): ByteArray
}

data class Wgs84Point(val latitudeDeg: Double, val longitudeDeg: Double)
data class PlaceResult(val id: String, val name: String, val address: String, val locationWgs84: Wgs84Point)
data class RouteRequest(val requestId: UInt, val originWgs84: Wgs84Point, val destinationWgs84: Wgs84Point, val destinationPoiId: String?)
data class RouteOption(val id: String, val distanceMeters: Double, val durationSeconds: Long, val trafficSummary: String?)
data class Gcj02Point(val latitudeDeg: Double, val longitudeDeg: Double)
data class RouteBundle(val routeId: String, val geometryGcj02: List<Gcj02Point>, val distanceMeters: Double, val durationSeconds: Long)
