package com.decentricity.arlauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** CAM0 left + CAM1 right. Decode off the camera thread; reopen if the HAL drops a stream. */
final class CameraBank {
    interface Listener {
        void onLabel(int index, String label);
        void onFrame(int index, Bitmap bitmap);
    }

    private static final String TAG = "ARLauncher";
    private static final int EYES = 2;
    private final CameraManager manager;
    private final Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private HandlerThread thread;
    private HandlerThread decodeThread;
    private Handler bg;
    private Handler decode;
    private volatile boolean running;
    private String[] ids = new String[0];
    private final Stream[] streams = new Stream[EYES];

    CameraBank(Context context, Listener listener) {
        this.manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        this.listener = listener;
    }

    void start() {
        stop();
        try {
            ids = manager.getCameraIdList();
        } catch (CameraAccessException e) {
            emitLabel(0, "L CAM? id list FAIL " + e.getReason());
            emitLabel(1, "R CAM? id list FAIL " + e.getReason());
            return;
        }
        if (ids.length == 0) {
            emitLabel(0, "L CAM? Camera2 id list empty");
            emitLabel(1, "R CAM? Camera2 id list empty");
            return;
        }
        thread = new HandlerThread("flow-cam2");
        thread.start();
        bg = new Handler(thread.getLooper());
        decodeThread = new HandlerThread("flow-cam-dec");
        decodeThread.start();
        decode = new Handler(decodeThread.getLooper());
        running = true;
        Log.i(TAG, "Camera2 ids: " + Arrays.toString(ids));
        int n = Math.min(EYES, ids.length);
        for (int i = 0; i < n; i++) {
            streams[i] = new Stream(i, ids[i]);
        }
        bg.post(new Runnable() {
            @Override
            public void run() {
                if (streams[0] != null) streams[0].open();
            }
        });
        if (ids.length < 2) {
            emitLabel(1, "R no second Camera2 id (only " + ids.length + ")");
        }
    }

    void stop() {
        running = false;
        Handler h = bg;
        HandlerThread t = thread;
        HandlerThread d = decodeThread;
        if (h != null && t != null && t.isAlive()) {
            final CountDownLatch done = new CountDownLatch(1);
            h.post(new Runnable() {
                @Override
                public void run() {
                    for (int i = 0; i < streams.length; i++) {
                        if (streams[i] != null) streams[i].close();
                        streams[i] = null;
                    }
                    done.countDown();
                }
            });
            try {
                done.await(1500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
            }
            t.quitSafely();
        } else {
            for (int i = 0; i < streams.length; i++) {
                if (streams[i] != null) streams[i].close();
                streams[i] = null;
            }
        }
        if (d != null) d.quitSafely();
        thread = null;
        decodeThread = null;
        bg = null;
        decode = null;
    }

    int count() {
        return ids.length;
    }

    void markFrameConsumed(int index) {
        if (index >= 0 && index < streams.length && streams[index] != null) {
            streams[index].dropping.set(false);
        }
    }

    private boolean switchRightToAlt() {
        if (streams[1] == null || ids.length < 3) return false;
        String alt = ids[2];
        if (alt.equals(streams[1].id)) return false;
        Log.w(TAG, "CAM1 busy, trying camera id " + alt + " for right eye");
        emitLabel(1, "R CAM1 stuck — trying CAM" + alt);
        streams[1].id = alt;
        streams[1].reopenDelayMs = 800;
        streams[1].scheduleReopen(400);
        return true;
    }

    private void openNextAfter(int index) {
        if (!running || index != 0 || bg == null) return;
        bg.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!running) return;
                if (streams[1] != null && streams[1].device == null) {
                    streams[1].open();
                }
            }
        }, 600);
    }

    private void emitLabel(final int index, final String text) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                listener.onLabel(index, text);
            }
        });
    }

    private static String eyeName(int index) {
        return index == 0 ? "L" : "R";
    }

    private static String errorName(int error) {
        switch (error) {
            case CameraDevice.StateCallback.ERROR_CAMERA_IN_USE: return "IN_USE";
            case CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE: return "MAX_CAMERAS";
            case CameraDevice.StateCallback.ERROR_CAMERA_DISABLED: return "DISABLED";
            case CameraDevice.StateCallback.ERROR_CAMERA_DEVICE: return "DEVICE";
            case CameraDevice.StateCallback.ERROR_CAMERA_SERVICE: return "SERVICE";
            default: return "error=" + error;
        }
    }

    private final class Stream {
        final int index;
        String id;
        final AtomicBoolean dropping = new AtomicBoolean();
        final AtomicBoolean reopenPosted = new AtomicBoolean();
        volatile boolean closing;
        int reopenDelayMs = 2000;
        CameraDevice device;
        CameraCaptureSession session;
        ImageReader reader;
        Bitmap[] pool = new Bitmap[2];
        int[][] pix = new int[2][];
        byte[] yCopy;
        int flip;
        String facing = "?";
        int orient = -1;
        Size size = new Size(640, 480);

        Stream(int index, String id) {
            this.index = index;
            this.id = id;
        }

        void open() {
            if (!running) return;
            close();
            try {
                CameraCharacteristics ch = manager.getCameraCharacteristics(id);
                Integer f = ch.get(CameraCharacteristics.LENS_FACING);
                if (f != null) {
                    if (f == CameraCharacteristics.LENS_FACING_FRONT) facing = "FRONT";
                    else if (f == CameraCharacteristics.LENS_FACING_BACK) facing = "BACK";
                    else facing = "EXT";
                }
                Integer o = ch.get(CameraCharacteristics.SENSOR_ORIENTATION);
                if (o != null) orient = o;
                StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map != null) {
                    Size[] yuv = map.getOutputSizes(ImageFormat.YUV_420_888);
                    if (yuv != null && yuv.length > 0) size = pick(yuv);
                }
            } catch (Exception e) {
                emitLabel(index, eyeName(index) + " CAM" + index + " id=" + id
                        + " chars FAIL " + e.getMessage());
                scheduleReopen();
                return;
            }

            emitLabel(index, eyeName(index) + " CAM" + index + " id=" + id + " " + facing
                    + " orient=" + orient + " " + size.getWidth() + "x" + size.getHeight() + " OPENING");

            reader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.YUV_420_888, 4);
            reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader r) {
                    if (!running) return;
                    Image image = null;
                    try {
                        image = r.acquireLatestImage();
                        if (image == null) return;
                        if (!dropping.compareAndSet(false, true)) return;
                        queueDecode(image);
                    } catch (Exception e) {
                        dropping.set(false);
                    } finally {
                        if (image != null) image.close();
                    }
                }
            }, bg);

            try {
                manager.openCamera(id, new CameraDevice.StateCallback() {
                    @Override
                    public void onOpened(CameraDevice camera) {
                        if (!running) {
                            camera.close();
                            return;
                        }
                        closing = false;
                        device = camera;
                        startSession(camera);
                    }

                    @Override
                    public void onDisconnected(CameraDevice camera) {
                        camera.close();
                        if (device == camera) device = null;
                        if (closing || !running) return;
                        emitLabel(index, eyeName(index) + " CAM" + index + " dropped — reopening");
                        scheduleReopen();
                    }

                    @Override
                    public void onError(CameraDevice camera, int error) {
                        camera.close();
                        if (device == camera) device = null;
                        if (closing || !running) return;
                        boolean busy = error == CameraDevice.StateCallback.ERROR_CAMERA_IN_USE
                                || error == CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE;
                        emitLabel(index, eyeName(index) + " CAM" + index + " HAL "
                                + errorName(error) + " — retry");
                        if (index == 0) openNextAfter(0);
                        if (busy && index == 1 && switchRightToAlt()) return;
                        scheduleReopen(busy ? 5000 : 0);
                    }

                    @Override
                    public void onClosed(CameraDevice camera) {
                        if (device == camera) device = null;
                    }
                }, bg);
            } catch (SecurityException se) {
                emitLabel(index, eyeName(index) + " CAM" + index + " SECURITY " + se.getMessage());
            } catch (Exception e) {
                Log.w(TAG, "open " + id, e);
                String msg = e.getMessage() != null ? e.getMessage() : "";
                boolean busy = msg.contains("already open") || msg.contains("CAMERA_IN_USE")
                        || msg.contains("IN_USE");
                emitLabel(index, eyeName(index) + " CAM" + index
                        + (busy ? " BUSY — backing off" : " OPEN FAIL " + msg));
                if (busy && index == 1 && switchRightToAlt()) return;
                scheduleReopen(busy ? 5000 : 0);
            }
        }

        void startSession(final CameraDevice camera) {
            Surface surface = reader.getSurface();
            try {
                camera.createCaptureSession(Collections.singletonList(surface),
                        new CameraCaptureSession.StateCallback() {
                            @Override
                            public void onConfigured(CameraCaptureSession sess) {
                                if (!running) {
                                    sess.close();
                                    return;
                                }
                                session = sess;
                                try {
                                    CaptureRequest.Builder req =
                                            camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                    req.addTarget(reader.getSurface());
                                    sess.setRepeatingRequest(req.build(), null, bg);
                                    emitLabel(index, eyeName(index) + " CAM" + index + " id=" + id
                                            + " " + facing + " orient=" + orient + " "
                                            + size.getWidth() + "x" + size.getHeight() + " LIVE");
                                    reopenDelayMs = 2000;
                                    openNextAfter(index);
                                } catch (Exception e) {
                                    emitLabel(index, eyeName(index) + " CAM" + index
                                            + " PREVIEW FAIL " + e.getMessage());
                                    scheduleReopen();
                                }
                            }

                            @Override
                            public void onConfigureFailed(CameraCaptureSession sess) {
                                emitLabel(index, eyeName(index) + " CAM" + index + " SESSION FAIL");
                                if (index == 0) openNextAfter(0);
                                scheduleReopen();
                            }
                        }, bg);
            } catch (Exception e) {
                emitLabel(index, eyeName(index) + " CAM" + index + " SESSION EX " + e.getMessage());
                scheduleReopen();
            }
        }

        void scheduleReopen() {
            scheduleReopen(0);
        }

        void scheduleReopen(int minDelayMs) {
            if (!running || bg == null) return;
            if (!reopenPosted.compareAndSet(false, true)) return;
            int delay = Math.max(minDelayMs, reopenDelayMs);
            reopenDelayMs = Math.min(12000, Math.max(2000, reopenDelayMs) * 2);
            bg.postDelayed(new Runnable() {
                @Override
                public void run() {
                    reopenPosted.set(false);
                    if (running && device == null) open();
                }
            }, delay);
        }

        void queueDecode(Image image) {
            Image.Plane yPlane = image.getPlanes()[0];
            ByteBuffer buf = yPlane.getBuffer();
            final int w = image.getWidth();
            final int h = image.getHeight();
            final int rowStride = yPlane.getRowStride();
            int need = rowStride * h;
            if (yCopy == null || yCopy.length < need) yCopy = new byte[need];
            buf.rewind();
            int remaining = Math.min(need, buf.remaining());
            buf.get(yCopy, 0, remaining);
            final byte[] src = yCopy;
            final Handler dec = decode;
            if (dec == null) {
                dropping.set(false);
                return;
            }
            dec.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        Bitmap decoded = yPlaneToHud(src, w, h, rowStride);
                        if (decoded != null) listener.onFrame(index, decoded);
                        else dropping.set(false);
                    } catch (Exception e) {
                        dropping.set(false);
                    }
                }
            });
        }

        void close() {
            closing = true;
            try {
                if (session != null) session.close();
            } catch (Exception ignored) {
            }
            session = null;
            try {
                if (device != null) device.close();
            } catch (Exception ignored) {
            }
            device = null;
            if (reader != null) {
                reader.close();
                reader = null;
            }
        }

        Bitmap yPlaneToHud(byte[] src, int w, int h, int rowStride) {
            int slot = flip;
            flip ^= 1;
            if (pix[slot] == null || pix[slot].length != w * h) {
                pix[slot] = new int[w * h];
                pool[slot] = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            }
            int[] pixels = pix[slot];
            for (int y = 0; y < h; y++) {
                int srcBase = y * rowStride;
                int rowBase = y * w;
                for (int x = 0; x < w; x++) {
                    int lum = src[srcBase + x] & 0xff;
                    int r = lum;
                    int g = (lum * 48) >> 8;
                    int b = (lum * 22) >> 8;
                    pixels[rowBase + x] = 0xff000000 | (r << 16) | (g << 8) | b;
                }
            }
            Bitmap bmp = pool[slot];
            bmp.setPixels(pixels, 0, w, 0, 0, w, h);
            return bmp;
        }
    }

    private static Size pick(Size[] sizes) {
        Size best = sizes[0];
        int bestScore = Integer.MAX_VALUE;
        for (Size s : sizes) {
            if (s.getWidth() < 320 || s.getWidth() > 960) continue;
            int score = Math.abs(s.getWidth() - 640) + Math.abs(s.getHeight() - 480);
            if (score < bestScore) {
                best = s;
                bestScore = score;
            }
        }
        return best;
    }
}
