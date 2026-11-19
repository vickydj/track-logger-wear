package app.tracklogger.wear

import android.location.Location
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.util.Locale

/**
 * Writes gps.csv and imu.csv in the Track Logger session-bundle format (tlog-1).
 * Not thread-safe: all calls must come from the single I/O handler thread.
 *
 * gps.csv: t_utc_ms,t_mono_us,lat,lon,alt_m,speed_mps,heading_deg,hacc_m,sacc_mps,sats,fix_type
 * imu.csv: t_mono_us,ax,ay,az,gx,gy,gz   (m/s^2, rad/s, device frame)
 * Missing values are written as empty fields.
 */
class SessionWriter(val dir: File) {

    private val gps = BufferedWriter(FileWriter(File(dir, "gps.csv")), 64 * 1024)
    private val imu = BufferedWriter(FileWriter(File(dir, "imu.csv")), 64 * 1024)

    init {
        gps.write("t_utc_ms,t_mono_us,lat,lon,alt_m,speed_mps,heading_deg,hacc_m,sacc_mps,sats,fix_type\n")
        imu.write("t_mono_us,ax,ay,az,gx,gy,gz\n")
    }

    fun gpsRow(l: Location, sats: Int) {
        val sb = StringBuilder(160)
        sb.append(l.time).append(',')
        sb.append(l.elapsedRealtimeNanos / 1000L).append(',')
        sb.append(String.format(Locale.US, "%.7f,%.7f,", l.latitude, l.longitude))
        sb.append(if (l.hasAltitude()) String.format(Locale.US, "%.2f", l.altitude) else "").append(',')
        sb.append(if (l.hasSpeed()) String.format(Locale.US, "%.3f", l.speed) else "").append(',')
        sb.append(if (l.hasBearing()) String.format(Locale.US, "%.1f", l.bearing) else "").append(',')
        sb.append(if (l.hasAccuracy()) String.format(Locale.US, "%.2f", l.accuracy) else "").append(',')
        sb.append(
            if (l.hasSpeedAccuracy()) String.format(Locale.US, "%.3f", l.speedAccuracyMetersPerSecond) else ""
        ).append(',')
        sb.append(sats).append(',')
        sb.append(if (l.hasAltitude()) 3 else 2).append('\n')
        gps.write(sb.toString())
    }

    fun imuRow(tMonoUs: Long, ax: Float, ay: Float, az: Float, gx: Float, gy: Float, gz: Float) {
        imu.write(
            String.format(Locale.US, "%d,%.4f,%.4f,%.4f,%.5f,%.5f,%.5f\n", tMonoUs, ax, ay, az, gx, gy, gz)
        )
    }

    fun flush() {
        try {
            gps.flush()
            imu.flush()
        } catch (e: Exception) {
            // ignore; close() will surface persistent problems
        }
    }

    fun close() {
        try {
            gps.close()
        } catch (e: Exception) {
        }
        try {
            imu.close()
        } catch (e: Exception) {
        }
    }
}
