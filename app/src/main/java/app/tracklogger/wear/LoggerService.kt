package app.tracklogger.wear

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executor
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Foreground service that records raw GPS (LocationManager GPS_PROVIDER, not fused) and
 * accelerometer + gyro into a tlog-1 session bundle. Runs with the screen off.
 *
 * Threading: every callback (location, GNSS status, sensors, UI tick) is delivered on one
 * HandlerThread, so the writer and counters need no locks.
 */
class LoggerService : Service() {

    companion object {
        const val ACTION_START = "app.tracklogger.wear.START"
        const val ACTION_STOP = "app.tracklogger.wear.STOP"
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1
    }

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var writer: SessionWriter? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var recording = false

    private var startWallMs = 0L
    private var startElapsedNs = 0L
    private var firstFixElapsedNs = 0L
    private var gpsCount = 0L
    private var imuCount = 0L
    private var satsUsed = 0
    private var satsVisible = 0
    private var lastSpeed = 0f
    private var lastAcc = Float.NaN
    private var hasAccel = false
    private var hasGyro = false
    private var gpsIntervalMs = 0L
    private var imuSetting = -1
    private var imuPeriodUs = 0
    private var accelMinDelayUs = 0
    private var gyroMinDelayUs = 0
    private var lastTickGps = 0L
    private var lastTickImu = 0L
    private val gyro = FloatArray(3)

    private val locationListener = LocationListener { loc -> onLocation(loc) }

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            for (i in 0 until status.satelliteCount) {
                if (status.usedInFix(i)) used++
            }
            satsUsed = used
            satsVisible = status.satelliteCount
        }
    }

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val w = writer ?: return
            when (e.sensor.type) {
                Sensor.TYPE_GYROSCOPE -> {
                    gyro[0] = e.values[0]
                    gyro[1] = e.values[1]
                    gyro[2] = e.values[2]
                }
                Sensor.TYPE_ACCELEROMETER -> {
                    // SensorEvent.timestamp shares the elapsedRealtimeNanos timebase with Location.
                    w.imuRow(
                        e.timestamp / 1000L,
                        e.values[0], e.values[1], e.values[2],
                        gyro[0], gyro[1], gyro[2],
                    )
                    imuCount++
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!recording) return
            writer?.flush()
            val g = gpsCount
            val i = imuCount
            val su = satsUsed
            val sv = satsVisible
            val spd = lastSpeed * 3.6f
            val acc = lastAcc
            val gHz = (g - lastTickGps).toInt()
            val iHz = (i - lastTickImu).toInt()
            lastTickGps = g
            lastTickImu = i
            LoggerState.update {
                it.copy(
                    running = true,
                    gpsFixes = g,
                    imuSamples = i,
                    satsUsed = su,
                    satsVisible = sv,
                    speedKmh = spd,
                    accuracyM = acc,
                    gpsHz = gHz,
                    imuHz = iHz,
                    message = if (g == 0L) "Waiting for GPS..." else "Recording",
                )
            }
            handler?.postDelayed(this, 1000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    private fun onLocation(loc: Location) {
        val w = writer ?: return
        if (gpsCount == 0L) firstFixElapsedNs = loc.elapsedRealtimeNanos
        w.gpsRow(loc, satsUsed)
        gpsCount++
        lastSpeed = if (loc.hasSpeed()) loc.speed else 0f
        lastAcc = if (loc.hasAccuracy()) loc.accuracy else Float.NaN
    }

    private fun startRecording() {
        if (recording) return

        // The activity requests permission before starting us; this is a safety net.
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            LoggerState.update { it.copy(running = false, message = "Location permission missing") }
            stopSelf()
            return
        }

        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)

        recording = true
        startWallMs = System.currentTimeMillis()
        startElapsedNs = SystemClock.elapsedRealtimeNanos()
        gpsCount = 0
        imuCount = 0
        satsUsed = 0
        satsVisible = 0
        lastSpeed = 0f
        lastAcc = Float.NaN
        gyro.fill(0f)
        lastTickGps = 0
        lastTickImu = 0
        gpsIntervalMs = Settings.gpsMs(this)
        imuSetting = Settings.imuHz(this)

        val base = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date()) + "_unspecified_watch"
        val root = File(getExternalFilesDir(null) ?: filesDir, "sessions")
        val dir = File(root, base)
        dir.mkdirs()
        writer = SessionWriter(dir)

        val t = HandlerThread("tracklogger-io")
        t.start()
        thread = t
        val h = Handler(t.looper)
        handler = h

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tracklogger:recording")
        wl.acquire(6 * 60 * 60 * 1000L) // safety timeout: 6 h
        wakeLock = wl

        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        try {
            lm.registerGnssStatusCallback(Executor { r -> h.post(r) }, gnssCallback)
            // minTime 0 = as fast as the GNSS chip delivers; otherwise a best-effort minimum interval.
            // The measured rate is what counts and is written to session.json.
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, gpsIntervalMs, 0f, locationListener, t.looper)
        } catch (e: SecurityException) {
            LoggerState.update { it.copy(message = "Location permission error") }
        }

        val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gy = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        hasAccel = acc != null
        hasGyro = gy != null
        accelMinDelayUs = acc?.minDelay ?: 0
        gyroMinDelayUs = gy?.minDelay ?: 0
        if (imuSetting != 0) {
            // Max = each sensor's own minimum delay; a fixed Hz is clamped to what the sensor supports.
            fun period(s: Sensor): Int {
                val floor = if (s.minDelay > 0) s.minDelay else 5_000
                return if (imuSetting < 0) floor else maxOf(floor, 1_000_000 / imuSetting)
            }
            if (acc != null) {
                imuPeriodUs = period(acc)
                sm.registerListener(sensorListener, acc, imuPeriodUs, 0, h)
            }
            if (gy != null) sm.registerListener(sensorListener, gy, period(gy), 0, h)
        }

        h.post(tick)
        LoggerState.update { LoggerUiState(running = true, message = "Waiting for GPS...") }
    }

    private fun unregisterAll() {
        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm.removeUpdates(locationListener)
            lm.unregisterGnssStatusCallback(gnssCallback)
        } catch (e: Exception) {
        }
        try {
            val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
            sm.unregisterListener(sensorListener)
        } catch (e: Exception) {
        }
    }

    private fun stopRecording() {
        if (!recording) {
            stopSelf()
            return
        }
        recording = false
        unregisterAll()
        LoggerState.update { it.copy(message = "Saving...") }
        // Queued events are processed in order, so finishSession runs after the last sample.
        val h = handler
        if (h != null) h.post { finishSession() } else stopSelf()
    }

    /** Runs on the I/O thread. */
    private fun finishSession() {
        handler?.removeCallbacks(tick)
        val w = writer
        writer = null
        w?.close()
        val dir = w?.dir
        val durS = (SystemClock.elapsedRealtimeNanos() - startElapsedNs) / 1e9

        var result = "Saved"
        var zipName: String? = null
        if (dir != null) {
            try {
                writeSessionJson(dir, durS)
                val zip = File(dir.parentFile, dir.name + ".tlog.zip")
                zipDir(dir, zip)
                dir.deleteRecursively()
                zipName = zip.name
            } catch (e: Exception) {
                result = "Save error: ${e.message}"
            }
        }

        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null

        LoggerState.update { it.copy(running = false, message = result, lastSession = zipName ?: it.lastSession) }

        thread?.quitSafely()
        thread = null
        handler = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun writeSessionJson(dir: File, durS: Double) {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        iso.timeZone = TimeZone.getTimeZone("UTC")
        val safeDur = if (durS > 0.0) durS else 1.0

        val j = JSONObject()
        j.put("format", "tlog-1")
        j.put("source", "wear-os-watch")
        j.put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
        j.put("android_sdk", Build.VERSION.SDK_INT)
        j.put("app_version", "0.2.0")
        j.put("track", "unspecified")
        j.put("start_utc", iso.format(Date(startWallMs)))
        j.put("duration_s", Math.round(safeDur * 10.0) / 10.0)
        j.put("gps_rows", gpsCount)
        j.put("imu_rows", imuCount)
        j.put("gps_rate_hz_measured", Math.round(gpsCount / safeDur * 100.0) / 100.0)
        j.put("imu_rate_hz_measured", Math.round(imuCount / safeDur * 10.0) / 10.0)
        if (gpsCount > 0) {
            j.put("time_to_first_fix_s", Math.round((firstFixElapsedNs - startElapsedNs) / 1e8) / 10.0)
        } else {
            j.put("time_to_first_fix_s", JSONObject.NULL)
        }
        j.put("gps_rate_requested", if (gpsIntervalMs == 0L) "max" else "every ${gpsIntervalMs} ms")
        j.put("imu_rate_requested", when (imuSetting) { -1 -> "max"; 0 -> "off"; else -> "$imuSetting Hz" })
        j.put("imu_period_us_used", imuPeriodUs)
        j.put("accel_min_delay_us", accelMinDelayUs)
        j.put("gyro_min_delay_us", gyroMinDelayUs)
        j.put("accelerometer_available", hasAccel)
        j.put("gyroscope_available", hasGyro)
        j.put("clock", "t_mono_us = elapsedRealtimeNanos/1000, shared by GPS and IMU rows")
        j.put("gps_note", "Android LocationManager GPS_PROVIDER (not fused); hacc_m is Location.getAccuracy (68% radius); fix_type 3 when altitude present else 2")
        j.put("imu_note", "rows are accelerometer-driven; gyro columns hold the most recent gyro event; device frame, wrist-worn so orientation is not fixed")
        File(dir, "session.json").writeText(j.toString(2))
    }

    private fun zipDir(dir: File, zip: File) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zip))).use { z ->
            for (name in listOf("session.json", "gps.csv", "imu.csv")) {
                val f = File(dir, name)
                if (!f.exists()) continue
                z.putNextEntry(ZipEntry(name))
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Track Logger")
            .setContentText("Recording GPS + IMU")
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    override fun onDestroy() {
        if (recording) {
            // Killed while recording: keep whatever was written (CSV files stay in the session folder).
            recording = false
            unregisterAll()
            writer?.close()
            writer = null
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
            thread?.quitSafely()
            thread = null
            handler = null
            LoggerState.update { it.copy(running = false, message = "Stopped unexpectedly") }
        }
        super.onDestroy()
    }
}
