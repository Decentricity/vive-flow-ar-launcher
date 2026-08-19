package com.decentricity.arlauncher;

import android.app.Activity;
import android.graphics.Bitmap;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.media.MediaScannerConnection;
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
 * Screenshot the visor window at 16 fps and mux H.264 to shared ~/Movies.
 * Lives on MainActivity, not the Record window, so capture survives close/reopen.
 */
final class ScreenRecorder {
    static final String[] PRESET_LABELS = {"5s", "10s", "30s", "1m", "5m"};
    static final long[] PRESET_MS = {5000L, 10000L, 30000L, 60000L, 300000L};
    private static final int FPS = 16;
    private static final long FRAME_MS = 1000L / FPS;
    private static final int WIDTH = 1280;
    private static final int HEIGHT = 640;
    private static final int AUDIO_RATE = 44100;
    private static final int AUDIO_BITRATE = 64000;

    interface Listener {
        void onRecorderChanged();
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final Object muxLock = new Object();
    private HandlerThread thread;
    private Handler rec;
    private HandlerThread audioThread;
    private Handler audioHandler;
    private Activity activity;
    private int preset = 1;
    private boolean micEnabled = true;
    private volatile boolean recording;
    private volatile boolean useAudio;
    private long startedAt;
    private long deadline;
    private File outFile;
    private String status = "Pick a time, then Record.";
    private MediaCodec codec;
    private MediaCodec audioCodec;
    private AudioRecord audioRecord;
    private MediaMuxer muxer;
    private int track = -1;
    private int audioTrack = -1;
    private boolean muxing;
    private boolean videoFormatReady;
    private boolean audioFormatReady;
    private long frameIndex;
    private long originNanos;
    private long lastPtsUs;
    private long lastAudioPtsUs;
    private byte[] audioPcm;
    private Bitmap capBmp;
    private boolean captureBusy;
    private final MediaCodec.BufferInfo bufInfo = new MediaCodec.BufferInfo();
    private final MediaCodec.BufferInfo audioInfo = new MediaCodec.BufferInfo();
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
    private final Runnable audioPump = new Runnable() {
        @Override
        public void run() {
            pumpAudio();
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

    synchronized boolean mic() {
        return micEnabled;
    }

    synchronized void setMic(boolean on) {
        if (recording) return;
        micEnabled = on;
        status = micEnabled ? "Mic on." : "Mic muted.";
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
                    + (useAudio ? " · mic" : " · muted")
                    + "  (" + formatMs(left) + " left)";
        }
        return status;
    }

    synchronized File lastFile() {
        return outFile;
    }

    void attach(Activity act) {
        activity = act;
        migrateOldMovies(act);
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
        if (audioThread != null) {
            audioThread.quitSafely();
            audioThread = null;
            audioHandler = null;
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
        releaseAudio();
        try {
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            MediaFormat fmt = MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT);
            fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000);
            fmt.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
            fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
            useAudio = micEnabled && startAudio();
            muxer = new MediaMuxer(dest.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            track = -1;
            audioTrack = -1;
            muxing = false;
            videoFormatReady = false;
            audioFormatReady = !useAudio;
            frameIndex = 0;
            lastPtsUs = -1L;
            lastAudioPtsUs = -1L;
            captureBusy = false;
            waitForMuxerTracks();
            originNanos = SystemClock.elapsedRealtimeNanos();
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
            if (useAudio && audioHandler != null) {
                audioHandler.removeCallbacks(audioPump);
                audioHandler.post(audioPump);
            }
        } catch (Exception e) {
            releaseCodec();
            releaseAudio();
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
            scheduleNext();
            return;
        }
        synchronized (this) {
            if (captureBusy) {
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
                            scheduleNext();
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
        scheduleNext();
        notifyUi();
    }

    private void scheduleNext() {
        if (!isRecording()) return;
        rec.removeCallbacks(tick);
        long now = SystemClock.elapsedRealtime();
        if (now >= deadline) {
            stopInternal("Saved");
            return;
        }
        long elapsed = now - startedAt;
        long slot = elapsed / FRAME_MS + 1L;
        long delay = startedAt + slot * FRAME_MS - now;
        if (delay < 1L) delay = 1L;
        rec.postDelayed(tick, delay);
    }

    private long nextPtsUs() {
        long pts = (SystemClock.elapsedRealtimeNanos() - originNanos) / 1000L;
        if (pts <= lastPtsUs) pts = lastPtsUs + 1L;
        lastPtsUs = pts;
        return pts;
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
        long pts = nextPtsUs();
        frameIndex++;
        codec.queueInputBuffer(inIx, 0, WIDTH * HEIGHT * 3 / 2, pts, 0);
        drain(false);
    }

    private void stopInternal(String why) {
        rec.removeCallbacks(tick);
        rec.removeCallbacks(finish);
        if (audioHandler != null) audioHandler.removeCallbacks(audioPump);
        boolean was = false;
        synchronized (this) {
            was = recording;
            recording = false;
        }
        stopAudioRecord();
        if (was && audioCodec != null) {
            try {
                int inIx = audioCodec.dequeueInputBuffer(100_000);
                if (inIx >= 0) {
                    audioCodec.queueInputBuffer(inIx, 0, 0, nextAudioPtsUs(),
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                }
                drainAudio(true);
            } catch (Exception ignored) {
            }
        }
        if (was && codec != null) {
            try {
                int inIx = codec.dequeueInputBuffer(100_000);
                if (inIx >= 0) {
                    codec.queueInputBuffer(inIx, 0, 0, nextPtsUs(),
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                }
                drain(true);
            } catch (Exception ignored) {
            }
        }
        File saved = outFile;
        releaseCodec();
        releaseAudio();
        synchronized (this) {
            if (saved != null && saved.isFile() && saved.length() > 0) {
                status = why + " Movies/" + saved.getName()
                        + " (" + (saved.length() / 1024) + " KB)";
                scanFile(saved);
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
                videoFormatReady = true;
                maybeStartMuxer();
                continue;
            }
            if (outIx < 0) continue;
            ByteBuffer out = codec.getOutputBuffer(outIx);
            if (out != null && bufInfo.size > 0 && muxing && muxer != null
                    && (bufInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                out.position(bufInfo.offset);
                out.limit(bufInfo.offset + bufInfo.size);
                synchronized (muxLock) {
                    if (muxing && muxer != null) muxer.writeSampleData(track, out, bufInfo);
                }
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
        audioTrack = -1;
        videoFormatReady = false;
        audioFormatReady = false;
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

    private void waitForMuxerTracks() {
        long until = SystemClock.elapsedRealtime() + 400L;
        while (SystemClock.elapsedRealtime() < until) {
            drain(false);
            drainAudio(false);
            if (videoFormatReady && (!useAudio || audioFormatReady)) break;
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                break;
            }
        }
        if (useAudio && !audioFormatReady) {
            useAudio = false;
            audioFormatReady = true;
            stopAudioRecord();
            releaseAudio();
        }
        maybeStartMuxer();
    }

    private void maybeStartMuxer() {
        synchronized (muxLock) {
            if (muxing || muxer == null) return;
            if (!videoFormatReady) return;
            if (useAudio && !audioFormatReady) return;
            try {
                track = muxer.addTrack(codec.getOutputFormat());
                if (useAudio && audioCodec != null) {
                    audioTrack = muxer.addTrack(audioCodec.getOutputFormat());
                } else {
                    audioTrack = -1;
                    useAudio = false;
                }
                muxer.start();
                muxing = true;
            } catch (Exception ignored) {
            }
        }
    }

    private boolean startAudio() {
        try {
            MediaFormat fmt = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_AAC, AUDIO_RATE, 1);
            fmt.setInteger(MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE);
            fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
            audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            audioCodec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            audioCodec.start();
            int min = AudioRecord.getMinBufferSize(AUDIO_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) min = 4096;
            audioPcm = new byte[Math.max(min, 4096)];
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, AUDIO_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    audioPcm.length * 2);
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                releaseAudio();
                return false;
            }
            audioRecord.startRecording();
            if (audioThread == null) {
                audioThread = new HandlerThread("rec-audio");
                audioThread.start();
                audioHandler = new Handler(audioThread.getLooper());
            }
            return true;
        } catch (Exception e) {
            releaseAudio();
            return false;
        }
    }

    private void pumpAudio() {
        if (!recording || !useAudio || audioRecord == null || audioPcm == null) return;
        int n = 0;
        try {
            n = audioRecord.read(audioPcm, 0, audioPcm.length);
        } catch (Exception e) {
            n = 0;
        }
        if (n > 0) encodeAudio(n);
        if (recording && useAudio && audioHandler != null) {
            audioHandler.post(audioPump);
        }
    }

    private void encodeAudio(int n) {
        if (audioCodec == null) return;
        try {
            int inIx = audioCodec.dequeueInputBuffer(0);
            if (inIx >= 0) {
                ByteBuffer in = audioCodec.getInputBuffer(inIx);
                if (in != null) {
                    in.clear();
                    int put = Math.min(n, in.remaining());
                    in.put(audioPcm, 0, put);
                    audioCodec.queueInputBuffer(inIx, 0, put, nextAudioPtsUs(), 0);
                }
            }
            drainAudio(false);
        } catch (Exception ignored) {
        }
    }

    private void drainAudio(boolean eos) {
        if (audioCodec == null) return;
        long until = SystemClock.elapsedRealtime() + (eos ? 800L : 0L);
        while (true) {
            int outIx = audioCodec.dequeueOutputBuffer(audioInfo, eos ? 30_000 : 0);
            if (outIx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!eos || SystemClock.elapsedRealtime() >= until) break;
                continue;
            }
            if (outIx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                audioFormatReady = true;
                maybeStartMuxer();
                continue;
            }
            if (outIx < 0) continue;
            ByteBuffer out = audioCodec.getOutputBuffer(outIx);
            if (out != null && audioInfo.size > 0 && muxing && muxer != null && audioTrack >= 0
                    && (audioInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                out.position(audioInfo.offset);
                out.limit(audioInfo.offset + audioInfo.size);
                synchronized (muxLock) {
                    if (muxing && muxer != null && audioTrack >= 0) {
                        muxer.writeSampleData(audioTrack, out, audioInfo);
                    }
                }
            }
            audioCodec.releaseOutputBuffer(outIx, false);
            if ((audioInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
        }
    }

    private long nextAudioPtsUs() {
        long pts = (SystemClock.elapsedRealtimeNanos() - originNanos) / 1000L;
        if (pts <= lastAudioPtsUs) pts = lastAudioPtsUs + 1L;
        lastAudioPtsUs = pts;
        return pts;
    }

    private void stopAudioRecord() {
        if (audioHandler != null) audioHandler.removeCallbacks(audioPump);
        if (audioRecord != null) {
            try {
                audioRecord.stop();
            } catch (Exception ignored) {
            }
            try {
                audioRecord.release();
            } catch (Exception ignored) {
            }
            audioRecord = null;
        }
    }

    private void releaseAudio() {
        stopAudioRecord();
        if (audioCodec != null) {
            try {
                audioCodec.stop();
            } catch (Exception ignored) {
            }
            try {
                audioCodec.release();
            } catch (Exception ignored) {
            }
            audioCodec = null;
        }
        audioPcm = null;
        audioFormatReady = false;
        lastAudioPtsUs = -1L;
    }

    private void notifyUi() {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (listener != null) listener.onRecorderChanged();
            }
        });
    }

    private static File publicMoviesDir() {
        File pub = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
        if (pub == null) pub = new File(Environment.getExternalStorageDirectory(), "Movies");
        if (pub.exists() || pub.mkdirs()) return pub;
        return null;
    }

    private static File moviesDir(Activity act) {
        File pub = publicMoviesDir();
        if (pub != null) return pub;
        File app = act.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (app != null && (app.exists() || app.mkdirs())) return app;
        return pub;
    }

    /** Older builds wrote rec-*.mp4 under Android/data/.../files/Movies. */
    private static void migrateOldMovies(Activity act) {
        File pub = publicMoviesDir();
        File app = act.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (pub == null || app == null || !app.isDirectory()) return;
        if (app.equals(pub)) return;
        File[] kids = app.listFiles();
        if (kids == null) return;
        for (int i = 0; i < kids.length; i++) {
            File src = kids[i];
            if (!src.isFile()) continue;
            String name = src.getName();
            if (!name.startsWith("rec-") || !name.endsWith(".mp4")) continue;
            File dest = new File(pub, name);
            if (dest.exists()) continue;
            if (src.renameTo(dest) || copyFile(src, dest)) {
                if (src.exists()) src.delete();
                scanFile(act, dest);
            }
        }
    }

    private void scanFile(File saved) {
        scanFile(activity, saved);
    }

    private static void scanFile(Activity act, File saved) {
        if (act == null || saved == null) return;
        try {
            MediaScannerConnection.scanFile(act,
                    new String[]{saved.getAbsolutePath()},
                    new String[]{"video/mp4"}, null);
        } catch (Exception ignored) {
        }
    }

    private static boolean copyFile(File src, File dest) {
        java.io.FileInputStream in = null;
        java.io.FileOutputStream out = null;
        try {
            in = new java.io.FileInputStream(src);
            out = new java.io.FileOutputStream(dest);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
            }
            out.flush();
            return dest.isFile() && dest.length() == src.length();
        } catch (Exception e) {
            if (dest.exists()) dest.delete();
            return false;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
            if (out != null) {
                try {
                    out.close();
                } catch (Exception ignored) {
                }
            }
        }
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
