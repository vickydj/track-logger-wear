package app.tracklogger.wear

import android.content.Context

/** Rate choices, persisted in SharedPreferences. "Max" asks the hardware for its fastest rate. */
object Settings {
    /** GPS request interval in ms; 0 = as fast as the GNSS chip delivers. */
    val GPS_OPTIONS = listOf(0L, 1000L, 2000L, 5000L)
    fun gpsLabel(ms: Long) = if (ms == 0L) "GPS: max" else "GPS: every %.0f s".format(ms / 1000.0)

    /** IMU rate in Hz; -1 = sensor's own minimum delay (max rate), 0 = IMU off. */
    val IMU_OPTIONS = listOf(-1, 200, 100, 50, 25, 0)
    fun imuLabel(hz: Int) = when (hz) {
        -1 -> "IMU: max"
        0 -> "IMU: off"
        else -> "IMU: $hz Hz"
    }

    private fun prefs(c: Context) = c.getSharedPreferences("tracklogger", Context.MODE_PRIVATE)

    fun gpsMs(c: Context): Long = prefs(c).getLong("gps_ms", 0L)
    fun imuHz(c: Context): Int = prefs(c).getInt("imu_hz", -1)

    fun cycleGps(c: Context): Long {
        val next = GPS_OPTIONS[(GPS_OPTIONS.indexOf(gpsMs(c)) + 1) % GPS_OPTIONS.size]
        prefs(c).edit().putLong("gps_ms", next).apply()
        return next
    }

    fun cycleImu(c: Context): Int {
        val next = IMU_OPTIONS[(IMU_OPTIONS.indexOf(imuHz(c)) + 1) % IMU_OPTIONS.size]
        prefs(c).edit().putInt("imu_hz", next).apply()
        return next
    }
}
