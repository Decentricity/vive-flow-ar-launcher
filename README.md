# Vive Flow AR Launcher

A 3D visor launcher for the [HTC Vive Flow](https://www.vive.com/us/product/vive-flow/specs/), grown from [Flow HUD](https://github.com/Decentricity/vive-flow-hud).

Menus and icons will live on a **guide line**: a white bar that stays **parallel to the horizon** (same gravity roll and pitch as the HUD bar) but is **locked to a compass heading**. Look left or right and it slides along that horizon. Volume-up click parks it on the heading the reticle is facing. That line is the shelf the launcher will hang from.

HUE tile: **AR Launcher**.

Heading uses the visor-forward axis projected onto world east/north, not Euler azimuth, so nodding does not send the guide flying. If you are looking almost straight up or down, the heading is frozen until the visor faces the world again.

## Run

```bash
export ANDROID_SDK_ROOT=/path/to/sdk
./build.sh
SERIAL=FA22B2S00442
adb -s "$SERIAL" install -r ar-launcher.apk
adb -s "$SERIAL" shell pm grant com.decentricity.arlauncher android.permission.CAMERA
adb -s "$SERIAL" shell am start -n com.decentricity.arlauncher/.MainActivity
```

| Control | |
| --- | --- |
| Volume **up** | Primary click: snap the guide to the reticle’s heading |
| Volume **down** | Secondary click: HUD n-gon stamp (debug) |

Treat the Flow as an untrusted Android 9 display. Do not put secrets on it.

Stereo, cameras, and unsmoothed horizon math are the same as Flow HUD. MIT license.
