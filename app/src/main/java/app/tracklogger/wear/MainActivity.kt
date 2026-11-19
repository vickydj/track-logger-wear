package app.tracklogger.wear

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText

class MainActivity : ComponentActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
                startRecording()
            } else {
                LoggerState.update { it.copy(message = "Location permission needed") }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WearApp(onToggle = { onToggle() }) }
    }

    private fun onToggle() {
        if (LoggerState.state.value.running) {
            startService(Intent(this, LoggerService::class.java).setAction(LoggerService.ACTION_STOP))
        } else if (hasLocationPermission()) {
            startRecording()
        } else {
            val perms = mutableListOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
            if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
            permissionLauncher.launch(perms.toTypedArray())
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun startRecording() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, LoggerService::class.java).setAction(LoggerService.ACTION_START),
        )
    }
}

@Composable
fun WearApp(onToggle: () -> Unit) {
    MaterialTheme {
        val s by LoggerState.state.collectAsState()
        val ctx = LocalContext.current
        var gpsMs by remember { mutableStateOf(Settings.gpsMs(ctx)) }
        var imuHz by remember { mutableStateOf(Settings.imuHz(ctx)) }
        Scaffold(timeText = { TimeText() }) {
            ScalingLazyColumn(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                item {
                    Text(
                        text = "%.0f km/h".format(s.speedKmh),
                        style = MaterialTheme.typography.title1,
                    )
                }
                item {
                    Text(
                        text = "GPS ${s.satsUsed}/${s.satsVisible} sats" +
                            if (s.accuracyM.isNaN()) "" else "  +/-%.1f m".format(s.accuracyM),
                        style = MaterialTheme.typography.caption1,
                    )
                }
                item {
                    Text(
                        text = "gps ${s.gpsHz} Hz (${s.gpsFixes})  imu ${s.imuHz} Hz (${s.imuSamples})",
                        style = MaterialTheme.typography.caption2,
                        textAlign = TextAlign.Center,
                    )
                }
                item {
                    Button(onClick = onToggle) { Text(if (s.running) "Stop" else "Start") }
                }
                item {
                    Chip(
                        onClick = { if (!s.running) gpsMs = Settings.cycleGps(ctx) },
                        enabled = !s.running,
                        label = { Text(Settings.gpsLabel(gpsMs)) },
                        colors = ChipDefaults.secondaryChipColors(),
                    )
                }
                item {
                    Chip(
                        onClick = { if (!s.running) imuHz = Settings.cycleImu(ctx) },
                        enabled = !s.running,
                        label = { Text(Settings.imuLabel(imuHz)) },
                        colors = ChipDefaults.secondaryChipColors(),
                    )
                }
                item {
                    Text(
                        text = s.message,
                        style = MaterialTheme.typography.caption2,
                        textAlign = TextAlign.Center,
                    )
                }
                s.lastSession?.let {
                    item {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.caption2,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                        )
                    }
                }
            }
        }
    }
}
