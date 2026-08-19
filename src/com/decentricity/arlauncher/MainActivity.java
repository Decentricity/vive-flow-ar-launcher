package com.decentricity.arlauncher;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity implements SensorEventListener,
        CameraBank.Listener, ScreenRecorder.Listener, FileViewer.Listener {
    /**
     * Measured from cam0/cam1 stills of the monitor bank (640x480, already upright).
     * Same world point is 124px left / 10px up in CAM1 vs CAM0 (crossed + slight vertical).
     * Split that as a display HIT: each eye moves half, no extra FOV zoom.
     */
    private static final float MATCH_DX_FRAC = 124f / 640f;
    private static final float MATCH_DY_FRAC = 10f / 480f;
    /**
     * Cameras sit on the lower front of the visor; the eye lenses are on the vertical
     * centerline. A low camera sees an eye-level object in the upper FOV, so the
     * passthrough sits too high. Shift both eyes down. Do not zoom to hide the
     * black bars — Vive's own passthrough left them.
     */
    private static final float CAMERA_BELOW_FRAC = 0.05f;
    /**
     * 640x480 visor preview, letterboxed to fill eye width. Horizon pitch is mapped
     * through this image so looking up/down moves the bar with the real horizon.
     */
    private static final float CAM_PREVIEW_ASPECT = 480f / 640f;
    private static final float CAMERA_VFOV_DEG = 65f;
    /**
     * Do not lerp/smooth the horizon. The cameras already move with the head; a
     * lagged overlay fights the passthrough and causes nausea.
     */
    private static final int COMPASS_TAPE_DP = 28;
    private static final int HUD_RED = 0xFFFF4A3A;
    private static final long CLICK_MARK_MS = 1600L;
    /** White guide line. Off for the launcher; flip on to debug the heading lock. */
    private static final boolean SHOW_GUIDE = false;
    /** Red attitude bar. Off so the app grid is the only thing on that plane. */
    private static final boolean SHOW_HORIZON_BAR = false;
    private static final boolean SHOW_SCANLINES = false;
    private static final String[] APP_NAMES = {
            "HedgeyOS", "Cat", "Files", "Lizard", "Record", "Writer"
    };
    private static final int[] APP_TINT = {
            0xFFE8AFA4, 0xFFE07A3D, 0xFF6EC6FF, 0xFF3D9A5F, 0xFFE53935, 0xFF4A90D9
    };
    private static final int KIND_NONE = 0;
    private static final int KIND_WRITER = 1;
    private static final int KIND_ABOUT = 2;
    private static final int KIND_RECORD = 3;
    private static final int KIND_FILES = 4;
    private static final int[] APP_KIND = {
            KIND_ABOUT, KIND_NONE, KIND_FILES, KIND_NONE, KIND_RECORD, KIND_WRITER
    };
    private static final int APP_COLS = 3;
    private static final int APP_ROWS = 2;
    private static final int WINDOW_NONE = 0;
    private static final int WINDOW_MISSING = 1;
    private static final int WINDOW_WRITER = 2;
    private static final int WINDOW_ABOUT = 3;
    private static final int WINDOW_RECORD = 4;
    private static final int WINDOW_FILES = 5;
    private static final int FADE_NONE = 0;
    private static final int FADE_IN = 1;
    private static final int FADE_OUT = 2;
    private static final long FADE_MS = 320L;
    private static final String[] STORAGE_PERMS = {
            android.Manifest.permission.READ_EXTERNAL_STORAGE,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
    };
    private static final String[] RECORD_PERMS = {
            android.Manifest.permission.READ_EXTERNAL_STORAGE,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
            android.Manifest.permission.RECORD_AUDIO
    };
    private static final long NO_APP_MS = 2400L;
    private static final String RECORD_HELP =
            "Press a time, then Record. 16 fps into ~/Movies, with optional mic. "
            + "The window fades away while recording; reopen Record to stop.";
    private static final String LOREM =
            "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do "
            + "eiusmod tempor incididunt ut labore et dolore magna aliqua. "
            + "Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris.";
    private static final int DRAFT_MAX = 4000;
    private static final String NO_APP_MSG = "No such app found! closing...";
    private static final String ABOUT_TEXT =
            "HedgeyOS\n\n"
            + "Your pocket Debian desktop.\n\n"
            + "A storybook Linux workstation for ARM64 Android — Debian 13, "
            + "XFCE, and a hedgehog.\n\n"
            + "github.com/hedgeyos/hedgeyos";
    private final StringBuilder draft = new StringBuilder(LOREM);
    private final LauncherState launcher = new LauncherState();
    private final ScreenRecorder recorder = new ScreenRecorder(this);
    private FileBrowser files;
    private FileViewer viewer;
    private boolean recWasOn;
    private Bitmap hedgeyIcon;
    private final ArrayList<ClickMark> clickMarks = new ArrayList<ClickMark>();
    private float gazeYaw;
    private float gazePitch;
    private float lookHeading;
    private boolean haveLookHeading;
    private float guideYaw = Float.NaN;
    private boolean haveGuide;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat clockFmt = new SimpleDateFormat("HH:mm", Locale.US);
    private final Date clockDate = new Date();
    private final float[] rotVec = new float[5];
    private final float[] rotMat = new float[9];
    private final float[] orient = new float[3];
    private final float[] grav = new float[3];
    private volatile boolean haveRot;
    private volatile boolean haveGrav;
    private SensorManager sensors;
    private Sensor rotSensor;
    private Sensor gravSensor;
    private CameraBank cameras;
    private boolean camsStarted;
    private final boolean[] camOk = new boolean[2];
    private final Runnable stopCamDelayed = new Runnable() {
        @Override
        public void run() {
            stopCameras();
        }
    };
    private final TextView[] timeViews = new TextView[2];
    private final TextView[] batViews = new TextView[2];
    private final TextView[][] camChips = new TextView[2][2];
    private final CompassTape[] compasses = new CompassTape[2];
    private final Horizon[] horizons = new Horizon[2];
    private final Reticle[] reticles = new Reticle[2];
    private final ImageView[] feeds = new ImageView[2];
    private final Bitmap[] lastFrame = new Bitmap[2];
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            updateHud();
            ui.postDelayed(this, 120);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        files = new FileBrowser(this);
        viewer = new FileViewer(this);
        sensors = (SensorManager) getSystemService(SENSOR_SERVICE);
        rotSensor = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if (rotSensor == null) {
            rotSensor = sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        }
        gravSensor = sensors.getDefaultSensor(Sensor.TYPE_GRAVITY);
        if (gravSensor == null) {
            gravSensor = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        }
        hedgeyIcon = BitmapFactory.decodeResource(getResources(), R.drawable.hedgeyos_icon);
        recorder.attach(this);

        LinearLayout stereo = new LinearLayout(this);
        stereo.setBackgroundColor(Color.BLACK);
        stereo.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams eyeLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        stereo.addView(buildEye(0), eyeLp);
        stereo.addView(buildEye(1), eyeLp);
        FrameLayout shell = new FrameLayout(this);
        shell.setBackgroundColor(Color.BLACK);
        TextureView videoSink = new TextureView(this);
        shell.addView(videoSink, new FrameLayout.LayoutParams(1280, 640));
        shell.addView(stereo, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        viewer.attachSink(videoSink);
        setContentView(shell);
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        setCamChips("WAIT", "WAIT");
        KeepAliveReceiver.schedule(this);
    }

    private FrameLayout buildEye(int eye) {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        ImageView feed = new ImageView(this);
        feed.setBackgroundColor(Color.BLACK);
        feed.setScaleType(ImageView.ScaleType.MATRIX);
        final int eyeIndex = eye;
        feed.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b,
                    int ol, int ot, int or, int ob) {
                if (lastFrame[eyeIndex] != null && feeds[eyeIndex] != null) {
                    showStereoFrame(eyeIndex, feeds[eyeIndex], lastFrame[eyeIndex]);
                }
            }
        });
        root.addView(feed, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        feeds[eye] = feed;

        if (SHOW_SCANLINES) {
            root.addView(new Scanlines(this), new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
        }

        Horizon horizon = new Horizon(this, clickMarks, launcher, draft, hedgeyIcon, recorder,
                files, viewer);
        root.addView(horizon, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        horizons[eye] = horizon;

        Reticle reticle = new Reticle(this);
        root.addView(reticle, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        reticles[eye] = reticle;

        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        timeViews[eye] = hudLabel("00:00");
        topRow.addView(timeViews[eye]);
        CompassTape tape = new CompassTape(this);
        LinearLayout.LayoutParams tapeLp = new LinearLayout.LayoutParams(dp(200), dp(COMPASS_TAPE_DP));
        tapeLp.leftMargin = dp(10);
        tapeLp.rightMargin = dp(10);
        topRow.addView(tape, tapeLp);
        compasses[eye] = tape;
        batViews[eye] = hudLabel("BAT --");
        topRow.addView(batViews[eye]);
        FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        topLp.gravity = Gravity.CENTER;
        topLp.topMargin = -dp(54);
        root.addView(topRow, topLp);
        return root;
    }

    private TextView hudLabel(String initial) {
        TextView tv = new TextView(this);
        tv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tv.setTextColor(HUD_RED);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        tv.setShadowLayer(4f, 1f, 1f, 0xFF000000);
        tv.setIncludeFontPadding(false);
        tv.setText(initial);
        return tv;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (rotSensor != null) {
            sensors.registerListener(this, rotSensor, SensorManager.SENSOR_DELAY_FASTEST);
        }
        if (gravSensor != null) {
            sensors.registerListener(this, gravSensor, SensorManager.SENSOR_DELAY_FASTEST);
        }
        ui.removeCallbacks(stopCamDelayed);
        ui.removeCallbacks(refresh);
        ui.post(refresh);
        maybeStartCameras();
        if (viewer != null) viewer.resume();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            maybeStartCameras();
            grabKeyboard();
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int code = event.getKeyCode();
            if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN) {
                if (event.getRepeatCount() == 0) {
                    onGazeClick(code == KeyEvent.KEYCODE_VOLUME_UP);
                }
                return true;
            }
        }
        if (typeKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    private void grabKeyboard() {
        if (horizons[0] != null) {
            horizons[0].setFocusable(true);
            horizons[0].setFocusableInTouchMode(true);
            horizons[0].requestFocus();
        }
    }

    /**
     * Gaze mouse: the reticle is always the pointer (view center). Volume up is
     * primary click (open / close). Volume down snaps the hidden guide — and
     * therefore the grid and any open window — to the reticle heading.
     */
    protected void onGazeClick(boolean primary) {
        for (int i = 0; i < 2; i++) {
            if (reticles[i] != null) reticles[i].pulse(primary);
        }
        if (!primary) {
            snapGuide();
            return;
        }
        if (launcher.fade != FADE_NONE || launcher.viewerClosing) return;
        if (launcher.window != WINDOW_NONE) {
            if (launcher.hoverClose) {
                closeWindow();
                return;
            }
            if (launcher.window == WINDOW_RECORD) {
                if (launcher.hoverChip >= 0) {
                    recorder.setPreset(launcher.hoverChip);
                    invalidateHorizons();
                    return;
                }
                if (launcher.hoverMic) {
                    recorder.setMic(!recorder.mic());
                    invalidateHorizons();
                    return;
                }
                if (launcher.hoverRecord) {
                    maybeToggleRecord();
                    return;
                }
            }
            if (launcher.window == WINDOW_FILES) {
                if (viewer.isOpen()) {
                    if (launcher.hoverPreview && viewer.kind() == FileViewer.KIND_VIDEO) {
                        viewer.togglePlay();
                        invalidateHorizons();
                    }
                    return;
                }
                if (launcher.hoverFile >= 0) {
                    ArrayList<FileBrowser.Row> rows = files.rows();
                    if (launcher.hoverFile < rows.size()) {
                        FileBrowser.Row row = rows.get(launcher.hoverFile);
                        if (row.kind == FileBrowser.KIND_FILE) {
                            boolean was = viewer.isOpen() && !launcher.viewerClosing;
                            viewer.open(row.target);
                            launcher.viewerClosing = false;
                            launcher.viewerFadeAt = was
                                    ? SystemClock.uptimeMillis() - FADE_MS
                                    : SystemClock.uptimeMillis();
                        } else {
                            files.activate(launcher.hoverFile);
                        }
                    }
                    invalidateHorizons();
                    return;
                }
            }
            return;
        }
        if (launcher.hover >= 0 && launcher.hover < APP_NAMES.length) {
            openApp(launcher.hover);
        }
    }

    private void snapGuide() {
        float snap = headingNow(true);
        if (Float.isNaN(snap)) return;
        lookHeading = snap;
        haveLookHeading = true;
        guideYaw = snap;
        haveGuide = true;
        applyHorizon();
    }

    private void openApp(int index) {
        launcher.windowApp = index;
        launcher.windowAt = SystemClock.uptimeMillis();
        launcher.hoverClose = false;
        if (APP_KIND[index] == KIND_WRITER) {
            launcher.window = WINDOW_WRITER;
            grabKeyboard();
        } else if (APP_KIND[index] == KIND_ABOUT) {
            launcher.window = WINDOW_ABOUT;
        } else if (APP_KIND[index] == KIND_RECORD) {
            launcher.window = WINDOW_RECORD;
        } else if (APP_KIND[index] == KIND_FILES) {
            launcher.window = WINDOW_FILES;
            maybeOpenFiles();
        } else {
            launcher.window = WINDOW_MISSING;
        }
        launcher.fade = FADE_IN;
        launcher.fadeAt = SystemClock.uptimeMillis();
        invalidateHorizons();
    }

    private boolean hasStoragePerms() {
        return checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasMicPerm() {
        return checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void maybeToggleRecord() {
        if (!hasStoragePerms() || (recorder.mic() && !hasMicPerm())) {
            requestPermissions(recorder.mic() ? RECORD_PERMS : STORAGE_PERMS, 8);
            return;
        }
        recorder.toggle();
        invalidateHorizons();
    }

    private void maybeOpenFiles() {
        if (!hasStoragePerms()) {
            requestPermissions(STORAGE_PERMS, 9);
        }
        files.ensureListed();
        invalidateHorizons();
    }

    @Override
    public void onViewerChanged() {
        invalidateHorizons();
    }

    @Override
    public void onRecorderChanged() {
        boolean on = recorder.isRecording();
        if (on && !recWasOn && launcher.window == WINDOW_RECORD) {
            closeWindow();
        }
        recWasOn = on;
        invalidateHorizons();
    }

    private void closeWindow() {
        long now = SystemClock.uptimeMillis();
        if (viewer.isOpen() && !launcher.viewerClosing) {
            launcher.viewerClosing = true;
            launcher.viewerFadeAt = now;
            launcher.hoverClose = false;
            invalidateHorizons();
            return;
        }
        if (launcher.viewerClosing) return;
        if (launcher.window == WINDOW_NONE) return;
        if (launcher.fade == FADE_OUT) return;
        if (launcher.fade == FADE_IN) {
            float t = fadeProgress(launcher.fadeAt, now);
            launcher.fade = FADE_OUT;
            launcher.fadeAt = now - (long) ((1f - t) * FADE_MS);
        } else {
            launcher.fade = FADE_OUT;
            launcher.fadeAt = now;
        }
        launcher.hoverClose = false;
        invalidateHorizons();
    }

    private static float fadeProgress(long started, long now) {
        float t = (now - started) / (float) FADE_MS;
        if (t < 0f) t = 0f;
        if (t > 1f) t = 1f;
        return t * t * (3f - 2f * t);
    }

    private void invalidateHorizons() {
        for (int i = 0; i < 2; i++) {
            if (horizons[i] != null) horizons[i].invalidate();
        }
    }

    private boolean typeKey(KeyEvent event) {
        if (launcher.window != WINDOW_WRITER) return false;
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return false;
        }
        if (code == KeyEvent.KEYCODE_DEL) {
            if (draft.length() > 0) draft.deleteCharAt(draft.length() - 1);
            invalidateHorizons();
            return true;
        }
        if (code == KeyEvent.KEYCODE_FORWARD_DEL) return true;
        if (code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (draft.length() < DRAFT_MAX) draft.append('\n');
            invalidateHorizons();
            return true;
        }
        int u = event.getUnicodeChar();
        if (u == 0) return false;
        if (draft.length() >= DRAFT_MAX) return true;
        draft.append(Character.toChars(u));
        invalidateHorizons();
        return true;
    }

    private void maybeStartCameras() {
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.CAMERA}, 7);
            setCamChips("L DENY", "R DENY");
            return;
        }
        startCameras();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] grant) {
        if (code == 8) {
            if (hasStoragePerms()) {
                recorder.toggle();
                invalidateHorizons();
            }
            return;
        }
        if (code == 9) {
            files.ensureListed();
            invalidateHorizons();
            return;
        }
        if (code == 7 && grant.length > 0 && grant[0] == PackageManager.PERMISSION_GRANTED) {
            maybeStartCameras();
        } else if (code == 7) {
            setCamChips("L DENY", "R DENY");
        }
    }

    private void startCameras() {
        if (camsStarted) return;
        stopCameras();
        cameras = new CameraBank(this, this);
        cameras.start();
        camsStarted = true;
    }

    private void stopCameras() {
        camsStarted = false;
        if (cameras != null) {
            cameras.stop();
            cameras = null;
        }
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(refresh);
        ui.removeCallbacks(stopCamDelayed);
        sensors.unregisterListener(this);
        if (viewer != null) viewer.pause();
        ui.postDelayed(stopCamDelayed, 2500);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (viewer != null) viewer.release();
        recorder.release();
        super.onDestroy();
    }

    @Override
    public void onLabel(int index, String label) {
        if (index < 0 || index > 1) return;
        camOk[index] = label != null && label.contains(" LIVE");
        paintCamChips();
    }

    @Override
    public void onFrame(final int index, final Bitmap bitmap) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (index >= 0 && index < feeds.length && feeds[index] != null) {
                    lastFrame[index] = bitmap;
                    showStereoFrame(index, feeds[index], bitmap);
                }
                if (cameras != null) cameras.markFrameConsumed(index);
            }
        });
    }

    private void showStereoFrame(final int eye, final ImageView view, final Bitmap bmp) {
        int vw = view.getWidth();
        int vh = view.getHeight();
        if (vw <= 0 || vh <= 0) {
            view.setImageBitmap(bmp);
            view.post(new Runnable() {
                @Override
                public void run() {
                    if (view.getWidth() > 0) showStereoFrame(eye, view, bmp);
                }
            });
            return;
        }
        float scale = Math.min(vw / (float) bmp.getWidth(), vh / (float) bmp.getHeight());
        float dx = (vw - bmp.getWidth() * scale) / 2f;
        float dy = (vh - bmp.getHeight() * scale) / 2f;
        float hitX = MATCH_DX_FRAC * bmp.getWidth() * scale * 0.5f;
        float hitY = MATCH_DY_FRAC * bmp.getHeight() * scale * 0.5f;
        float drop = CAMERA_BELOW_FRAC * bmp.getHeight() * scale;
        dy += drop;
        if (eye == 0) {
            dx -= hitX;
            dy -= hitY;
        } else {
            dx += hitX;
            dy += hitY;
        }
        Matrix m = new Matrix();
        m.setScale(scale, scale);
        m.postTranslate(dx, dy);
        view.setImageMatrix(m);
        view.setImageBitmap(bmp);
    }

    private void setCamChips(String left, String right) {
        for (int eye = 0; eye < 2; eye++) {
            if (camChips[eye][0] != null) camChips[eye][0].setText(left);
            if (camChips[eye][1] != null) camChips[eye][1].setText(right);
        }
    }

    private void paintCamChips() {
        setCamChips(camOk[0] ? "L OK" : "L DROP", camOk[1] ? "R OK" : "R DROP");
    }

    private void updateHud() {
        clockDate.setTime(System.currentTimeMillis());
        String time = clockFmt.format(clockDate);
        String bat = batteryLine();
        float yaw = applyHorizon();
        for (int eye = 0; eye < 2; eye++) {
            if (timeViews[eye] != null) timeViews[eye].setText(time);
            if (batViews[eye] != null) batViews[eye].setText(bat);
            if (compasses[eye] != null) compasses[eye].setYaw(yaw);
        }
    }

    /** Push pitch/roll/yaw to the horizon immediately. Returns yaw for the compass tape. */
    private float applyHorizon() {
        float yaw = 0f;
        if (haveRot) {
            SensorManager.getRotationMatrixFromVector(rotMat, rotVec);
            SensorManager.getOrientation(rotMat, orient);
            yaw = (float) Math.toDegrees(orient[0]);
            if (yaw < 0) yaw += 360f;
        }
        float roll = 0f;
        float pitch = 0f;
        if (haveGrav) {
            // Rest: +Y is up, gx~0 → angle 0, a horizontal line. gx changes sign
            // through level so it does not snap vertical on the centerline.
            // Camera is on the head: a right-ear-down roll puts world-up at gx<0,
            // and the passthrough horizon therefore leans counter-clockwise.
            // canvas.rotate is clockwise for +angles, so use atan2(gx, gy) (not
            // -gx) to put the bar on that image horizon.
            float rollT = (float) Math.toDegrees(Math.atan2(grav[0], grav[1]));
            float n = (float) Math.sqrt(grav[0] * grav[0] + grav[1] * grav[1] + grav[2] * grav[2]);
            // Looking up: camera is on the head, so the real horizon drops in the
            // bitmap. asin(gz/n) moves the other way on this HMD; negate it.
            float pitchT = n > 0.2f ? (float) -Math.toDegrees(Math.asin(Math.max(-1f, Math.min(1f, grav[2] / n)))) : 0f;
            roll = rollT;
            pitch = pitchT;
        }
        gazeYaw = yaw;
        gazePitch = pitch;
        float heading = headingNow(false);
        if (!Float.isNaN(heading)) {
            lookHeading = heading;
            haveLookHeading = true;
            if (!haveGuide) {
                guideYaw = lookHeading;
                haveGuide = true;
            }
        }
        for (int eye = 0; eye < 2; eye++) {
            if (horizons[eye] != null) {
                horizons[eye].setAttitude(pitch, roll, yaw, lookHeading, guideYaw);
            }
        }
        return yaw;
    }

    /**
     * Compass heading of visor forward (device -Z) from the rotation matrix,
     * not Euler azimuth. Project onto world east/north so nod/roll do not pan
     * the guide. Reject samples that are too close to vertical unless
     * {@code allowWeak} (used for a click snap).
     */
    private float headingNow(boolean allowWeak) {
        if (!haveRot) return Float.NaN;
        float east = -rotMat[2];
        float north = -rotMat[5];
        float mag2 = east * east + north * north;
        if (mag2 < 1e-8f) return Float.NaN;
        if (!allowWeak && mag2 < 0.04f) return Float.NaN;
        float deg = (float) Math.toDegrees(Math.atan2(east, north));
        if (deg < 0f) deg += 360f;
        return deg;
    }

    private static float wrapDeg(float d) {
        while (d > 180f) d -= 360f;
        while (d < -180f) d += 360f;
        return d;
    }

    private String batteryLine() {
        BatteryManager bat = (BatteryManager) getSystemService(Context.BATTERY_SERVICE);
        int pct = bat != null ? bat.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) : -1;
        if (pct < 0) return "BAT --";
        return "BAT " + pct + "%";
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_GRAVITY
                || event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            System.arraycopy(event.values, 0, grav, 0, 3);
            haveGrav = true;
            applyHorizon();
            return;
        }
        int n = Math.min(rotVec.length, event.values.length);
        System.arraycopy(event.values, 0, rotVec, 0, n);
        haveRot = true;
        applyHorizon();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private static final class CompassTape extends View {
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float yaw;

        CompassTape(Context context) {
            super(context);
            line.setColor(HUD_RED);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(2f);
            text.setColor(HUD_RED);
            text.setTypeface(Typeface.MONOSPACE);
            text.setTextAlign(Paint.Align.CENTER);
            text.setShadowLayer(3f, 1f, 1f, 0xFF000000);
            setWillNotDraw(false);
        }

        void setYaw(float deg) {
            yaw = deg;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;
            text.setTextSize(h * 0.42f);
            float cx = w / 2f;
            float midY = h * 0.62f;
            canvas.drawLine(0, midY, w, midY, line);
            canvas.drawLine(cx, 2, cx, h * 0.28f, line);
            float pxPerDeg = w / 180f;
            int start = (int) Math.floor((yaw - 90f) / 15f) * 15;
            for (int deg = start; deg <= yaw + 90f; deg += 15) {
                float x = cx + (deg - yaw) * pxPerDeg;
                if (x < 4 || x > w - 4) continue;
                int wrap = ((deg % 360) + 360) % 360;
                boolean cardinal = wrap % 90 == 0;
                float tick = cardinal ? h * 0.38f : h * 0.22f;
                canvas.drawLine(x, midY - tick, x, midY + 3, line);
                if (cardinal) {
                    String lab = wrap == 0 ? "N" : wrap == 90 ? "E" : wrap == 180 ? "S" : "W";
                    canvas.drawText(lab, x, midY - tick - 4, text);
                }
            }
        }
    }

    static float pitchToDy(float pitchDeg, float viewMin) {
        float imgHalf = viewMin * CAM_PREVIEW_ASPECT * 0.5f;
        float lim = CAMERA_VFOV_DEG * 0.45f;
        float p = pitchDeg;
        if (p > lim) p = lim;
        if (p < -lim) p = -lim;
        float den = (float) Math.tan(Math.toRadians(CAMERA_VFOV_DEG * 0.5f));
        if (den <= 0.01f) return 0f;
        return (float) (Math.tan(Math.toRadians(p)) / den * imgHalf);
    }

    private static final class LauncherState {
        int hover = -1;
        boolean hoverClose;
        int hoverChip = -1;
        boolean hoverRecord;
        boolean hoverMic;
        int hoverFile = -1;
        boolean hoverPreview;
        int window = WINDOW_NONE;
        int windowApp;
        long windowAt;
        int fade;
        long fadeAt;
        boolean viewerClosing;
        long viewerFadeAt;
    }

    private static final class ClickMark {
        float pitch;
        int sides;
        float spin;
        float radius;
        boolean primary;
        long born;
    }

    private static final class Horizon extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint markFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint markStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint guidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint guideText = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint iconGlass = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint iconRing = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint iconLabel = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint winFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint winStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint winTitleFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint closeFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint closeXPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint caret = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint body = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF winBox = new RectF();
        private final RectF titleBar = new RectF();
        private final Path glyphPath = new Path();
        private final RectF oval = new RectF();
        private final float[] mapped = new float[2];
        private final Path markPath = new Path();
        private final ArrayList<ClickMark> marks;
        private final LauncherState state;
        private final StringBuilder draft;
        private final Bitmap hedgeyIcon;
        private final ScreenRecorder recorder;
        private final FileBrowser files;
        private final FileViewer viewer;
        private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Path iconClip = new Path();
        private final RectF iconDest = new RectF();
        private final RectF chipRect = new RectF();
        private final Paint chipFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint recDot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float pitch;
        private float roll;
        private float yaw;
        private float heading;
        private float guideYaw = Float.NaN;

        Horizon(Context context, ArrayList<ClickMark> marks, LauncherState state,
                StringBuilder draft, Bitmap hedgeyIcon, ScreenRecorder recorder,
                FileBrowser files, FileViewer viewer) {
            super(context);
            this.marks = marks;
            this.state = state;
            this.draft = draft;
            this.hedgeyIcon = hedgeyIcon;
            this.recorder = recorder;
            this.files = files;
            this.viewer = viewer;
            paint.setColor(0xAAFF2A22);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.2f);
            float compassSize = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, COMPASS_TAPE_DP,
                    context.getResources().getDisplayMetrics()) * 0.42f;
            text.setColor(HUD_RED);
            text.setTypeface(Typeface.MONOSPACE);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTextSize(compassSize * 0.5f);
            text.setShadowLayer(3f, 1f, 1f, 0xFF000000);
            guidePaint.setColor(0xFFFFFFFF);
            guidePaint.setStyle(Paint.Style.STROKE);
            guidePaint.setStrokeWidth(2.6f);
            guideText.setColor(0xFFFFFFFF);
            guideText.setTypeface(Typeface.MONOSPACE);
            guideText.setTextAlign(Paint.Align.CENTER);
            guideText.setTextSize(compassSize * 0.5f);
            guideText.setShadowLayer(3f, 1f, 1f, 0xFF000000);
            iconGlass.setStyle(Paint.Style.FILL);
            iconRing.setStyle(Paint.Style.STROKE);
            glyph.setStrokeCap(Paint.Cap.ROUND);
            glyph.setStrokeJoin(Paint.Join.ROUND);
            iconLabel.setColor(0xFFFFFFFF);
            iconLabel.setTypeface(Typeface.SANS_SERIF);
            iconLabel.setTextAlign(Paint.Align.CENTER);
            iconLabel.setShadowLayer(4f, 0f, 1f, 0xCC000000);
            winFill.setColor(0x80141418);
            winFill.setStyle(Paint.Style.FILL);
            winStroke.setColor(0x66FFFFFF);
            winStroke.setStyle(Paint.Style.STROKE);
            winStroke.setStrokeWidth(2f);
            winTitleFill.setColor(0x33FFFFFF);
            winTitleFill.setStyle(Paint.Style.FILL);
            closeFill.setStyle(Paint.Style.FILL);
            closeXPaint.setColor(0xFFFFFFFF);
            closeXPaint.setStyle(Paint.Style.STROKE);
            closeXPaint.setStrokeCap(Paint.Cap.ROUND);
            titlePaint.setColor(0xFFFFFFFF);
            titlePaint.setTypeface(Typeface.SANS_SERIF);
            titlePaint.setTextAlign(Paint.Align.CENTER);
            caret.setColor(0xFFE8E8EA);
            caret.setStrokeWidth(2f);
            body.setColor(0xFFE8E8EA);
            body.setTypeface(Typeface.SANS_SERIF);
            body.setTextSize(compassSize * 0.55f);
            body.setAntiAlias(true);
            body.setShadowLayer(3f, 0f, 1f, 0xCC000000);
            titlePaint.setShadowLayer(3f, 0f, 1f, 0xCC000000);
            markFill.setStyle(Paint.Style.FILL);
            markStroke.setStyle(Paint.Style.STROKE);
            markStroke.setStrokeWidth(2.4f);
            recDot.setStyle(Paint.Style.FILL);
            recDot.setColor(0xFFE53935);
            setWillNotDraw(false);
            setClickable(true);
            setFocusable(true);
            setFocusableInTouchMode(true);
        }

        void setAttitude(float pitchDeg, float rollDeg, float yawDeg,
                float lookHeadingDeg, float guideYawDeg) {
            pitch = pitchDeg;
            roll = rollDeg;
            yaw = yawDeg;
            heading = lookHeadingDeg;
            guideYaw = guideYawDeg;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            float cx = w / 2f;
            float cy = h / 2f;
            float arm = Math.min(w, h) * 0.22f;
            float dy = pitchToDy(pitch, Math.min(w, h));
            canvas.save();
            canvas.rotate(roll, cx, cy);
            canvas.translate(0, dy);
            if (SHOW_HORIZON_BAR) {
                canvas.drawLine(cx - arm, cy, cx - 18, cy, paint);
                canvas.drawLine(cx + 18, cy, cx + arm, cy, paint);
                canvas.drawLine(cx - arm, cy - 6, cx - arm, cy + 6, paint);
                canvas.drawLine(cx + arm, cy - 6, cx + arm, cy + 6, paint);
                String label = String.format(Locale.US, "P%+.1f  R%+.1f  Y%.0f", pitch, roll, yaw);
                canvas.drawText(label, cx, cy - 6 - text.descent(), text);
            }
            if (SHOW_GUIDE) drawGuide(canvas, w, cx, cy);
            tickFade();
            if (state.window == WINDOW_MISSING
                    && state.fade != FADE_OUT
                    && SystemClock.uptimeMillis() - state.windowAt >= NO_APP_MS) {
                state.fade = FADE_OUT;
                state.fadeAt = SystemClock.uptimeMillis();
            }
            long now = SystemClock.uptimeMillis();
            float t = fadeProgress(state.fadeAt, now);
            float gridA;
            float winA;
            if (state.fade == FADE_IN) {
                gridA = 1f - t;
                winA = t;
            } else if (state.fade == FADE_OUT) {
                winA = 1f - t;
                gridA = t;
            } else if (state.window != WINDOW_NONE) {
                winA = 1f;
                gridA = 0f;
            } else {
                gridA = 1f;
                winA = 0f;
            }
            boolean live = state.fade == FADE_NONE;
            if (gridA > 0.02f) {
                canvas.saveLayerAlpha(null, (int) (gridA * 255f));
                drawAppGrid(canvas, w, h, cx, cy, dy);
                if (!live) state.hover = -1;
                canvas.restore();
            } else {
                state.hover = -1;
            }
            if (winA > 0.02f && state.window != WINDOW_NONE) {
                canvas.saveLayerAlpha(null, (int) (winA * 255f));
                drawWindow(canvas, w, h, cx, cy, dy);
                if (!live) {
                    state.hoverClose = false;
                    state.hoverChip = -1;
                    state.hoverRecord = false;
                    state.hoverMic = false;
                    state.hoverFile = -1;
                    state.hoverPreview = false;
                }
                canvas.restore();
            }
            if (state.fade != FADE_NONE || viewerFading()) {
                postInvalidateDelayed(16);
            }
            if (SHOW_GUIDE) drawClickMarks(canvas, cx, cy);
            canvas.restore();
            if (recorder.isRecording()) {
                recDot.setColor(0xFFE53935);
                float badge = Math.min(w, h) * 0.018f;
                canvas.drawCircle(badge * 2.2f, badge * 2.2f, badge, recDot);
                iconLabel.setTextSize(badge * 1.3f);
                iconLabel.setTextAlign(Paint.Align.LEFT);
                iconLabel.setColor(0xFFFFE0E0);
                canvas.drawText("REC", badge * 3.6f, badge * 2.2f + iconLabel.getTextSize() * 0.35f,
                        iconLabel);
                iconLabel.setTextAlign(Paint.Align.CENTER);
                postInvalidateDelayed(200);
            }
        }

        private void tickFade() {
            long now = SystemClock.uptimeMillis();
            if (state.fade != FADE_NONE && now - state.fadeAt >= FADE_MS) {
                if (state.fade == FADE_OUT) {
                    state.window = WINDOW_NONE;
                    state.hoverClose = false;
                    state.hoverFile = -1;
                    state.hoverPreview = false;
                }
                state.fade = FADE_NONE;
            }
            if (state.viewerClosing && now - state.viewerFadeAt >= FADE_MS) {
                viewer.close();
                state.viewerClosing = false;
                state.hoverClose = false;
                state.hoverPreview = false;
            }
        }

        private boolean viewerFading() {
            if (state.viewerClosing) return true;
            if (!viewer.isOpen()) return false;
            return SystemClock.uptimeMillis() - state.viewerFadeAt < FADE_MS;
        }

        private float guideLocalX(float cx, int w) {
            if (Float.isNaN(guideYaw)) return Float.NaN;
            float hfov = CAMERA_VFOV_DEG / CAM_PREVIEW_ASPECT;
            return cx + wrapDeg(guideYaw - heading) * (w / hfov);
        }

        private void drawGuide(Canvas canvas, int w, float cx, float cy) {
            float x = guideLocalX(cx, w);
            if (Float.isNaN(x)) return;
            float arm = Math.min(w, getHeight()) * 0.30f;
            if (x + arm < 0 || x - arm > w) return;
            canvas.drawLine(x - arm, cy, x - 16, cy, guidePaint);
            canvas.drawLine(x + 16, cy, x + arm, cy, guidePaint);
            canvas.drawLine(x - arm, cy - 7, x - arm, cy + 7, guidePaint);
            canvas.drawLine(x + arm, cy - 7, x + arm, cy + 7, guidePaint);
            canvas.drawCircle(x, cy, 5f, guidePaint);
            canvas.drawText(
                    String.format(Locale.US, "GUIDE %.0f", guideYaw),
                    x, cy + 18 + guideText.getTextSize(), guideText);
        }

        private void drawAppGrid(Canvas canvas, int w, int h, float cx, float cy, float dy) {
            float gx = guideLocalX(cx, w);
            if (Float.isNaN(gx)) {
                state.hover = -1;
                return;
            }
            float min = Math.min(w, h);
            float r = min * 0.046f;
            float gapX = r * 2.75f;
            float gapY = r * 3.2f;
            float gridW = gapX * (APP_COLS - 1);
            float gridH = gapY * (APP_ROWS - 1);
            if (gx + gridW * 0.5f + r * 2f < 0 || gx - gridW * 0.5f - r * 2f > w) {
                state.hover = -1;
                return;
            }
            float originX = gx - gridW * 0.5f;
            float originY = cy - gridH * 0.5f;
            iconLabel.setTextSize(r * 0.42f);
            int hover = -1;
            float best = r * 1.55f;
            int n = APP_NAMES.length;
            for (int i = 0; i < n; i++) {
                int col = i % APP_COLS;
                int row = i / APP_COLS;
                float ix = originX + col * gapX;
                float iy = originY + row * gapY;
                mapToView(ix, iy, cx, cy, dy, roll, mapped);
                float dist = (float) Math.hypot(mapped[0] - cx, mapped[1] - cy);
                if (dist < best) {
                    best = dist;
                    hover = i;
                }
            }
            state.hover = hover;
            for (int i = 0; i < n; i++) {
                int col = i % APP_COLS;
                int row = i / APP_COLS;
                float ix = originX + col * gapX;
                float iy = originY + row * gapY;
                boolean hot = i == hover;
                float ir = r * (hot ? 1.12f : 1f);
                if (i == 0 && hedgeyIcon != null) {
                    drawHedgeyIcon(canvas, ix, iy, ir);
                } else {
                    iconGlass.setColor(hot ? 0xCCFFFFFF : 0x99FFFFFF);
                    canvas.drawCircle(ix, iy, ir, iconGlass);
                    glyph.setColor(APP_TINT[i]);
                    drawGlyph(canvas, i, ix, iy, ir * 0.62f);
                }
                iconRing.setStrokeWidth(hot ? 3.2f : 2.0f);
                iconRing.setColor(hot ? 0xFFFFFFFF : 0x66FFFFFF);
                canvas.drawCircle(ix, iy, ir, iconRing);
                iconLabel.setColor(hot ? 0xFFFFFFFF : 0xEEFFFFFF);
                canvas.drawText(APP_NAMES[i], ix, iy + ir + iconLabel.getTextSize() * 1.15f, iconLabel);
            }
        }

        private void drawWindow(Canvas canvas, int w, int h, float cx, float cy, float dy) {
            float gx = guideLocalX(cx, w);
            state.hoverClose = false;
            state.hoverChip = -1;
            state.hoverRecord = false;
            state.hoverMic = false;
            state.hoverFile = -1;
            state.hoverPreview = false;
            if (Float.isNaN(gx)) return;
            float min = Math.min(w, h);
            boolean writer = state.window == WINDOW_WRITER;
            boolean about = state.window == WINDOW_ABOUT;
            boolean record = state.window == WINDOW_RECORD;
            boolean fileWin = state.window == WINDOW_FILES;
            float boxW = min * (fileWin ? 0.66f : writer || about || record ? 0.62f : 0.52f);
            float boxH = min * (fileWin ? 0.56f : record ? 0.54f : writer ? 0.38f : about ? 0.44f : 0.24f);
            float titleH = min * 0.048f;
            float left = gx - boxW * 0.5f;
            float top = cy - boxH * 0.5f;
            winBox.set(left, top, left + boxW, top + boxH);
            if (winBox.right < -20 || winBox.left > w + 20) return;
            boolean viewing = fileWin && viewer.isOpen();
            int app = state.windowApp;
            if (app < 0 || app >= APP_NAMES.length) app = 0;
            String title = writer ? "Writer" : about ? "About" : record ? "Record"
                    : fileWin ? "Files" : APP_NAMES[app];
            paintChrome(canvas, gx, cx, cy, dy, titleH, title, !viewing);

            float pad = titleH * 0.35f;
            float contentLeft = winBox.left + pad;
            float contentTop = winBox.top + titleH + pad;
            float contentRight = winBox.right - pad;
            float contentBot = winBox.bottom - pad;
            if (record) {
                drawRecordPanel(canvas, cx, gx, cy, dy, contentLeft, contentTop, contentRight,
                        contentBot, titleH, pad);
                if (recorder.isRecording()) postInvalidateDelayed(200);
                return;
            }
            if (fileWin) {
                drawFilesPanel(canvas, cx, cy, dy, contentLeft, contentTop, contentRight,
                        contentBot, titleH);
                float va = 0f;
                long now = SystemClock.uptimeMillis();
                if (state.viewerClosing) {
                    va = 1f - fadeProgress(state.viewerFadeAt, now);
                } else if (viewer.isOpen()) {
                    va = fadeProgress(state.viewerFadeAt, now);
                }
                if (va > 0.02f) {
                    canvas.saveLayerAlpha(null, (int) (va * 255f));
                    drawViewerWindow(canvas, w, h, gx, cx, cy, dy, min, titleH);
                    canvas.restore();
                    if (viewer.kind() == FileViewer.KIND_VIDEO) postInvalidateDelayed(70);
                }
                if (va > 0.15f) state.hoverFile = -1;
                if (state.viewerClosing) state.hoverPreview = false;
                return;
            }
            if (about && hedgeyIcon != null) {
                float pic = Math.min(titleH * 2.2f, (contentBot - contentTop) * 0.28f);
                float cyPic = contentTop + pic;
                drawHedgeyIcon(canvas, gx, cyPic, pic);
                contentTop = cyPic + pic + pad * 0.5f;
            }
            int inner = Math.max(8, (int) (contentRight - contentLeft));
            CharSequence src = writer ? draft : about ? ABOUT_TEXT : NO_APP_MSG;
            body.setTextAlign(Paint.Align.LEFT);
            StaticLayout layout = StaticLayout.Builder
                    .obtain(src, 0, src.length(), body, inner)
                    .setAlignment(writer ? Layout.Alignment.ALIGN_NORMAL
                            : Layout.Alignment.ALIGN_CENTER)
                    .setIncludePad(false)
                    .build();
            canvas.save();
            canvas.clipRect(contentLeft, contentTop, contentRight, contentBot);
            float textY = contentTop;
            if (!writer) {
                float blockH = layout.getHeight();
                textY = contentTop + Math.max(0f, (contentBot - contentTop - blockH) * 0.15f);
            }
            canvas.translate(contentLeft, textY);
            layout.draw(canvas);
            if (writer && (SystemClock.uptimeMillis() / 450L) % 2L == 0L) {
                int last = Math.max(0, layout.getLineCount() - 1);
                float cxCaret = layout.getLineRight(last);
                canvas.drawLine(cxCaret + 1f, layout.getLineTop(last),
                        cxCaret + 1f, layout.getLineBottom(last), caret);
            }
            canvas.restore();
            if (writer) postInvalidateDelayed(50);
        }

        private void paintChrome(Canvas canvas, float gx, float cx, float cy, float dy,
                float titleH, String title, boolean hitTestClose) {
            float rad = Math.min(16f, titleH * 0.45f);
            canvas.drawRoundRect(winBox, rad, rad, winFill);
            canvas.drawRoundRect(winBox, rad, rad, winStroke);
            titleBar.set(winBox.left, winBox.top, winBox.right, winBox.top + titleH);
            canvas.drawRoundRect(titleBar, rad, rad, winTitleFill);
            canvas.drawRect(winBox.left, winBox.top + titleH * 0.4f, winBox.right,
                    winBox.top + titleH, winTitleFill);

            float closeX = winBox.left + titleH * 0.55f;
            float closeY = winBox.top + titleH * 0.5f;
            float closeR = titleH * 0.28f;
            boolean hot = false;
            if (hitTestClose) {
                mapToView(closeX, closeY, cx, cy, dy, roll, mapped);
                hot = Math.hypot(mapped[0] - cx, mapped[1] - cy) < closeR * 3.2f;
                state.hoverClose = hot;
            }
            closeFill.setColor(hot ? 0xFFFF7A73 : 0xFFE85D55);
            canvas.drawCircle(closeX, closeY, closeR, closeFill);
            closeXPaint.setStrokeWidth(Math.max(1.6f, closeR * 0.28f));
            float xarm = closeR * 0.38f;
            canvas.drawLine(closeX - xarm, closeY - xarm, closeX + xarm, closeY + xarm, closeXPaint);
            canvas.drawLine(closeX - xarm, closeY + xarm, closeX + xarm, closeY - xarm, closeXPaint);

            titlePaint.setTextSize(titleH * 0.42f);
            titlePaint.setColor(0xFFFFFFFF);
            float titleBase = winBox.top + (titleH - titlePaint.ascent() - titlePaint.descent()) / 2f;
            canvas.drawText(title, gx, titleBase, titlePaint);
        }

        private void drawViewerWindow(Canvas canvas, int w, int h, float gx, float cx, float cy,
                float dy, float min, float titleH) {
            float boxW = min * 0.70f;
            float boxH = min * 0.58f;
            float left = gx - boxW * 0.5f + min * 0.02f;
            float top = cy - boxH * 0.5f - min * 0.02f;
            winBox.set(left, top, left + boxW, top + boxH);
            if (winBox.right < -20 || winBox.left > w + 20) return;
            String title = FileBrowser.ellipsize(viewer.title(), 28);
            paintChrome(canvas, winBox.centerX(), cx, cy, dy, titleH, title, true);
            float pad = titleH * 0.35f;
            float contentLeft = winBox.left + pad;
            float contentTop = winBox.top + titleH + pad;
            float contentRight = winBox.right - pad;
            float contentBot = winBox.bottom - pad;
            int kind = viewer.kind();
            if (kind == FileViewer.KIND_IMAGE || kind == FileViewer.KIND_VIDEO) {
                iconDest.set(contentLeft, contentTop, contentRight, contentBot);
                viewer.drawMedia(canvas, iconDest, bitmapPaint);
                float rx = cx;
                float ry = cy - dy;
                state.hoverPreview = iconDest.contains(rx, ry);
                if (kind == FileViewer.KIND_VIDEO && !viewer.isPlaying()) {
                    float s = Math.min(iconDest.width(), iconDest.height()) * 0.12f;
                    float px = iconDest.centerX();
                    float py = iconDest.centerY();
                    glyphPath.reset();
                    glyphPath.moveTo(px - s * 0.45f, py - s);
                    glyphPath.lineTo(px - s * 0.45f, py + s);
                    glyphPath.lineTo(px + s, py);
                    glyphPath.close();
                    chipFill.setStyle(Paint.Style.FILL);
                    chipFill.setColor(state.hoverPreview ? 0xEEFFFFFF : 0xCCFFFFFF);
                    canvas.drawPath(glyphPath, chipFill);
                }
                if (viewer.error().length() > 0) {
                    body.setTextAlign(Paint.Align.CENTER);
                    body.setColor(0xFFFFE08A);
                    body.setTextSize(titleH * 0.36f);
                    canvas.drawText(viewer.error(), winBox.centerX(),
                            contentTop + body.getTextSize(), body);
                    body.setTextAlign(Paint.Align.LEFT);
                    body.setColor(0xFFE8E8EA);
                }
                return;
            }
            String src = viewer.error().length() > 0 ? viewer.error() : viewer.text();
            if (src == null) src = "";
            int inner = Math.max(8, (int) (contentRight - contentLeft));
            body.setTextAlign(Paint.Align.LEFT);
            body.setColor(0xFFE8E8EA);
            body.setTextSize(titleH * 0.34f);
            StaticLayout layout = StaticLayout.Builder
                    .obtain(src, 0, src.length(), body, inner)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setIncludePad(false)
                    .build();
            float ry = cy - dy;
            float scroll = 0f;
            float extra = layout.getHeight() - (contentBot - contentTop);
            if (extra > 0f && contentTop <= ry && ry <= contentBot) {
                float t = (ry - contentTop) / Math.max(1f, contentBot - contentTop);
                if (t < 0f) t = 0f;
                if (t > 1f) t = 1f;
                scroll = t * extra;
            }
            canvas.save();
            canvas.clipRect(contentLeft, contentTop, contentRight, contentBot);
            canvas.translate(contentLeft, contentTop - scroll);
            layout.draw(canvas);
            canvas.restore();
        }

        private void drawFilesPanel(Canvas canvas, float cx, float cy, float dy,
                float left, float top, float right, float bot, float titleH) {
            float rx = cx;
            float ry = cy - dy;
            files.ensureListed();
            body.setTextAlign(Paint.Align.LEFT);
            body.setTextSize(titleH * 0.32f);
            body.setColor(0xFFAAAAAA);
            String path = FileBrowser.ellipsize(files.pathLabel(), 42);
            if (files.error().length() > 0) {
                path = path + "  (" + files.error() + ")";
            }
            canvas.drawText(path, left, top + body.getTextSize(), body);
            float listTop = top + body.getTextSize() + titleH * 0.28f;
            float lineH = titleH * 0.70f;
            int visible = Math.max(1, (int) ((bot - listTop) / lineH));
            ArrayList<FileBrowser.Row> rows = files.rows();
            int n = rows.size();
            int hover = -1;
            int start = 0;
            if (listTop <= ry && ry < listTop + visible * lineH && n > 0) {
                if (n <= visible) {
                    hover = (int) ((ry - listTop) / lineH);
                    if (hover < 0 || hover >= n) hover = -1;
                } else {
                    float t = (ry - listTop) / (visible * lineH);
                    if (t < 0f) t = 0f;
                    if (t > 0.999f) t = 0.999f;
                    hover = (int) (t * n);
                    start = hover - visible / 2;
                    int maxStart = n - visible;
                    if (start < 0) start = 0;
                    if (start > maxStart) start = maxStart;
                }
            }
            state.hoverFile = hover;
            body.setTextSize(titleH * 0.36f);
            canvas.save();
            canvas.clipRect(left, listTop, right, bot);
            for (int i = 0; i < visible && start + i < n; i++) {
                int idx = start + i;
                FileBrowser.Row row = rows.get(idx);
                float y0 = listTop + i * lineH;
                boolean hot = idx == hover;
                if (hot) {
                    chipRect.set(left, y0, right, y0 + lineH);
                    chipFill.setStyle(Paint.Style.FILL);
                    chipFill.setColor(0x33FFFFFF);
                    canvas.drawRoundRect(chipRect, 4f, 4f, chipFill);
                }
                int color;
                if (row.kind == FileBrowser.KIND_HOME || row.kind == FileBrowser.KIND_UP) {
                    color = hot ? 0xFFFFE08A : 0xFFE0B85A;
                } else if (row.kind == FileBrowser.KIND_DIR) {
                    color = hot ? 0xFFB8E0FF : 0xFF6EC6FF;
                } else {
                    color = hot ? 0xFFFFFFFF : 0xFFCCCCCC;
                }
                body.setColor(color);
                float base = y0 + (lineH - body.ascent() - body.descent()) / 2f;
                canvas.drawText(row.name, left + 6f, base, body);
            }
            canvas.restore();
            body.setColor(0xFFE8E8EA);
            titlePaint.setColor(0xFFFFFFFF);
        }

        private void drawRecordPanel(Canvas canvas, float cx, float gx, float cy, float dy,
                float left, float top, float right, float bot, float titleH, float pad) {
            float rx = cx;
            float ry = cy - dy;
            int inner = Math.max(8, (int) (right - left));
            body.setTextSize(titleH * 0.38f);
            StaticLayout help = StaticLayout.Builder
                    .obtain(RECORD_HELP, 0, RECORD_HELP.length(), body, inner)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setIncludePad(false)
                    .build();
            canvas.save();
            canvas.translate(left, top);
            help.draw(canvas);
            canvas.restore();
            float y = top + help.getHeight() + pad * 0.8f;
            float chipH = titleH * 0.95f;
            float gap = pad * 0.45f;
            int n = ScreenRecorder.PRESET_LABELS.length;
            float chipW = (right - left - gap * (n - 1)) / n;
            int hoverChip = -1;
            for (int i = 0; i < n; i++) {
                float x0 = left + i * (chipW + gap);
                chipRect.set(x0, y, x0 + chipW, y + chipH);
                boolean on = recorder.preset() == i;
                boolean hot = chipRect.contains(rx, ry);
                if (hot) hoverChip = i;
                chipFill.setStyle(Paint.Style.FILL);
                chipFill.setColor(on ? 0xCCFFFFFF : hot ? 0x44FFFFFF : 0x22FFFFFF);
                canvas.drawRoundRect(chipRect, 8f, 8f, chipFill);
                chipFill.setStyle(Paint.Style.STROKE);
                chipFill.setStrokeWidth(on ? 2.6f : 1.6f);
                chipFill.setColor(on || hot ? 0xFFFFFFFF : 0x66FFFFFF);
                canvas.drawRoundRect(chipRect, 8f, 8f, chipFill);
                titlePaint.setTextSize(chipH * 0.42f);
                titlePaint.setColor(on ? 0xFF111111 : 0xFFFFFFFF);
                float base = chipRect.centerY() - (titlePaint.ascent() + titlePaint.descent()) / 2f;
                canvas.drawText(ScreenRecorder.PRESET_LABELS[i], chipRect.centerX(), base, titlePaint);
            }
            state.hoverChip = hoverChip;
            titlePaint.setColor(0xFFFFFFFF);
            y += chipH + pad * 0.7f;
            float micW = Math.min((right - left) * 0.34f, chipW * 1.6f);
            chipRect.set(gx - micW * 0.5f, y, gx + micW * 0.5f, y + chipH);
            boolean micOn = recorder.mic();
            boolean micHot = chipRect.contains(rx, ry);
            state.hoverMic = micHot && hoverChip < 0;
            chipFill.setStyle(Paint.Style.FILL);
            chipFill.setColor(micOn ? 0xCCFFFFFF : micHot ? 0x44FFFFFF : 0x22FFFFFF);
            canvas.drawRoundRect(chipRect, 8f, 8f, chipFill);
            chipFill.setStyle(Paint.Style.STROKE);
            chipFill.setStrokeWidth(micOn ? 2.6f : 1.6f);
            chipFill.setColor(micOn || micHot ? 0xFFFFFFFF : 0x66FFFFFF);
            canvas.drawRoundRect(chipRect, 8f, 8f, chipFill);
            titlePaint.setTextSize(chipH * 0.40f);
            titlePaint.setColor(micOn ? 0xFF111111 : 0xFFFFFFFF);
            float micBase = chipRect.centerY() - (titlePaint.ascent() + titlePaint.descent()) / 2f;
            canvas.drawText(micOn ? "Mic on" : "Muted", chipRect.centerX(), micBase, titlePaint);
            titlePaint.setColor(0xFFFFFFFF);
            y += chipH + pad * 1.0f;
            float recR = titleH * 0.85f;
            float recY = Math.min(y + recR, bot - recR - titleH);
            boolean recHot = Math.hypot(rx - gx, ry - recY) < recR * 1.35f;
            state.hoverRecord = recHot && hoverChip < 0 && !state.hoverMic;
            boolean recording = recorder.isRecording();
            recDot.setColor(recording ? (recHot ? 0xFFFFC107 : 0xFFFFB300) : (recHot ? 0xFFFF6B63 : 0xFFE53935));
            canvas.drawCircle(gx, recY, recR, recDot);
            if (recording) {
                chipFill.setColor(0xFF111111);
                chipFill.setStyle(Paint.Style.FILL);
                float s = recR * 0.38f;
                canvas.drawRect(gx - s, recY - s, gx + s, recY + s, chipFill);
            } else {
                recDot.setColor(0xFFFFFFFF);
                canvas.drawCircle(gx, recY, recR * 0.38f, recDot);
            }
            titlePaint.setTextSize(titleH * 0.36f);
            canvas.drawText(recording ? "Stop" : "Record", gx, recY + recR + titleH * 0.55f, titlePaint);
            body.setTextSize(titleH * 0.32f);
            String st = recorder.status();
            StaticLayout status = StaticLayout.Builder
                    .obtain(st, 0, st.length(), body, inner)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setIncludePad(false)
                    .build();
            canvas.save();
            canvas.translate(left, recY + recR + titleH * 0.75f);
            status.draw(canvas);
            canvas.restore();
        }

        private void drawHedgeyIcon(Canvas canvas, float x, float y, float r) {
            iconClip.reset();
            iconClip.addCircle(x, y, r, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(iconClip);
            iconDest.set(x - r, y - r, x + r, y + r);
            canvas.drawBitmap(hedgeyIcon, null, iconDest, bitmapPaint);
            canvas.restore();
        }

        private void mapToView(float lx, float ly, float cx, float cy, float dy,
                float rollDeg, float[] out) {
            float x = lx;
            float y = ly + dy;
            double rad = Math.toRadians(rollDeg);
            float c = (float) Math.cos(rad);
            float s = (float) Math.sin(rad);
            float dx = x - cx;
            float dy2 = y - cy;
            out[0] = cx + dx * c - dy2 * s;
            out[1] = cy + dx * s + dy2 * c;
        }

        private void drawGlyph(Canvas canvas, int id, float x, float y, float r) {
            glyph.setStrokeWidth(Math.max(1.8f, r * 0.14f));
            switch (id) {
                case 0: // hedgehog: round body + spikes
                    glyph.setStyle(Paint.Style.FILL);
                    oval.set(x - r * 0.72f, y - r * 0.15f, x + r * 0.72f, y + r * 0.78f);
                    canvas.drawOval(oval, glyph);
                    glyph.setStyle(Paint.Style.STROKE);
                    for (int s = -4; s <= 4; s++) {
                        float a = (float) Math.toRadians(-90 + s * 18);
                        canvas.drawLine(
                                x + (float) Math.cos(a) * r * 0.18f,
                                y + (float) Math.sin(a) * r * 0.05f,
                                x + (float) Math.cos(a) * r * 0.95f,
                                y + (float) Math.sin(a) * r * 0.78f - r * 0.08f,
                                glyph);
                    }
                    glyph.setStyle(Paint.Style.FILL);
                    canvas.drawCircle(x + r * 0.38f, y + r * 0.22f, r * 0.09f, glyph);
                    break;
                case 1: // cat
                    glyph.setStyle(Paint.Style.FILL);
                    canvas.drawCircle(x, y + r * 0.12f, r * 0.58f, glyph);
                    glyphPath.reset();
                    glyphPath.moveTo(x - r * 0.52f, y + r * 0.05f);
                    glyphPath.lineTo(x - r * 0.62f, y - r * 0.85f);
                    glyphPath.lineTo(x - r * 0.08f, y - r * 0.22f);
                    glyphPath.close();
                    glyphPath.moveTo(x + r * 0.52f, y + r * 0.05f);
                    glyphPath.lineTo(x + r * 0.62f, y - r * 0.85f);
                    glyphPath.lineTo(x + r * 0.08f, y - r * 0.22f);
                    glyphPath.close();
                    canvas.drawPath(glyphPath, glyph);
                    glyph.setColor(0xFFFFFFFF);
                    canvas.drawCircle(x - r * 0.18f, y + r * 0.08f, r * 0.08f, glyph);
                    canvas.drawCircle(x + r * 0.18f, y + r * 0.08f, r * 0.08f, glyph);
                    break;
                case 2: // files: folder tab
                    glyph.setStyle(Paint.Style.FILL);
                    oval.set(x - r * 0.62f, y - r * 0.08f, x + r * 0.62f, y + r * 0.55f);
                    canvas.drawRoundRect(oval, r * 0.12f, r * 0.12f, glyph);
                    oval.set(x - r * 0.62f, y - r * 0.42f, x - r * 0.02f, y + r * 0.05f);
                    canvas.drawRoundRect(oval, r * 0.10f, r * 0.10f, glyph);
                    break;
                case 3: // lizard
                    glyph.setStyle(Paint.Style.FILL);
                    oval.set(x - r * 0.55f, y - r * 0.28f, x + r * 0.35f, y + r * 0.28f);
                    canvas.drawOval(oval, glyph);
                    canvas.drawCircle(x + r * 0.42f, y - r * 0.02f, r * 0.22f, glyph);
                    glyph.setStyle(Paint.Style.STROKE);
                    canvas.drawLine(x - r * 0.45f, y, x - r * 0.98f, y + r * 0.35f, glyph);
                    canvas.drawLine(x - r * 0.1f, y - r * 0.12f, x - r * 0.05f, y - r * 0.7f, glyph);
                    canvas.drawLine(x + r * 0.1f, y - r * 0.12f, x + r * 0.22f, y - r * 0.7f, glyph);
                    canvas.drawLine(x - r * 0.1f, y + r * 0.12f, x - r * 0.05f, y + r * 0.7f, glyph);
                    canvas.drawLine(x + r * 0.1f, y + r * 0.12f, x + r * 0.22f, y + r * 0.7f, glyph);
                    break;
                case 4: // Record
                    glyph.setStyle(Paint.Style.FILL);
                    canvas.drawCircle(x, y, r * 0.72f, glyph);
                    glyph.setColor(0xFFFFFFFF);
                    canvas.drawCircle(x, y, r * 0.28f, glyph);
                    break;
                default: // Writer: a page
                    glyph.setStyle(Paint.Style.STROKE);
                    oval.set(x - r * 0.55f, y - r * 0.7f, x + r * 0.55f, y + r * 0.7f);
                    canvas.drawRoundRect(oval, r * 0.12f, r * 0.12f, glyph);
                    canvas.drawLine(x - r * 0.32f, y - r * 0.32f, x + r * 0.32f, y - r * 0.32f, glyph);
                    canvas.drawLine(x - r * 0.32f, y, x + r * 0.32f, y, glyph);
                    canvas.drawLine(x - r * 0.32f, y + r * 0.32f, x + r * 0.12f, y + r * 0.32f, glyph);
                    break;
            }
        }

        private void drawClickMarks(Canvas canvas, float cx, float cy) {
            long now = SystemClock.uptimeMillis();
            float viewMin = Math.min(getWidth(), getHeight());
            for (int i = 0; i < marks.size(); i++) {
                ClickMark m = marks.get(i);
                float age = (now - m.born) / (float) CLICK_MARK_MS;
                if (age >= 1f) continue;
                int a = (int) (255f * (1f - age) * (1f - age));
                float x = cx;
                float y = cy - pitchToDy(m.pitch, viewMin);
                buildNgon(markPath, x, y, m.radius * (0.85f + 0.15f * (1f - age)), m.sides, m.spin);
                if (m.primary) {
                    markFill.setColor(Color.argb(a, 255, 255, 255));
                    canvas.drawPath(markPath, markFill);
                } else {
                    markStroke.setColor(Color.argb(a, 255, 255, 255));
                    canvas.drawPath(markPath, markStroke);
                }
            }
        }

        private static void buildNgon(Path path, float cx, float cy, float r, int sides, float spinDeg) {
            path.reset();
            int n = Math.max(3, sides);
            double spin = Math.toRadians(spinDeg);
            for (int i = 0; i < n; i++) {
                double a = spin + (Math.PI * 2.0 * i) / n - Math.PI / 2.0;
                float x = cx + (float) (Math.cos(a) * r);
                float y = cy + (float) (Math.sin(a) * r);
                if (i == 0) path.moveTo(x, y);
                else path.lineTo(x, y);
            }
            path.close();
        }
    }

    private static final class Reticle extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private long pulseAt;
        private boolean pulsePrimary;

        Reticle(Context context) {
            super(context);
            paint.setColor(0xCCFF2A22);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2f);
            setWillNotDraw(false);
        }

        void pulse(boolean primary) {
            pulseAt = SystemClock.uptimeMillis();
            pulsePrimary = primary;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            int cx = w / 2;
            int cy = h / 2;
            int arm = Math.min(w, h) / 14;
            int gap = Math.min(w, h) / 28;
            long dt = SystemClock.uptimeMillis() - pulseAt;
            if (pulseAt > 0L && dt < 280L) {
                float u = dt / 280f;
                int a = (int) (230f * (1f - u));
                paint.setColor(Color.argb(a, 255, 255, 255));
                paint.setStrokeWidth(2.8f);
                float rad = gap * 1.6f * (1f + 2.4f * u);
                canvas.drawCircle(cx, cy, rad, paint);
                if (!pulsePrimary) {
                    canvas.drawCircle(cx, cy, rad * 0.55f, paint);
                }
                paint.setColor(0xCCFF2A22);
                postInvalidateDelayed(16);
            }
            paint.setStrokeWidth(2.2f);
            canvas.drawLine(cx - arm, cy, cx - gap, cy, paint);
            canvas.drawLine(cx + gap, cy, cx + arm, cy, paint);
            canvas.drawLine(cx, cy - arm, cx, cy - gap, paint);
            canvas.drawLine(cx, cy + gap, cx, cy + arm, paint);
            canvas.drawCircle(cx, cy, gap * 1.6f, paint);
            int inset = Math.min(w, h) / 9;
            int tick = inset / 3;
            paint.setStrokeWidth(2.6f);
            canvas.drawLine(inset, inset, inset + tick, inset, paint);
            canvas.drawLine(inset, inset, inset, inset + tick, paint);
            canvas.drawLine(w - inset, inset, w - inset - tick, inset, paint);
            canvas.drawLine(w - inset, inset, w - inset, inset + tick, paint);
            canvas.drawLine(inset, h - inset, inset + tick, h - inset, paint);
            canvas.drawLine(inset, h - inset, inset, h - inset - tick, paint);
            canvas.drawLine(w - inset, h - inset, w - inset - tick, h - inset, paint);
            canvas.drawLine(w - inset, h - inset, w - inset, h - inset - tick, paint);
        }
    }

    private static final class Scanlines extends View {
        private final Paint paint = new Paint();

        Scanlines(Context context) {
            super(context);
            paint.setColor(0x2A000000);
            setWillNotDraw(false);
            setClickable(false);
            setFocusable(false);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            for (int y = 0; y < h; y += 4) {
                canvas.drawLine(0, y, w, y, paint);
            }
        }
    }
}
