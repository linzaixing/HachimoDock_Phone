package com.example.hachimophone;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
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

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.gpu.GpuDelegate;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * 轻量版视觉模块：
 *   1. 按需定时留存一帧摆正的 JPEG，给智谱免费 VLM（glm-4v-flash）看图问答；
 *   2. 开启人脸追踪时用 ML Kit 人脸检测（捆绑模型，各机型一致）定位人脸中心，
 *      人脸连续丢失时再用 2.9MB 量化 MoveNet 做人体姿态兜底，驱动 ESP32-C3 云台水平跟随。
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

    /** 预览上要画的框：坐标是摆正后画面的 0~1；primary=当前跟踪主体 */
    static final class Box {
        final float l;
        final float t;
        final float r;
        final float b;
        final boolean primary;

        Box(float l, float t, float r, float b, boolean primary) {
            this.l = l;
            this.t = t;
            this.r = r;
            this.b = b;
            this.primary = primary;
        }
    }

    private static final String TAG = "HachimoPhone";
    private static final long TRACK_INTERVAL_MS = 100;
    /** 人脸连续丢失这么久才切人体姿态；偶尔漏检时保持上一帧目标，别让云台立刻回中 */
    private static final long LOST_HOLD_MS = 1500;
    private static final String POSE_MODEL = "movenet_lightning_int8.tflite";
    private static final int POSE_SIZE = 192;
    private static final int POSE_KP = 17;

    private final LifecycleRegistry lifecycle = new LifecycleRegistry(this);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService poseExecutor = Executors.newSingleThreadExecutor();
    private final boolean trackFace;

    private volatile byte[] latestJpeg;
    private volatile long streamStartedAt;
    private long lastJpegAt;
    private long lastTrackAt;
    private long lastFaceSeenAt;
    private long lastPoseSeenAt;
    private volatile boolean trackBusy;
    private volatile java.util.List<Box> boxes = java.util.Collections.emptyList();
    private volatile int frameW = 640;
    private volatile int frameH = 480;
    private int lastBoxCount = -1;

    private ProcessCameraProvider provider;
    private FaceDetector detector;
    private volatile boolean running;

    private volatile Context appContext;
    private final Object poseLock = new Object();
    private volatile Interpreter poseInterpreter;
    private volatile GpuDelegate poseGpuDelegate;
    private ByteBuffer poseInput;
    private float[][][][] poseOutput;
    private int[] posePixels;

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

    /** 最近一帧检测到的框：跟踪主体 primary=true */
    java.util.List<Box> boxes() {
        return boxes;
    }

    int frameWidth() {
        return frameW;
    }

    int frameHeight() {
        return frameH;
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
        appContext = activity.getApplicationContext();
        streamStartedAt = SystemClock.elapsedRealtime();
        frontCamera = front;
        previewView = preview;
        if (trackFace) {
            detector = FaceDetection.getClient(new FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                    .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                    .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
                    .setMinFaceSize(0.12f)
                    .build());
        }
        lifecycle.setCurrentState(Lifecycle.State.STARTED);
        ProcessCameraProvider.getInstance(activity).addListener(() -> {
            try {
                provider = ProcessCameraProvider.getInstance(activity).get();
                bind(front);
                Log.i(TAG, "vision start " + (front ? "front" : "back") + " camera");
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
                // 预览和分析用同一档 4:3，叠加的跟踪框才能按同一比例对齐
                Preview preview = new Preview.Builder()
                        .setResolutionSelector(resolution)
                        .build();
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
        boolean wantTrack = trackFace && detector != null && !trackBusy
                && now - lastTrackAt >= TRACK_INTERVAL_MS;
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
            trackBusy = true;
            detectTargets(upright);       // 异步检测，结束后由它负责回收
        } else {
            upright.recycle();
        }
    }

    /** 人脸优先；人脸连续丢失超过 LOST_HOLD_MS 时，同一帧再跑人体姿态兜底 */
    private void detectTargets(Bitmap upright) {
        FaceDetector fd = detector;
        if (fd == null) {
            trackBusy = false;
            upright.recycle();
            return;
        }
        final int w = upright.getWidth();
        final int h = upright.getHeight();
        final boolean allowPose =
                SystemClock.elapsedRealtime() - lastFaceSeenAt >= LOST_HOLD_MS;
        final InputImage image;
        try {
            image = InputImage.fromBitmap(upright, 0);
        } catch (Exception e) {
            Log.w(TAG, "frame input failed: " + e.getMessage());
            trackBusy = false;
            upright.recycle();
            return;
        }
        final boolean[] faceFound = {false};
        try {
            fd.process(image)
                    .addOnSuccessListener(faces -> {
                        if (running) faceFound[0] = onFaces(faces, w, h);
                    })
                    .addOnFailureListener(e -> Log.w(TAG, "face detect failed: " + e.getMessage()))
                    .addOnCompleteListener(t -> {
                        if (!running || !allowPose || faceFound[0]) {
                            trackBusy = false;
                            upright.recycle();
                            return;
                        }
                        try {
                            poseExecutor.execute(() -> {
                                try {
                                    if (running) runPose(upright);
                                } catch (Throwable e) {
                                    Log.w(TAG, "pose failed: " + e.getMessage());
                                } finally {
                                    upright.recycle();
                                    trackBusy = false;
                                }
                            });
                        } catch (RejectedExecutionException e) {
                            trackBusy = false;
                            upright.recycle();
                        }
                    });
        } catch (Exception e) {
            Log.w(TAG, "face detect start failed: " + e.getMessage());
            trackBusy = false;
            upright.recycle();
        }
    }

    private boolean onFaces(List<Face> faces, int w, int h) {
        Face best = null;
        long bestArea = 0;
        if (faces != null) {
            for (Face f : faces) {
                Rect b = f.getBoundingBox();
                long area = (long) Math.max(0, b.width()) * Math.max(0, b.height());
                if (area > bestArea) {
                    bestArea = area;
                    best = f;
                }
            }
        }
        frameW = w;
        frameH = h;
        long now = SystemClock.elapsedRealtime();
        if (best == null) {
            boxes = java.util.Collections.emptyList();
            logBoxCount(0);
            if (now - Math.max(lastFaceSeenAt, lastPoseSeenAt) > LOST_HOLD_MS) {
                target = new Target(System.currentTimeMillis(), false, 0.5f, 0.5f);
            }
            return false;
        }
        java.util.List<Box> out = new java.util.ArrayList<>();
        for (Face f : faces) {
            Rect b = f.getBoundingBox();
            float l = b.left / (float) Math.max(1, w);
            float r = b.right / (float) Math.max(1, w);
            float t = b.top / (float) Math.max(1, h);
            float bt = b.bottom / (float) Math.max(1, h);
            if (frontCamera) {
                float nl = 1f - r;
                r = 1f - l;
                l = nl;
            }
            out.add(new Box(clamp01(l), clamp01(t), clamp01(r), clamp01(bt), f == best));
        }
        boxes = out;
        logBoxCount(out.size());
        Rect b = best.getBoundingBox();
        float nx = clamp01((b.left + b.right) / 2f / Math.max(1, w));
        float ny = clamp01((b.top + b.bottom) / 2f / Math.max(1, h));
        if (frontCamera) nx = 1f - nx;
        smoothX = smoothX < 0 ? nx : smoothX * 0.7f + nx * 0.3f;
        smoothY = smoothY < 0 ? ny : smoothY * 0.7f + ny * 0.3f;
        lastFaceSeenAt = now;
        target = new Target(System.currentTimeMillis(), true, smoothX, smoothY);
        return true;
    }

    /** MoveNet 人体姿态兜底：取两肩中点（没有肩再看鼻子/髋）作为水平中心 */
    private void runPose(Bitmap frame) {
        if (!ensurePose()) return;
        Interpreter it = poseInterpreter;
        ByteBuffer in = poseInput;
        float[][][][] out = poseOutput;
        int[] pixels = posePixels;
        if (it == null || in == null || out == null || pixels == null) return;
        Bitmap scaled = Bitmap.createScaledBitmap(frame, POSE_SIZE, POSE_SIZE, true);
        scaled.getPixels(pixels, 0, POSE_SIZE, 0, 0, POSE_SIZE, POSE_SIZE);
        if (scaled != frame) scaled.recycle();
        in.rewind();
        for (int p : pixels) {
            in.put((byte) ((p >> 16) & 0xff));
            in.put((byte) ((p >> 8) & 0xff));
            in.put((byte) (p & 0xff));
        }
        in.rewind();
        try {
            it.run(in, out);
        } catch (Exception e) {
            Log.w(TAG, "pose run failed: " + e.getMessage());
            return;
        }
        if (!running) return;
        frameW = frame.getWidth();
        frameH = frame.getHeight();
        // MoveNet 输出 17 个关键点：[y, x, score]，坐标已归一化到 0~1
        float nx;
        float ny;
        float ls = out[0][0][5][2];
        float rs = out[0][0][6][2];
        if (ls > 0.3f && rs > 0.3f) {
            nx = (out[0][0][5][1] + out[0][0][6][1]) / 2f;
            ny = (out[0][0][5][0] + out[0][0][6][0]) / 2f;
        } else if (out[0][0][0][2] > 0.3f) {
            nx = out[0][0][0][1];
            ny = out[0][0][0][0];
        } else if (out[0][0][11][2] > 0.3f && out[0][0][12][2] > 0.3f) {
            nx = (out[0][0][11][1] + out[0][0][12][1]) / 2f;
            ny = (out[0][0][11][0] + out[0][0][12][0]) / 2f;
        } else {
            return;
        }
        // 用所有可信关键点围出人体框；当前跟踪主体就是它，画蓝框
        float minX = 1f;
        float maxX = 0f;
        float minY = 1f;
        float maxY = 0f;
        boolean any = false;
        for (int i = 0; i < POSE_KP; i++) {
            if (out[0][0][i][2] < 0.3f) continue;
            float kx = out[0][0][i][1];
            float ky = out[0][0][i][0];
            if (kx < minX) minX = kx;
            if (kx > maxX) maxX = kx;
            if (ky < minY) minY = ky;
            if (ky > maxY) maxY = ky;
            any = true;
        }
        if (any) {
            float l = clamp01(minX - 0.02f);
            float r = clamp01(maxX + 0.02f);
            float t = clamp01(minY - 0.02f);
            float b = clamp01(maxY + 0.02f);
            if (frontCamera) {
                float nl = 1f - r;
                r = 1f - l;
                l = nl;
            }
            boxes = java.util.Collections.singletonList(new Box(l, t, r, b, true));
        }
        nx = clamp01(nx);
        ny = clamp01(ny);
        if (frontCamera) nx = 1f - nx;
        smoothX = smoothX < 0 ? nx : smoothX * 0.7f + nx * 0.3f;
        smoothY = smoothY < 0 ? ny : smoothY * 0.7f + ny * 0.3f;
        lastPoseSeenAt = SystemClock.elapsedRealtime();
        target = new Target(System.currentTimeMillis(), true, smoothX, smoothY);
    }

    private void logBoxCount(int n) {
        if (n != lastBoxCount) {
            lastBoxCount = n;
            Log.i(TAG, "boxes=" + n);
        }
    }

    /** 调试用：预加载模型并跑 10 次空帧，确认 GPU/CPU 和单帧耗时 */
    void benchmarkPose() {
        try {
            poseExecutor.execute(() -> {
                if (!ensurePose()) return;
                Interpreter it = poseInterpreter;
                ByteBuffer in = poseInput;
                float[][][][] out = poseOutput;
                int[] px = posePixels;
                if (it == null || in == null || out == null || px == null) return;
                java.util.Arrays.fill(px, 0xFF808080);
                in.rewind();
                for (int p : px) {
                    in.put((byte) ((p >> 16) & 0xff));
                    in.put((byte) ((p >> 8) & 0xff));
                    in.put((byte) (p & 0xff));
                }
                long total = 0;
                int n = 10;
                for (int i = 0; i < n; i++) {
                    in.rewind();
                    long t0 = System.nanoTime();
                    try {
                        it.run(in, out);
                    } catch (Throwable e) {
                        Log.w(TAG, "pose bench failed: " + e.getMessage());
                        return;
                    }
                    total += System.nanoTime() - t0;
                }
                Log.i(TAG, "pose bench avg=" + (total / 1_000_000f / n)
                        + "ms gpu=" + (poseGpuDelegate != null));
            });
        } catch (RejectedExecutionException ignored) {
        }
    }

    private static ByteBuffer modelBuffer(byte[] bytes) {
        ByteBuffer b = ByteBuffer.allocateDirect(bytes.length).order(ByteOrder.nativeOrder());
        b.put(bytes).rewind();
        return b;
    }

    /** 首次用到人体兜底时才加载 2.9MB 的量化 MoveNet，不在启动时占内存 */
    private boolean ensurePose() {        if (poseInterpreter != null) return true;
        Context c = appContext;
        if (c == null) return false;
        try {
            byte[] bytes;
            try (InputStream is = c.getAssets().open(POSE_MODEL)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream(is.available());
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
                bytes = out.toByteArray();
            }
            ByteBuffer model = modelBuffer(bytes);
            Interpreter.Options options = new Interpreter.Options();
            GpuDelegate gpu = null;
            try {
                gpu = new GpuDelegate();
                options.addDelegate(gpu);
                Log.i(TAG, "movenet gpu delegate on");
            } catch (Throwable e) {
                gpu = null;
                Log.w(TAG, "movenet gpu unavailable, cpu fallback: " + e.getMessage());
            }
            Interpreter it;
            try {
                it = new Interpreter(model, options);
            } catch (Throwable e) {
                if (gpu != null) {
                    try {
                        gpu.close();
                    } catch (Exception ignored) {
                    }
                    gpu = null;
                }
                it = new Interpreter(modelBuffer(bytes), new Interpreter.Options());
                Log.w(TAG, "movenet gpu init failed, cpu fallback: " + e.getMessage());
            }
            synchronized (poseLock) {
                if (!running) {
                    it.close();
                    if (gpu != null) gpu.close();
                    return false;
                }
                poseInput = ByteBuffer.allocateDirect(POSE_SIZE * POSE_SIZE * 3)
                        .order(ByteOrder.nativeOrder());
                poseOutput = new float[1][1][POSE_KP][3];
                posePixels = new int[POSE_SIZE * POSE_SIZE];
                poseInterpreter = it;
                poseGpuDelegate = gpu;
            }
            Log.i(TAG, "movenet ready");
            return true;
        } catch (Exception e) {
            Log.w(TAG, "movenet load failed: " + e.getMessage());
            return false;
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
        FaceDetector d = detector;
        detector = null;
        if (d != null) {
            try {
                d.close();
            } catch (Exception ignored) {
            }
        }
        final Interpreter it;
        final GpuDelegate gpu;
        synchronized (poseLock) {
            it = poseInterpreter;
            gpu = poseGpuDelegate;
            poseInterpreter = null;
            poseGpuDelegate = null;
            poseInput = null;
            poseOutput = null;
            posePixels = null;
        }
        if (it != null || gpu != null) {
            // 和姿态推理跑在同一个单线程里排队关闭，避免 run/close 并发
            try {
                poseExecutor.execute(() -> {
                    try {
                        if (it != null) it.close();
                    } catch (Exception e) {
                        Log.w(TAG, "movenet close failed: " + e.getMessage());
                    }
                    try {
                        if (gpu != null) gpu.close();
                    } catch (Exception ignored) {
                    }
                });
            } catch (RejectedExecutionException e) {
                try {
                    if (it != null) it.close();
                } catch (Exception ignored) {
                }
                try {
                    if (gpu != null) gpu.close();
                } catch (Exception ignored) {
                }
            }
        }
        trackBusy = false;
        executor.shutdown();
        poseExecutor.shutdown();
        previewView = null;
        latestJpeg = null;
        target = new Target(System.currentTimeMillis(), false, 0.5f, 0.5f);
        Log.i(TAG, "vision stop");
    }
}
