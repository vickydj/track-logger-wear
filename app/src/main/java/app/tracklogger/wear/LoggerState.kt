package app.tracklogger.wear

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class LoggerUiState(
    val running: Boolean = false,
    val gpsFixes: Long = 0,
    val imuSamples: Long = 0,
    val satsUsed: Int = 0,
    val satsVisible: Int = 0,
    val speedKmh: Float = 0f,
    val accuracyM: Float = Float.NaN,
    val gpsHz: Int = 0,
    val imuHz: Int = 0,
    val gpsMs: Long = 0L,
    val imuSetting: Int = -1,
    val message: String = "Ready",
    val lastSession: String? = null,
)

/** Process-wide state shared between LoggerService (writer) and the Compose UI (reader). */
object LoggerState {
    private val _state = MutableStateFlow(LoggerUiState())
    val state: StateFlow<LoggerUiState> = _state.asStateFlow()

    fun update(block: (LoggerUiState) -> LoggerUiState) {
        _state.update(block)
    }
}
