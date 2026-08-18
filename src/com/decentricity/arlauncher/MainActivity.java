package com.decentricity.arlauncher;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
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
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;
import java.util.Random;

public class MainActivity extends Activity implements SensorEventListener, CameraBank.Listener {
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
    private final ArrayList<ClickMark> clickMarks = new ArrayList<ClickMark>();
    private final Random clickRng = new Random();
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
        sensors = (SensorManager) getSystemService(SENSOR_SERVICE);
        rotSensor = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if (rotSensor == null) {
            rotSensor = sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        }
        gravSensor = sensors.getDefaultSensor(Sensor.TYPE_GRAVITY);
        if (gravSensor == null) {
            gravSensor = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        }

        LinearLayout stereo = new LinearLayout(this);
        stereo.setBackgroundColor(Color.BLACK);
        stereo.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams eyeLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        stereo.addView(buildEye(0), eyeLp);
        stereo.addView(buildEye(1), eyeLp);
        setContentView(stereo);
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        setCamChips("WAIT", "WAIT");
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

        root.addView(new Scanlines(this), new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        Horizon horizon = new Horizon(this, clickMarks);
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

        LinearLayout botRow = new LinearLayout(this);
        botRow.setOrientation(LinearLayout.HORIZONTAL);
        botRow.setGravity(Gravity.CENTER_VERTICAL);
        camChips[eye][0] = hudLabel("L --");
        camChips[eye][1] = hudLabel("R --");
        botRow.addView(camChips[eye][0]);
        View gap = new View(this);
        botRow.addView(gap, new LinearLayout.LayoutParams(dp(36), 1));
        botRow.addView(camChips[eye][1]);
        FrameLayout.LayoutParams botLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        botLp.gravity = Gravity.CENTER;
        botLp.topMargin = dp(50);
        root.addView(botRow, botLp);
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
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            maybeStartCameras();
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
        return super.dispatchKeyEvent(event);
    }

    /**
     * Gaze mouse: the reticle is always the pointer (view center). Volume up is
     * primary click, volume down is secondary. Primary parks the guide line on
     * the heading the reticle is looking at. Secondary still stamps a debug n-gon.
     */
    protected void onGazeClick(boolean primary) {
        pruneClickMarks();
        for (int i = 0; i < 2; i++) {
            if (reticles[i] != null) reticles[i].pulse(primary);
        }
        if (primary) {
            float snap = headingNow(true);
            if (!Float.isNaN(snap)) {
                lookHeading = snap;
                haveLookHeading = true;
                guideYaw = snap;
                haveGuide = true;
                applyHorizon();
            }
            return;
        }
        ClickMark mark = new ClickMark();
        mark.pitch = gazePitch;
        mark.sides = 3 + clickRng.nextInt(6);
        mark.spin = clickRng.nextFloat() * 360f;
        mark.radius = 16f + clickRng.nextFloat() * 18f;
        mark.primary = primary;
        mark.born = SystemClock.uptimeMillis();
        clickMarks.add(mark);
        for (int i = 0; i < 2; i++) {
            if (horizons[i] != null) horizons[i].invalidate();
        }
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                pruneClickMarks();
                for (int i = 0; i < 2; i++) {
                    if (horizons[i] != null) horizons[i].invalidate();
                }
            }
        }, CLICK_MARK_MS + 50);
    }

    private void pruneClickMarks() {
        long now = SystemClock.uptimeMillis();
        Iterator<ClickMark> it = clickMarks.iterator();
        while (it.hasNext()) {
            if (now - it.next().born > CLICK_MARK_MS) it.remove();
        }
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
        if (code == 7 && grant.length > 0 && grant[0] == PackageManager.PERMISSION_GRANTED) {
            maybeStartCameras();
        } else {
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
        ui.postDelayed(stopCamDelayed, 2500);
        super.onPause();
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
        private final Path markPath = new Path();
        private final ArrayList<ClickMark> marks;
        private float pitch;
        private float roll;
        private float yaw;
        private float heading;
        private float guideYaw = Float.NaN;

        Horizon(Context context, ArrayList<ClickMark> marks) {
            super(context);
            this.marks = marks;
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
            markFill.setStyle(Paint.Style.FILL);
            markStroke.setStyle(Paint.Style.STROKE);
            markStroke.setStrokeWidth(2.4f);
            setWillNotDraw(false);
            setClickable(false);
            setFocusable(false);
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
            canvas.drawLine(cx - arm, cy, cx - 18, cy, paint);
            canvas.drawLine(cx + 18, cy, cx + arm, cy, paint);
            canvas.drawLine(cx - arm, cy - 6, cx - arm, cy + 6, paint);
            canvas.drawLine(cx + arm, cy - 6, cx + arm, cy + 6, paint);
            String label = String.format(Locale.US, "P%+.1f  R%+.1f  Y%.0f", pitch, roll, yaw);
            canvas.drawText(label, cx, cy - 6 - text.descent(), text);
            drawGuide(canvas, w, cx, cy);
            drawClickMarks(canvas, cx, cy);
            canvas.restore();
        }

        private void drawGuide(Canvas canvas, int w, float cx, float cy) {
            if (Float.isNaN(guideYaw)) return;
            float hfov = CAMERA_VFOV_DEG / CAM_PREVIEW_ASPECT;
            float pxPerDeg = w / hfov;
            float x = cx + wrapDeg(guideYaw - heading) * pxPerDeg;
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

        private void drawClickMarks(Canvas canvas, float cx, float cy) {
            long now = SystemClock.uptimeMillis();
            float viewMin = Math.min(getWidth(), getHeight());
            for (int i = 0; i < marks.size(); i++) {
                ClickMark m = marks.get(i);
                float age = (now - m.born) / (float) CLICK_MARK_MS;
                if (age >= 1f) continue;
                int a = (int) (255f * (1f - age) * (1f - age));
                // Same canvas as the bar: rotate+pitch translate already applied.
                // Sit at the click's elevation on that bar. Do not pan with
                // Euler yaw — that couples into nod/roll and flies sideways.
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
