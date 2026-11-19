package app.tracklogger.wear

import android.location.Location
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException

/**
 * Writes gps.csv and imu.csv in the Track Logger session-bundle format (tlog-1).
 * Not thread-safe: all calls must come from the single I/O handler thread.
 *
 * gps.csv: t_utc_ms,t_mono_us,lat,lon,alt_m,speed_mps,heading_deg,hacc_m,sacc_mps,sats,fix_type
 * imu.csv: t_mono_us,ax,ay,az,gx,gy,gz   (m/s^2, rad/s, device frame)
 * Missing values are written as empty fields.
 *
 * A write error (for example storage full) sets [failed] instead of throwing, so the caller can stop cleanly.
 */
class SessionWriter(val dir: File) {

    /** True after the first write error. Further writes are skipped. */
    @Volatile
    var failed = false
        private set

    private val gps = BufferedWriter(FileWriter(File(dir, "gps.csv")), 64 * 1024)
    private val imu = BufferedWriter(FileWriter(File(dir, "imu.csv")), 64 * 1024)

    init {
        safely {
            gps.write(CsvFormat.GPS_HEADER + "\n")
            imu.write(CsvFormat.IMU_HEADER + "\n")
        }
    }

    private inline fun safely(block: () -> Unit) {
        if (failed) return
        try {
            block()
        } catch (e: IOException) {
            failed = true
        }
    }

    fun gpsRow(l: Location, sats: Int) = safely {
        gps.write(
            CsvFormat.gpsRow(
                timeMs = l.time,
                tMonoUs = l.elapsedRealtimeNanos / 1000L,
                lat = l.latitude,
                lon = l.longitude,
                alt = if (l.hasAltitude()) l.altitude else null,
                speed = if (l.hasSpeed()) l.speed else null,
                bearing = if (l.hasBearing()) l.bearing else null,
                acc = if (l.hasAccuracy()) l.accuracy else null,
                sacc = if (l.hasSpeedAccuracy()) l.speedAccuracyMetersPerSecond else null,
                sats = sats,
            )
        )
    }

    fun imuRow(tMonoUs: Long, ax: Float, ay: Float, az: Float, gx: Float, gy: Float, gz: Float) = safely {
        imu.write(CsvFormat.imuRow(tMonoUs, ax, ay, az, gx, gy, gz))
    }

    fun flush() = safely {
        gps.flush()
        imu.flush()
    }

    fun close() {
        try {
            gps.close()
        } catch (e: IOException) {
            failed = true
        }
        try {
            imu.close()
        } catch (e: IOException) {
            failed = true
        }
    }
}
