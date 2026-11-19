package app.tracklogger.wear

import java.util.Locale

/** Pure formatting and rate logic, kept free of Android classes so it can be unit tested. */
object CsvFormat {
    const val GPS_HEADER = "t_utc_ms,t_mono_us,lat,lon,alt_m,speed_mps,heading_deg,hacc_m,sacc_mps,sats,fix_type"
    const val IMU_HEADER = "t_mono_us,ax,ay,az,gx,gy,gz"

    private fun opt(v: Double?, fmt: String) = if (v == null) "" else String.format(Locale.US, fmt, v)

    /** Missing values become empty fields. fix_type is 3 when altitude is present, else 2. */
    fun gpsRow(
        timeMs: Long, tMonoUs: Long, lat: Double, lon: Double,
        alt: Double?, speed: Float?, bearing: Float?, acc: Float?, sacc: Float?, sats: Int,
    ): String =
        "$timeMs,$tMonoUs," +
            String.format(Locale.US, "%.7f,%.7f,", lat, lon) +
            opt(alt, "%.2f") + "," +
            opt(speed?.toDouble(), "%.3f") + "," +
            opt(bearing?.toDouble(), "%.1f") + "," +
            opt(acc?.toDouble(), "%.2f") + "," +
            opt(sacc?.toDouble(), "%.3f") + "," +
            "$sats," + (if (alt != null) 3 else 2) + "\n"

    fun imuRow(tMonoUs: Long, ax: Float, ay: Float, az: Float, gx: Float, gy: Float, gz: Float): String =
        String.format(Locale.US, "%d,%.4f,%.4f,%.4f,%.5f,%.5f,%.5f\n", tMonoUs, ax, ay, az, gx, gy, gz)

    /**
     * Sampling period to request. setting: -1 = max (the sensor's own minimum delay), otherwise Hz.
     * A fixed rate is clamped so it never asks for faster than the sensor supports.
     * minDelayUs of 0 (on-change sensor) falls back to 5000 us (200 Hz).
     */
    fun imuPeriodUs(minDelayUs: Int, setting: Int): Int {
        val floor = if (minDelayUs > 0) minDelayUs else 5_000
        return if (setting < 0) floor else maxOf(floor, 1_000_000 / setting)
    }
}
