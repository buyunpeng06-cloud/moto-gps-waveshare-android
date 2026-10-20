package top.maler.motogps.navigation

import kotlinx.coroutines.flow.StateFlow
import top.maler.motogps.location.LocationFix

/** Navigation-session boundary. Real route calculation/matching is added in phase 2 via shared nav_core JNI. */
interface NavigationSession {
    val state: StateFlow<NavigationSessionState>
    suspend fun start(destinationId: String, destinationName: String)
    fun stop()
    fun onLocation(fix: LocationFix)
}

data class NavigationSessionState(
    val phase: Phase = Phase.IDLE,
    val destinationName: String? = null,
    val failure: String? = null,
    val isSimulated: Boolean = false,
) {
    enum class Phase { IDLE, ACQUIRING_LOCATION, SEARCHING, PREVIEWING, NAVIGATING, REROUTING, ARRIVED, FAILED }
}
