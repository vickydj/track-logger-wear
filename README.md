# Track Logger for Wear OS (v0.2, untested)

Standalone Wear OS app that records raw GPS (about 1 Hz) and accelerometer + gyro (about 100 Hz requested)
into a `tlog-1` session bundle, the same format as the Track Logger plan, so the phone app and the
DIY logger can share importers.

STATUS: source written without a compiler or a watch. It has never been built or run.
Expect a few compile errors on first sync; send them to Claude and they get fixed.

## Output

On Stop, one file per session:

    <date>_<time>_unspecified_watch.tlog.zip
      session.json   metadata + measured GPS/IMU rates + time to first fix
      gps.csv        t_utc_ms,t_mono_us,lat,lon,alt_m,speed_mps,heading_deg,hacc_m,sacc_mps,sats,fix_type
      imu.csv        t_mono_us,ax,ay,az,gx,gy,gz   (m/s^2, rad/s, device frame)

Location on the watch:
`/sdcard/Android/data/app.tracklogger.wear/files/sessions/`

## Build and install

1. Install Android Studio (current stable). Open this folder (File > Open).
   If it asks to upgrade AGP / Gradle / Kotlin, accept. Versions in the build files are from memory.
2. Watch: Settings > About watch > Software, tap Software version 5 times to unlock Developer options.
   Then Developer options > ADB debugging ON, Debug over Wi-Fi ON (menu names vary by One UI Watch version).
3. Watch and PC on the same Wi-Fi. In Android Studio: Device Manager > Pair Devices Using Wi-Fi, or in a terminal:
       adb pair <ip>:<pairing-port>      (enter the pairing code shown on the watch)
       adb connect <ip>:<port>
4. Pick the watch in the device dropdown and press Run. The app "Track Logger" appears in the app list.
5. First launch: Start > allow Location (Precise) and Notifications.

## Get data off the watch

    adb pull /sdcard/Android/data/app.tracklogger.wear/files/sessions ./watch_sessions

If that path is blocked on your watch's Android version, use a debug build and:

    adb exec-out run-as app.tracklogger.wear ls files/sessions

## First test (10 minutes)

1. Go outside with clear sky. Start. Wait until "GPS n/m sats" fills and fixes count up.
2. Walk or drive 5 minutes, screen off for at least part of it (the service must keep recording).
3. Stop. Pull the zip. Open `session.json`: check `gps_rate_hz_measured` (expect about 1) and
   `imu_rate_hz_measured` (expect about 100, but Samsung may give less; that number is what matters).
4. Open gps.csv in a spreadsheet and plot lat/lon: it should look like your walk.

## Known limits

- GPS rate is chosen on the watch (Max, 1 s, 2 s, 5 s). Max = whatever the watch GNSS chip delivers; the measured rate is shown live and saved in session.json. Not confirmed above 1 Hz for the Galaxy Watch Ultra, so check the measured value.
- IMU rate is chosen on the watch (Max, 200, 100, 50, 25 Hz, off). Max uses each sensor minimum delay; requested and actual periods are saved in session.json.
- IMU rows are accelerometer-driven with the latest gyro value held (not interpolated).
- Wrist mounting: axes are not fixed to the car. Fine for rough g-force comparison only.
- No lap timer, no track selection yet (track is "unspecified"), no phone sync yet. Manual adb pull.
- Battery cost of continuous GPS + 100 Hz sensors is unmeasured. Check it on a real session.
- targetSdk 34. Play Store publishing needs a current target level and location declarations; sideloading does not.
