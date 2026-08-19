# Vive Flow AR Launcher

![AR Launcher on a Vive Flow](media/ar-launcher-demo.gif)

[Full recording (MP4, with audio)](media/ar-launcher-demo.mp4)

A 3D visor launcher for the [HTC Vive Flow](https://www.vive.com/us/product/vive-flow/specs/), grown from [Flow HUD](https://github.com/Decentricity/vive-flow-hud).

A 3×2 grid of apps (**HedgeyOS**, Cat, **Files**, Lizard, **Record**, **Writer**) floats on the passthrough with no panel behind it. The grid is locked to the hidden **guide**: same gravity horizon, same compass heading.

Opening an app fades the grid out and a window in on that same guide — title bar, close **X** at the top left, glass fill so the cameras show through. Closing **X** fades the window out and the grid back in. **HedgeyOS** opens an About window (the hand-drawn hedgehog from [hedgeyos/hedgeyos](https://github.com/hedgeyos/hedgeyos)). **Files** lands in Android shared storage (`/storage/emulated/0`). Look at a name and volume-up to open a folder; **`~`** jumps home, **`..`** goes up. Volume-up on an image, video, or text file fades in a second guide-locked window on top of the listing: pictures display, videos play, text shows. **X** on that preview fades back to the listing. **Record** snaps the visor at **16 fps** into an MP4 under **`~/Movies`** (`/storage/emulated/0/Movies`, `rec-*.mp4`) for 5s / 10s / 30s / 1m / 5m, with a **Mic on / Muted** toggle (AAC, default on). Pressing Record fades the window away to the grid while capture continues; reopen Record to Stop. Dummy icons show `No such app found! closing...` and fade out after a moment. **Writer** stays open: the text box from [AR Writer](https://github.com/Decentricity/vive-flow-ar-writer), locked to the guide, typed from a Bluetooth keyboard.

There is no in-app Exit or Restart. Closing Camera2 and returning to HUE (or even restarting this process) has already lost Wave tracking. Stay in this APK.

Flip `SHOW_GUIDE` in `MainActivity` to draw the white guide line again for heading debug.

HUE tile: **AR Launcher**.

Heading uses the visor-forward axis projected onto world east/north, not Euler azimuth, so nodding does not send the guide flying. If you are looking almost straight up or down, the heading is frozen until the visor faces the world again.

## Stay in this app

This APK starts itself on the visor (no PC required):

- `BootReceiver` pokes MainActivity several times after headset boot
- `KeepAliveReceiver` is an on-device alarm: if another activity is in front, bring AR Launcher forward

This development PC is USB-only. Do not enable `vive-flow-arlauncher.service` for daily use.

Unstick during development: `adb -s FA22B2S00442 shell am start -n com.decentricity.arlauncher/.MainActivity`

This APK is not a Wave client. It takes exclusive Camera2 on CAM0/CAM1 for stereo passthrough. Stock Flow VR apps never do that. Handing the cameras back to HUE has already lost tracking.

## Run

```bash
export ANDROID_SDK_ROOT=/path/to/sdk
./build.sh
SERIAL=FA22B2S00442
adb -s "$SERIAL" install -r ar-launcher.apk
adb -s "$SERIAL" shell pm grant com.decentricity.arlauncher android.permission.CAMERA
adb -s "$SERIAL" shell pm grant com.decentricity.arlauncher android.permission.READ_EXTERNAL_STORAGE
adb -s "$SERIAL" shell pm grant com.decentricity.arlauncher android.permission.WRITE_EXTERNAL_STORAGE
adb -s "$SERIAL" shell pm grant com.decentricity.arlauncher android.permission.RECORD_AUDIO
adb -s "$SERIAL" shell am start -n com.decentricity.arlauncher/.MainActivity
```

| Control | |
| --- | --- |
| Volume **up** | Open the app under the reticle, click the window **X**, Files / preview / pause-play, Record duration / mic / start |
| Volume **down** | Snap the hidden guide (grid + windows) to the reticle heading |
| BT keyboard | Type into Writer while its window is open |
| Headset button (long press) | Wave power menu (power off / reboot) |

Treat the Flow as an untrusted Android 9 display. Do not put secrets on it.

Stereo, cameras, and unsmoothed horizon math are the same as Flow HUD. MIT license.
