package com.example.hachimophone;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.PointF;
import android.media.FaceDetector;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 轻量版视觉模块（不依赖 ML Kit / ONNX Runtime，节省约 66MB 安装包体积）：
 *   1. 按需定时留存一帧摆正的 JPEG，给智谱免费 VLM（glm-4v-flash）看图问答；
 *   2. 开启视觉跟踪时用 Android 系统内置 FaceDetector 做人脸中心定位，驱动 ESP32-C3 云台。
 */
final class VisionTracker implements LifecycleOwner {

    static final class Target {
        final long ts;
        final boolean found;
        final float x;
        final float y;

        Target(long ts, boolean found, float x, float y) {
            this.ts = ts;
            this.found = found;
            this.x = x;
            this.y = y;
        }
    }

    private static final String TAG = "HachimoPhone";
    private static final long TRACK_INTERVAL_MS = 250;
    private static final int DET_W = 160;
    private static final int DET_H = 120;

    private final LifecycleRegistry lifecycle = new LifecycleRegistry(this);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final boolean trackFace;

    private volatile byte[] latestJpeg;
    private volatile long streamStartedAt;
    private long lastJpegAt;
    private long lastTrackAt;

    private ProcessCameraProvider provider;
    private FaceDetector detector;
    private final FaceDetector.Face[] faceBuf = new FaceDetector.Face[1];
    private volatile boolean running;

    private float smoothX = -1;
    private float smoothY = -1;
    private volatile boolean frontCamera;
    private volatile boolean flipped;
    private volatile Target target = new Target(0, false, 0.5f, 0.5f);
    private PreviewView previewView;

    @NonNull
    @Override
    public Lifecycle getLifecycle() {
        return lifecycle;
    }

    VisionTracker(FaceMemory faces, boolean mlkit) {
        this.trackFace = mlkit;
    }

    byte[] latestJpeg() {
        return latestJpeg;
    }

    Target target() {
        return target;
    }

    String labels() {
        return "";
    }

    String faceName() {
        return "";
    }

    void setFlipped(boolean value) {
        if (flipped == value) return;
        flipped = value;
        smoothX = -1;
        smoothY = -1;
        latestJpeg = null;
    }

    void start(Activity activity, boolean front, PreviewView preview) {
        if (running) return;
        running = true;
        streamStartedAt = SystemClock.elapsedRealtime();
        frontCamera = front;
        previewView = preview;
        if (trackFace) {
            detector = new FaceDetector(DET_W, DET_H, 1);
        }
        lifecycle.setCurrentState(Lifecycle.State.STARTED);
        ProcessCameraProvider.getInstance(activity).addListener(() -> {
            try {
                provider = ProcessCameraProvider.getInstance(activity).get();
                bind(front);
                Log.i(TAG, "vision(lite) start " + (front ? "front" : "back") + " camera");
            } catch (Exception e) {
                Log.e(TAG, "vision start failed", e);
            }
        }, ContextCompat.getMainExecutor(activity));
    }

    private void bind(boolean front) {
        if (provider == null || !running) return;
        provider.unbindAll();
        CameraSelector selector = front
                ? CameraSelector.DEFAULT_FRONT_CAMERA
                : CameraSelector.DEFAULT_BACK_CAMERA;
        try {
            if (!provider.hasCamera(selector)) {
                CameraSelector alt = front
                        ? CameraSelector.DEFAULT_BACK_CAMERA
                        : CameraSelector.DEFAULT_FRONT_CAMERA;
                if (provider.hasCamera(alt)) {
                    selector = alt;
                    frontCamera = !front;
                }
            }
        } catch (Exception ignored) {
        }

        ResolutionSelector resolution = new ResolutionSelector.Builder()
                .setResolutionStrategy(new ResolutionStrategy(
                        new android.util.Size(640, 480),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                .build();
        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(resolution)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build();
        analysis.setAnalyzer(executor, this::analyze);
        try {
            PreviewView pv = previewView;
            if (pv != null) {
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(pv.getSurfaceProvider());
                provider.bindToLifecycle(this, selector, preview, analysis);
            } else {
                provider.bindToLifecycle(this, selector, analysis);
            }
        } catch (Exception e) {
            Log.e(TAG, "vision bind failed", e);
        }
    }

    @androidx.annotation.OptIn(markerClass = androidx.camera.core.ExperimentalGetImage.class)
    private void analyze(@NonNull ImageProxy proxy) {
        long now = SystemClock.elapsedRealtime();
        if (!running || proxy.getImage() == null) {
            proxy.close();
            return;
        }
        if (now - streamStartedAt < 800) {
            proxy.close();
            return;
        }
        boolean wantSnap = now - lastJpegAt >= 1000;
        boolean wantTrack = trackFace && detector != null && now - lastTrackAt >= TRACK_INTERVAL_MS;
        if (!wantSnap && !wantTrack) {
            proxy.close();
            return;
        }

        final int rotation = proxy.getImageInfo().getRotationDegrees();
        final Bitmap raw;
        try {
            raw = toBitmap(proxy);
        } catch (Exception e) {
            Log.w(TAG, "frame to bitmap failed: " + e.getMessage());
            proxy.close();
            return;
        }
        proxy.close();

        int deg = (rotation + (flipped ? 180 : 0)) % 360;
        Bitmap upright = deg == 0 ? raw : rotate(raw, deg);
        if (upright != raw) raw.recycle();

        if (wantSnap) {
            lastJpegAt = now;
            try {
                if (isBlank(upright)) {
                    Log.i(TAG, "skip blank frame");
                } else {
                    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                    upright.compress(Bitmap.CompressFormat.JPEG, 70, out);
                    latestJpeg = out.toByteArray();
                }
            } catch (Exception e) {
                Log.w(TAG, "snapshot failed: " + e.getMessage());
            }
        }

        if (wantTrack) {
            lastTrackAt = now;
            detectBuiltInFace(upright);
        }
        upright.recycle();
    }

    private void detectBuiltInFace(Bitmap upright) {
        try {
            Bitmap scaled = Bitmap.createScaledBitmap(upright, DET_W, DET_H, true);
            Bitmap rgb565 = scaled.copy(Bitmap.Config.RGB_565, false);
            if (scaled != upright) scaled.recycle();
            if (rgb565 == null) return;
            int count = detector.findFaces(rgb565, faceBuf);
            rgb565.recycle();
            if (count <= 0 || faceBuf[0] == null) {
                target = new Target(System.currentTimeMillis(), false, 0.5f, 0.5f);
                return;
            }
            PointF mid = new PointF();
            faceBuf[0].getMidPoint(mid);
            float nx = clamp01(mid.x / DET_W);
            float ny = clamp01(mid.y / DET_H);
            if (frontCamera) nx = 1f - nx;
            smoothX = smoothX < 0 ? nx : smoothX * 0.7f + nx * 0.3f;
            smoothY = smoothY < 0 ? ny : smoothY * 0.7f + ny * 0.3f;
            target = new Target(System.currentTimeMillis(), true, smoothX, smoothY);
        } catch (Exception e) {
            Log.w(TAG, "builtin face detect failed: " + e.getMessage());
        }
    }

    private static Bitmap toBitmap(ImageProxy proxy) {
        ImageProxy.PlaneProxy plane = proxy.getPlanes()[0];
        java.nio.ByteBuffer buffer = plane.getBuffer();
        int width = proxy.getWidth();
        int height = proxy.getHeight();
        int rowStride = plane.getRowStride();
        Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        if (rowStride == width * 4) {
            bmp.copyPixelsFromBuffer(buffer);
        } else {
            byte[] row = new byte[width * 4];
            java.nio.ByteBuffer out = java.nio.ByteBuffer.allocateDirect(width * height * 4);
            for (int y = 0; y < height; y++) {
                buffer.position(y * rowStride);
                buffer.get(row, 0, row.length);
                out.put(row);
            }
            out.rewind();
            bmp.copyPixelsFromBuffer(out);
        }
        return bmp;
    }

    private static boolean isBlank(Bitmap b) {
        int w = b.getWidth();
        int h = b.getHeight();
        int min = 765;
        int max = 0;
        for (int gy = 0; gy < 12; gy++) {
            for (int gx = 0; gx < 12; gx++) {
                int c = b.getPixel(gx * (w - 1) / 11, gy * (h - 1) / 11);
                int lum = ((c >> 16) & 0xff) + ((c >> 8) & 0xff) + (c & 0xff);
                if (lum < min) min = lum;
                if (lum > max) max = lum;
            }
        }
        return max - min < 20;
    }

    private static Bitmap rotate(Bitmap src, int degrees) {
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.postRotate(degrees);
        return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
    }

    private static float clamp01(float v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    void stop() {
        if (!running) return;
        running = false;
        try {
            lifecycle.setCurrentState(Lifecycle.State.DESTROYED);
        } catch (Exception ignored) {
        }
        if (provider != null) {
            try {
                provider.unbindAll();
            } catch (Exception ignored) {
            }
            provider = null;
        }
        detector = null;
        executor.shutdown();
        previewView = null;
        latestJpeg = null;
        target = new Target(System.currentTimeMillis(), false, 0.5f, 0.5f);
        Log.i(TAG, "vision stop");
    }
}
