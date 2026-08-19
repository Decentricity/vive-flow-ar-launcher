# Vive Flow AR Launcher

A 3D visor launcher for the [HTC Vive Flow](https://www.vive.com/us/product/vive-flow/specs/), grown from [Flow HUD](https://github.com/Decentricity/vive-flow-hud).

A 3×2 grid of apps (**HedgeyOS**, Cat, Dog, Lizard, **Record**, **Writer**) floats on the passthrough with no panel behind it. The grid is locked to the hidden **guide**: same gravity horizon, same compass heading. A thin menu bar sits above the icons; **Restart** on the right relaunches this APK.

Opening an app puts a window on that same guide — title bar, close **X** at the top left. **HedgeyOS** opens an About window (the hand-drawn hedgehog from [hedgeyos/hedgeyos](https://github.com/hedgeyos/hedgeyos)). **Record** snaps the visor every 1/4 second into an MP4 under `Movies/ARLauncher/` for 5s / 10s / 30s / 1m / 5m; capture keeps going if you close the window, and reopening shows Stop until you halt it. Dummy icons show `No such app found! closing...` and dismiss after a moment. **Writer** stays open: the text box from [AR Writer](https://github.com/Decentricity/vive-flow-ar-writer), locked to the guide, typed from a Bluetooth keyboard.

Flip `SHOW_GUIDE` in `MainActivity` to draw the white guide line again for heading debug.

HUE tile: **AR Launcher**.

Heading uses the visor-forward axis projected onto world east/north, not Euler azimuth, so nodding does not send the guide flying. If you are looking almost straight up or down, the heading is frozen until the visor faces the world again.

## Stay in this app (kiosk)

**Suggested: pin AR Launcher as HUE kiosk single-app.** Do not use Android Home / an in-app Exit to return to HUE.

This APK is not a Wave client. It takes exclusive Camera2 on CAM0/CAM1 for stereo passthrough. Stock Flow VR apps never do that — tracking and passthrough stay inside Wave. HUE is still paused underneath our task, and its 6DoF / hand mesh need those same sensors. Handing the cameras back after Exit showed HUE for a moment, then **tracking lost**, a glitched hand asset, and a visor that needed a long-press power cycle. Intermittent, but the handoff is not something Wave was written to do.

HUE kiosk is not Android lock-task and does not replace the system launcher. It is HUE’s own filter: hide the library and auto-launch one `VRAPP`. AR Launcher already has `com.htc.intent.category.VRAPP`, so HUE can list it. Long-press the **headset button** → **Quit Kiosk mode** to leave (that path stays inside Wave).

ADB cannot write the assignment. Kiosk app, activity, and `kiosk_mode` live in Wave OEM’s `oemdata.db` (`content://oem_data/miac_config`), and writes need the signature permission `vive.wave.vr.oem.data.OEMDataWrite`.

On the visor (needs the phone/VR controller for HUE Settings):

1. Back to Home
2. **Settings → Kiosk Mode**
3. **Assign apps → Single app → AR Launcher**
4. **Enter Kiosk Mode**

Or set the same single-app kiosk from the VIVE / VIVE Manager phone app.

**Restart** on the menu bar only relaunches this APK. It does not go to HUE. Headset reboot is not exposed to a third-party app (`PowerManager.reboot` is privileged). The Wave power menu (long-press the headset button) can power off / reboot; a later Restart mode might call that if a safe intent shows up.

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
| Volume **up** | Open the app under the reticle, click the window **X** to close, or click **Restart** to relaunch this APK |
| Volume **down** | Snap the hidden guide (grid + windows) to the reticle heading |
| BT keyboard | Type into Writer while its window is open |
| Headset button (long press) | Wave power menu: quit kiosk, power off, reboot |

Treat the Flow as an untrusted Android 9 display. Do not put secrets on it.

Stereo, cameras, and unsmoothed horizon math are the same as Flow HUD. MIT license.
