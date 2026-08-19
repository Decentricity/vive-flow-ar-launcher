package com.decentricity.arlauncher;

import android.app.Activity;
import android.graphics.Bitmap;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.view.PixelCopy;
import android.view.View;
import android.view.Window;

import java.io.File;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Screenshot the visor window every 250ms and mux H.264 to Movies/ARLauncher.
 * Lives on MainActivity, not the Record window, so capture survives close/reopen.
 */
final class ScreenRecorder {
    static final String[] PRESET_LABELS = {"5s", "10s", "30s", "1m", "5m"};
    static final long[] PRESET_MS = {5000L, 10000L, 30000L, 60000L, 300000L};
    private static final long FRAME_MS = 250L;
    private static final int WIDTH = 1280;
    private static final int HEIGHT = 640;

    interface Listener {
        void onRecorderChanged();
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private HandlerThread thread;
    private Handler rec;
    private Activity activity;
    private int preset = 1;
    private volatile boolean recording;
    private long startedAt;
    private long deadline;
    private File outFile;
    private String status = "Pick a time, then Record.";
    private MediaCodec codec;
    private MediaMuxer muxer;
    private int track = -1;
    private boolean muxing;
    private long frameIndex;
    private Bitmap capBmp;
    private boolean captureBusy;
    private final MediaCodec.BufferInfo bufInfo = new MediaCodec.BufferInfo();
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            captureFrame();
        }
    };
    private final Runnable finish = new Runnable() {
        @Override
        public void run() {
            stopInternal("Saved");
        }
    };

    ScreenRecorder(Listener listener) {
        this.listener = listener;
    }

    synchronized int preset() {
        return preset;
    }

    synchronized void setPreset(int index) {
        if (recording) return;
        if (index < 0 || index >= PRESET_MS.length) return;
        preset = index;
        status = "Ready for " + PRESET_LABELS[preset] + ".";
        notifyUi();
    }

    synchronized boolean isRecording() {
        return recording;
    }

    synchronized String status() {
        if (recording) {
            long left = Math.max(0L, deadline - SystemClock.elapsedRealtime());
            return "Recording " + formatMs(SystemClock.elapsedRealtime() - startedAt)
                    + " / " + PRESET_LABELS[preset]
                    + "  (" + formatMs(left) + " left)";
        }
        return status;
    }

    synchronized File lastFile() {
        return outFile;
    }

    void attach(Activity act) {
        activity = act;
    }

    void toggle() {
        if (isRecording()) stop();
        else start();
    }

    void start() {
        final Activity act = activity;
        if (act == null) return;
        synchronized (this) {
            if (recording) return;
            if (preset < 0 || preset >= PRESET_MS.length) {
                status = "Pick a time first.";
                notifyUi();
                return;
            }
        }
        File dir = moviesDir(act);
        if (dir == null) {
            synchronized (this) {
                status = "Cannot write video on this headset.";
                notifyUi();
            }
            return;
        }
        String name = "rec-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                .format(new Date()) + ".mp4";
        File dest = new File(dir, name);
        try {
            ensureThread();
            rec.post(new Runnable() {
                @Override
                public void run() {
                    beginOnThread(dest);
                }
            });
        } catch (Exception e) {
            synchronized (this) {
                status = "Recorder failed: " + e.getMessage();
                notifyUi();
            }
        }
    }

    void stop() {
        if (rec == null) {
            stopInternal("Stopped");
            return;
        }
        rec.post(new Runnable() {
            @Override
            public void run() {
                stopInternal("Stopped");
            }
        });
    }

    void release() {
        stop();
        if (thread != null) {
            thread.quitSafely();
            thread = null;
            rec = null;
        }
        if (capBmp != null) {
            capBmp.recycle();
            capBmp = null;
        }
        activity = null;
    }

    private void ensureThread() {
        if (thread != null) return;
        thread = new HandlerThread("screen-rec");
        thread.start();
        rec = new Handler(thread.getLooper());
    }

    private void beginOnThread(File dest) {
        releaseCodec();
        try {
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            MediaFormat fmt = MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT);
            fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000);
            fmt.setInteger(MediaFormat.KEY_FRAME_RATE, 4);
            fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
            muxer = new MediaMuxer(dest.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            track = -1;
            muxing = false;
            frameIndex = 0;
            captureBusy = false;
            synchronized (this) {
                outFile = dest;
                recording = true;
                startedAt = SystemClock.elapsedRealtime();
                deadline = startedAt + PRESET_MS[preset];
                status = "Recording…";
            }
            notifyUi();
            rec.removeCallbacks(tick);
            rec.removeCallbacks(finish);
            rec.post(tick);
            rec.postDelayed(finish, PRESET_MS[preset]);
        } catch (Exception e) {
            releaseCodec();
            synchronized (this) {
                recording = false;
                status = "Could not start encoder: " + e.getMessage();
            }
            notifyUi();
        }
    }

    private void captureFrame() {
        if (!isRecording()) return;
        if (SystemClock.elapsedRealtime() >= deadline) {
            stopInternal("Saved");
            return;
        }
        final Activity act = activity;
        if (act == null) {
            rec.postDelayed(tick, FRAME_MS);
            return;
        }
        synchronized (this) {
            if (captureBusy) {
                rec.postDelayed(tick, FRAME_MS);
                return;
            }
            captureBusy = true;
        }
        main.post(new Runnable() {
            @Override
            public void run() {
                Window win = act.getWindow();
                View decor = win != null ? win.getDecorView() : null;
                if (decor == null || decor.getWidth() <= 0) {
                    rec.post(new Runnable() {
                        @Override
                        public void run() {
                            synchronized (ScreenRecorder.this) {
                                captureBusy = false;
                            }
                            rec.postDelayed(tick, FRAME_MS);
                        }
                    });
                    return;
                }
                if (capBmp == null || capBmp.getWidth() != decor.getWidth()
                        || capBmp.getHeight() != decor.getHeight()) {
                    if (capBmp != null) capBmp.recycle();
                    capBmp = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(),
                            Bitmap.Config.ARGB_8888);
                }
                try {
                    PixelCopy.request(win, capBmp, new PixelCopy.OnPixelCopyFinishedListener() {
                        @Override
                        public void onPixelCopyFinished(int result) {
                            final boolean ok = result == PixelCopy.SUCCESS;
                            rec.post(new Runnable() {
                                @Override
                                public void run() {
                                    onCaptured(ok);
                                }
                            });
                        }
                    }, main);
                } catch (Exception e) {
                    rec.post(new Runnable() {
                        @Override
                        public void run() {
                            onCaptured(false);
                        }
                    });
                }
            }
        });
    }

    private void onCaptured(boolean ok) {
        try {
            if (ok && isRecording() && capBmp != null && codec != null) {
                encodeBitmap(capBmp);
            }
        } catch (Exception ignored) {
        }
        synchronized (this) {
            captureBusy = false;
        }
        if (isRecording()) rec.postDelayed(tick, FRAME_MS);
        notifyUi();
    }

    private void encodeBitmap(Bitmap bmp) throws Exception {
        int inIx = codec.dequeueInputBuffer(20_000);
        if (inIx < 0) {
            drain(false);
            inIx = codec.dequeueInputBuffer(50_000);
        }
        if (inIx < 0) return;
        Image image = codec.getInputImage(inIx);
        if (image == null) {
            codec.queueInputBuffer(inIx, 0, 0, 0, 0);
            return;
        }
        bitmapToYuv(bmp, image);
        long pts = frameIndex * FRAME_MS * 1000L;
        frameIndex++;
        codec.queueInputBuffer(inIx, 0, WIDTH * HEIGHT * 3 / 2, pts, 0);
        drain(false);
    }

    private void stopInternal(String why) {
        rec.removeCallbacks(tick);
        rec.removeCallbacks(finish);
        boolean was = false;
        synchronized (this) {
            was = recording;
            recording = false;
        }
        if (was && codec != null) {
            try {
                int inIx = codec.dequeueInputBuffer(100_000);
                if (inIx >= 0) {
                    codec.queueInputBuffer(inIx, 0, 0, frameIndex * FRAME_MS * 1000L,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                }
                drain(true);
            } catch (Exception ignored) {
            }
        }
        File saved = outFile;
        releaseCodec();
        synchronized (this) {
            if (saved != null && saved.isFile() && saved.length() > 0) {
                status = why + " " + saved.getName() + " (" + (saved.length() / 1024) + " KB)";
            } else if (was) {
                status = "Recording ended (no file).";
            }
        }
        notifyUi();
    }

    private void drain(boolean eos) {
        if (codec == null) return;
        long until = SystemClock.elapsedRealtime() + (eos ? 800L : 0L);
        while (true) {
            int outIx = codec.dequeueOutputBuffer(bufInfo, eos ? 30_000 : 0);
            if (outIx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!eos || SystemClock.elapsedRealtime() >= until) break;
                continue;
            }
            if (outIx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (!muxing && muxer != null) {
                    track = muxer.addTrack(codec.getOutputFormat());
                    muxer.start();
                    muxing = true;
                }
                continue;
            }
            if (outIx < 0) continue;
            ByteBuffer out = codec.getOutputBuffer(outIx);
            if (out != null && bufInfo.size > 0 && muxing && muxer != null
                    && (bufInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                out.position(bufInfo.offset);
                out.limit(bufInfo.offset + bufInfo.size);
                muxer.writeSampleData(track, out, bufInfo);
            }
            codec.releaseOutputBuffer(outIx, false);
            if ((bufInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
            if (!eos && outIx >= 0) {
                // keep draining while buffers are ready, then return
            }
        }
    }

    private void releaseCodec() {
        if (muxer != null) {
            try {
                if (muxing) muxer.stop();
            } catch (Exception ignored) {
            }
            try {
                muxer.release();
            } catch (Exception ignored) {
            }
            muxer = null;
        }
        muxing = false;
        track = -1;
        if (codec != null) {
            try {
                codec.stop();
            } catch (Exception ignored) {
            }
            try {
                codec.release();
            } catch (Exception ignored) {
            }
            codec = null;
        }
    }

    private void notifyUi() {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (listener != null) listener.onRecorderChanged();
            }
        });
    }

    private static File moviesDir(Activity act) {
        File pub = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
        File dir = new File(pub, "ARLauncher");
        if (dir.exists() || dir.mkdirs()) return dir;
        File alt = act.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (alt != null && (alt.exists() || alt.mkdirs())) return alt;
        return null;
    }

    static String formatMs(long ms) {
        long s = Math.max(0L, ms) / 1000L;
        return (s / 60L) + ":" + String.format(Locale.US, "%02d", s % 60L);
    }

    private static void bitmapToYuv(Bitmap bmp, Image image) {
        int width = image.getWidth();
        int height = image.getHeight();
        Bitmap src = bmp;
        boolean scaled = false;
        if (bmp.getWidth() != width || bmp.getHeight() != height) {
            src = Bitmap.createScaledBitmap(bmp, width, height, true);
            scaled = true;
        }
        int[] argb = new int[width * height];
        src.getPixels(argb, 0, width, 0, 0, width, height);
        if (scaled && src != bmp) src.recycle();
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer yBuf = planes[0].getBuffer();
        ByteBuffer uBuf = planes[1].getBuffer();
        ByteBuffer vBuf = planes[2].getBuffer();
        int yRow = planes[0].getRowStride();
        int uvRow = planes[1].getRowStride();
        int uvPix = planes[1].getPixelStride();
        yBuf.clear();
        for (int row = 0; row < height; row++) {
            int yPos = row * yRow;
            int rowOff = row * width;
            for (int col = 0; col < width; col++) {
                int c = argb[rowOff + col];
                int r = (c >> 16) & 255;
                int g = (c >> 8) & 255;
                int b = c & 255;
                int y = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                if (y < 0) y = 0;
                if (y > 255) y = 255;
                yBuf.put(yPos + col, (byte) y);
            }
        }
        for (int row = 0; row < height / 2; row++) {
            int rowOff = (row * 2) * width;
            for (int col = 0; col < width / 2; col++) {
                int c = argb[rowOff + col * 2];
                int r = (c >> 16) & 255;
                int g = (c >> 8) & 255;
                int b = c & 255;
                int u = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                int v = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                if (u < 0) u = 0;
                if (u > 255) u = 255;
                if (v < 0) v = 0;
                if (v > 255) v = 255;
                int uvPos = row * uvRow + col * uvPix;
                uBuf.put(uvPos, (byte) u);
                vBuf.put(uvPos, (byte) v);
            }
        }
    }
}
