package com.decentricity.arlauncher;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.view.Surface;
import android.view.TextureView;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.Charset;
import java.util.Locale;

/**
 * Preview window on top of Files: image, looping video, or text.
 * Video plays through MediaPlayer into a hidden TextureView; both eyes copy
 * those frames onto the guide-locked window.
 */
final class FileViewer implements TextureView.SurfaceTextureListener,
        MediaPlayer.OnPreparedListener, MediaPlayer.OnErrorListener {
    static final int KIND_NONE = 0;
    static final int KIND_IMAGE = 1;
    static final int KIND_TEXT = 2;
    static final int KIND_VIDEO = 3;
    static final int KIND_OTHER = 4;

    interface Listener {
        void onViewerChanged();
    }

    private final RectF fitRect = new RectF();
    private static final int TEXT_MAX = 48 * 1024;
    private static final int IMAGE_MAX_EDGE = 1280;
    private static final int SINK_W = 1280;
    private static final int SINK_H = 640;

    private final Listener listener;
    private final Object frameLock = new Object();
    private TextureView sink;
    private Surface decoderSurface;
    private File file;
    private int kind = KIND_NONE;
    private String text = "";
    private String error = "";
    private Bitmap still;
    private Bitmap videoScratch;
    private MediaPlayer player;
    private boolean open;
    private boolean wantPlay;
    private boolean prepared;
    private boolean pendingPrepare;

    FileViewer(Listener listener) {
        this.listener = listener;
    }

    void attachSink(TextureView view) {
        sink = view;
        if (view == null) return;
        view.setOpaque(true);
        view.setSurfaceTextureListener(this);
        if (view.isAvailable()) {
            onSurfaceTextureAvailable(view.getSurfaceTexture(), view.getWidth(), view.getHeight());
        }
    }

    boolean isOpen() {
        return open;
    }

    int kind() {
        return kind;
    }

    boolean isPlaying() {
        try {
            return kind == KIND_VIDEO && player != null && player.isPlaying();
        } catch (Exception e) {
            return false;
        }
    }

    void togglePlay() {
        if (!open || kind != KIND_VIDEO || player == null) return;
        if (!prepared) {
            wantPlay = true;
            return;
        }
        try {
            if (player.isPlaying()) {
                wantPlay = false;
                player.pause();
            } else {
                wantPlay = true;
                player.start();
            }
        } catch (Exception ignored) {
        }
        notifyChanged();
    }

    String title() {
        if (file == null) return "Preview";
        return file.getName();
    }

    String text() {
        return text;
    }

    String error() {
        return error;
    }

    void open(File target) {
        closeInternal(false);
        if (target == null || !target.isFile()) {
            open = true;
            kind = KIND_OTHER;
            error = "Can't preview";
            notifyChanged();
            return;
        }
        file = target;
        open = true;
        error = "";
        text = "";
        kind = sniff(target);
        if (kind == KIND_IMAGE) {
            still = decodeImage(target);
            if (still == null) {
                kind = KIND_OTHER;
                error = "Can't decode image";
            }
        } else if (kind == KIND_TEXT) {
            text = readText(target);
            if (text.length() == 0) {
                kind = KIND_OTHER;
                error = "Empty or unreadable";
            }
        } else if (kind == KIND_VIDEO) {
            startVideo(target);
        } else {
            error = "Can't preview this file";
        }
        notifyChanged();
    }

    void close() {
        closeInternal(true);
    }

    void pause() {
        try {
            if (player != null && player.isPlaying()) player.pause();
        } catch (Exception ignored) {
        }
    }

    void resume() {
        if (!open || kind != KIND_VIDEO || player == null || !prepared || !wantPlay) return;
        try {
            player.start();
        } catch (Exception ignored) {
        }
        notifyChanged();
    }

    void release() {
        closeInternal(true);
        releaseSurface();
        sink = null;
    }

    void drawMedia(Canvas canvas, RectF dest, Paint paint) {
        if (kind == KIND_IMAGE && still != null && !still.isRecycled()) {
            fitDraw(canvas, still, dest, paint);
            return;
        }
        if (kind == KIND_VIDEO) {
            synchronized (frameLock) {
                if (videoScratch != null && !videoScratch.isRecycled()) {
                    fitDraw(canvas, videoScratch, dest, paint);
                }
            }
        }
    }

    private void fitDraw(Canvas canvas, Bitmap bmp, RectF dest, Paint paint) {
        if (bmp.getWidth() <= 0 || bmp.getHeight() <= 0) return;
        float bw = bmp.getWidth();
        float bh = bmp.getHeight();
        float scale = Math.min(dest.width() / bw, dest.height() / bh);
        float dw = bw * scale;
        float dh = bh * scale;
        float left = dest.centerX() - dw * 0.5f;
        float top = dest.centerY() - dh * 0.5f;
        fitRect.set(left, top, left + dw, top + dh);
        canvas.drawBitmap(bmp, null, fitRect, paint);
    }

    static int sniff(File f) {
        String name = f.getName();
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.US) : "";
        if (ext.equals("jpg") || ext.equals("jpeg") || ext.equals("png")
                || ext.equals("gif") || ext.equals("webp") || ext.equals("bmp")
                || ext.equals("wbmp")) {
            return KIND_IMAGE;
        }
        if (ext.equals("mp4") || ext.equals("m4v") || ext.equals("3gp")
                || ext.equals("3gpp") || ext.equals("webm") || ext.equals("mkv")
                || ext.equals("mov")) {
            return KIND_VIDEO;
        }
        if (ext.equals("txt") || ext.equals("md") || ext.equals("markdown")
                || ext.equals("log") || ext.equals("json") || ext.equals("xml")
                || ext.equals("html") || ext.equals("htm") || ext.equals("csv")
                || ext.equals("tsv") || ext.equals("properties") || ext.equals("conf")
                || ext.equals("cfg") || ext.equals("ini") || ext.equals("sh")
                || ext.equals("bash") || ext.equals("java") || ext.equals("c")
                || ext.equals("h") || ext.equals("cpp") || ext.equals("cc")
                || ext.equals("py") || ext.equals("js") || ext.equals("css")
                || ext.equals("yml") || ext.equals("yaml") || ext.equals("toml")
                || ext.equals("gradle") || ext.equals("mk") || ext.equals("cmake")
                || ext.equals("sql")) {
            return KIND_TEXT;
        }
        if (ext.length() == 0 && f.length() > 0 && f.length() <= TEXT_MAX) {
            return looksLikeText(f) ? KIND_TEXT : KIND_OTHER;
        }
        return KIND_OTHER;
    }

    private void startVideo(File target) {
        wantPlay = true;
        prepared = false;
        pendingPrepare = false;
        try {
            player = new MediaPlayer();
            player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            player.setDataSource(target.getAbsolutePath());
            player.setLooping(true);
            player.setOnPreparedListener(this);
            player.setOnErrorListener(this);
            if (sinkReady()) {
                bindSurface();
                player.prepareAsync();
            } else {
                pendingPrepare = true;
            }
        } catch (Exception e) {
            kind = KIND_OTHER;
            error = "Can't play video";
            releasePlayer();
        }
    }

    private boolean sinkReady() {
        return sink != null && sink.isAvailable() && sink.getSurfaceTexture() != null;
    }

    private void bindSurface() {
        if (player == null || !sinkReady()) return;
        SurfaceTexture st = sink.getSurfaceTexture();
        st.setDefaultBufferSize(SINK_W, SINK_H);
        releaseSurface();
        decoderSurface = new Surface(st);
        player.setSurface(decoderSurface);
    }

    private void grabSinkFrame() {
        if (!open || kind != KIND_VIDEO || sink == null || !sink.isAvailable()) return;
        int w = sink.getWidth();
        int h = sink.getHeight();
        if (w <= 0 || h <= 0) {
            w = SINK_W;
            h = SINK_H;
        }
        synchronized (frameLock) {
            if (videoScratch == null || videoScratch.isRecycled()
                    || videoScratch.getWidth() != w || videoScratch.getHeight() != h) {
                if (videoScratch != null) videoScratch.recycle();
                videoScratch = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            }
            Bitmap got = sink.getBitmap(videoScratch);
            if (got == null) return;
        }
        notifyChanged();
    }

    @Override
    public void onPrepared(MediaPlayer mp) {
        if (player != mp) return;
        prepared = true;
        try {
            if (wantPlay) mp.start();
        } catch (Exception ignored) {
        }
        grabSinkFrame();
        notifyChanged();
    }

    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        error = "Can't play video";
        notifyChanged();
        return true;
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        surface.setDefaultBufferSize(SINK_W, SINK_H);
        if (player == null) return;
        bindSurface();
        if (pendingPrepare) {
            pendingPrepare = false;
            try {
                player.prepareAsync();
            } catch (Exception e) {
                error = "Can't play video";
                notifyChanged();
            }
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        surface.setDefaultBufferSize(SINK_W, SINK_H);
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        if (player != null) {
            try {
                player.setSurface(null);
            } catch (Exception ignored) {
            }
        }
        releaseSurface();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        if (open && kind == KIND_VIDEO) grabSinkFrame();
    }

    private void closeInternal(boolean notify) {
        open = false;
        wantPlay = false;
        prepared = false;
        pendingPrepare = false;
        kind = KIND_NONE;
        file = null;
        text = "";
        error = "";
        releasePlayer();
        if (still != null) {
            still.recycle();
            still = null;
        }
        synchronized (frameLock) {
            if (videoScratch != null) {
                videoScratch.recycle();
                videoScratch = null;
            }
        }
        if (notify) notifyChanged();
    }

    private void releasePlayer() {
        if (player != null) {
            try {
                player.setSurface(null);
            } catch (Exception ignored) {
            }
            try {
                player.reset();
            } catch (Exception ignored) {
            }
            try {
                player.release();
            } catch (Exception ignored) {
            }
            player = null;
        }
        prepared = false;
        pendingPrepare = false;
    }

    private void releaseSurface() {
        if (decoderSurface != null) {
            try {
                decoderSurface.release();
            } catch (Exception ignored) {
            }
            decoderSurface = null;
        }
    }

    private void notifyChanged() {
        if (listener != null) listener.onViewerChanged();
    }

    private static Bitmap decodeImage(File f) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), bounds);
        int w = bounds.outWidth;
        int h = bounds.outHeight;
        if (w <= 0 || h <= 0) return null;
        int sample = 1;
        while (w / sample > IMAGE_MAX_EDGE || h / sample > IMAGE_MAX_EDGE) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
    }

    private static String readText(File f) {
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            long len = f.length();
            int n = (int) Math.min(len, TEXT_MAX);
            byte[] buf = new byte[n];
            int got = 0;
            while (got < n) {
                int r = in.read(buf, got, n - got);
                if (r < 0) break;
                got += r;
            }
            if (got <= 0) return "";
            int nul = 0;
            int printable = 0;
            for (int i = 0; i < got; i++) {
                int b = buf[i] & 0xFF;
                if (b == 0) nul++;
                else if (b == 9 || b == 10 || b == 13 || (b >= 32 && b < 127) || b >= 128) {
                    printable++;
                }
            }
            if (nul > got / 20) return "";
            if (printable < got * 0.8f) return "";
            String s = new String(buf, 0, got, Charset.forName("UTF-8"));
            if (len > TEXT_MAX) s = s + "\n…";
            return s;
        } catch (Exception e) {
            return "";
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static boolean looksLikeText(File f) {
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] buf = new byte[512];
            int got = in.read(buf);
            if (got <= 0) return false;
            int nul = 0;
            for (int i = 0; i < got; i++) {
                if (buf[i] == 0) nul++;
            }
            return nul == 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
