package com.example.hachimophone;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
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
import com.google.mlkit.vision.label.ImageLabel;
import com.google.mlkit.vision.label.ImageLabeler;
import com.google.mlkit.vision.label.ImageLabeling;
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions;
import com.google.mlkit.vision.pose.Pose;
import com.google.mlkit.vision.pose.PoseDetection;
import com.google.mlkit.vision.pose.PoseDetector;
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions;
import com.google.mlkit.vision.pose.PoseLandmark;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 对话期间的视觉：本地做姿态跟踪 + 图像标签（不联网、不花钱）。
 *
 * 人体中心换算成 0~1 的归一化坐标，给以后的 ESP32-C3 云台（XY 两轴）用；
 * 人脸识别和场景理解分别由 {@link FaceMemory}、手机/PC 端的云端视觉按需处理。
 */
final class VisionTracker implements LifecycleOwner {

    static final class Target {
        final long ts;
        final boolean found;
        /** 画面里的归一化坐标：0 左/上，1 右/下 */
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
    /** 姿态检测最多 5fps：再高对云台没有意义，还白烧电 */
    private static final long POSE_INTERVAL_MS = 200;
    /** 图像标签 1.2 秒一次就够 */
    private static final long LABEL_INTERVAL_MS = 1200;
    /** 人脸识别 0.8 秒一次：够快认识人，也不会把 CPU 占满 */
    private static final long FACE_INTERVAL_MS = 800;

    private final LifecycleRegistry lifecycle = new LifecycleRegistry(this);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    /** 人脸识别（可空）：模型没下载好或用户没启用时只做姿态 */
    private final FaceMemory faces;
    /** 本地 ML Kit（姿态/标签/人脸）开关：关掉后只保留最近一帧，留给按需发 VLM */
    private final boolean mlkit;
    /** 关掉本地识别时保留的最近一帧 JPEG */
    private volatile byte[] latestJpeg;
    private long lastJpegAt;
    /** 相机会话启动时刻：前 800ms 的画面常是过曝/全白，先不用 */
    private volatile long streamStartedAt;

    private ProcessCameraProvider provider;
    private PoseDetector poseDetector;
    private ImageLabeler labeler;
    private volatile boolean running;

    private long lastPoseAt;
    private long lastLabelAt;
    private long lastFaceAt;
    private float smoothX = -1;
    private float smoothY = -1;
    /** 用的是前置（自拍）摄像头：画面是镜像的，x 要反过来 */
    private volatile boolean frontCamera;
    /** 页面倒立（整块内容转了 180°）：画面里的人体方向也要跟着转 */
    private volatile boolean flipped;

    private volatile Target target = new Target(0, false, 0.5f, 0.5f);
    /** 最近的场景标签，例如「室内 客厅 人」 */
    private volatile String labels = "";

    @NonNull
    @Override
    public Lifecycle getLifecycle() {
        return lifecycle;
    }

    VisionTracker(FaceMemory faces, boolean mlkit) {
        this.faces = faces;
        this.mlkit = mlkit;
    }

    /** 最近一帧（JPEG，可能为 null）：给「看一眼」这类按需视觉请求用 */
    byte[] latestJpeg() {
        return latestJpeg;
    }

    Target target() {
        return target;
    }

    String labels() {
        return labels;
    }

    /** 当前画面里认出来的人名（认不出为空） */
    String faceName() {
        return faces == null ? "" : faces.currentName();
    }

    /** 页面倒立 / 转回来时通知一下，坐标映射要跟着变 */
    void setFlipped(boolean value) {
        if (flipped == value) return;
        flipped = value;
        smoothX = -1;
        smoothY = -1;
        // 旧帧方向已经不对了，丢掉等下一帧（≤1 秒）
        latestJpeg = null;
    }

    /** 调试用的小预览窗（可空）：不为空就同时绑 Preview，把画面显示出来 */
    private PreviewView previewView;

    /**
     * @param front true 用前置（自拍）摄像头：内屏打开时用；
     *              false 用后置（外置）摄像头：外屏打开时用
     * @param preview 调试预览窗，可传 null
     */
    void start(Activity activity, boolean front, PreviewView preview) {
        if (running) return;
        running = true;
        streamStartedAt = SystemClock.elapsedRealtime();
        frontCamera = front;
        previewView = preview;
        if (mlkit) {
            poseDetector = PoseDetection.getClient(
                    new PoseDetectorOptions.Builder()
                            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
                            .build());
            labeler = ImageLabeling.getClient(
                    new ImageLabelerOptions.Builder().setConfidenceThreshold(0.55f).build());
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
        // 相机服务回调是异步的：期间可能已经 stop（页面短暂 pause），别在已销毁的 lifecycle 上 bind
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
        if (!mlkit) {
            // 本地识别关掉：每秒留一帧 JPEG，给「看看我现在的手势」这类按需视觉请求
            if (now - streamStartedAt < 800) {
                proxy.close();
                return;
            }
            if (now - streamStartedAt >= 800 && now - lastJpegAt >= 1000) {
                lastJpegAt = now;
                try {
                    int rotation = proxy.getImageInfo().getRotationDegrees();
                    Bitmap b = toBitmap(proxy);
                    int deg = (rotation + (flipped ? 180 : 0)) % 360;
                    if (deg != 0) {
                        Bitmap r = rotate(b, deg);
                        b.recycle();
                        b = r;
                    }
                    if (isBlank(b)) {
                        Log.i(TAG, "skip blank frame");
                    } else {
                        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                        b.compress(Bitmap.CompressFormat.JPEG, 70, out);
                        latestJpeg = out.toByteArray();
                    }
                    b.recycle();
                } catch (Exception e) {
                    Log.w(TAG, "snapshot failed: " + e.getMessage());
                }
            }
            proxy.close();
            return;
        }
        if (now - lastPoseAt < POSE_INTERVAL_MS) {
            proxy.close();
            return;
        }
        lastPoseAt = now;
        final int rotation = proxy.getImageInfo().getRotationDegrees();
        // ML Kit 的坐标是「摆正后」的图：90/270 度时宽高要对调
        final int w = (rotation == 90 || rotation == 270) ? proxy.getHeight() : proxy.getWidth();
        final int h = (rotation == 90 || rotation == 270) ? proxy.getWidth() : proxy.getHeight();
        final Bitmap frame;
        try {
            frame = toBitmap(proxy);
        } catch (Exception e) {
            Log.w(TAG, "frame to bitmap failed: " + e.getMessage());
            proxy.close();
            return;
        }
        proxy.close();

        // 每秒留一帧给「看一眼」类视觉请求（ML Kit 开着也照样留）
        if (now - lastJpegAt >= 1000) {
            lastJpegAt = now;
            try {
                Bitmap snap = frame;
                int snapDeg = (rotation + (flipped ? 180 : 0)) % 360;
                if (snapDeg != 0) snap = rotate(frame, snapDeg);
                if (isBlank(snap)) {
                    Log.i(TAG, "skip blank frame");
                } else {
                    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                    snap.compress(Bitmap.CompressFormat.JPEG, 70, out);
                    latestJpeg = out.toByteArray();
                }
                if (snap != frame) snap.recycle();
            } catch (Exception e) {
                Log.w(TAG, "snapshot failed: " + e.getMessage());
            }
        }

        final boolean wantLabels = now - lastLabelAt >= LABEL_INTERVAL_MS;
        final boolean wantFace = faces != null && now - lastFaceAt >= FACE_INTERVAL_MS;
        if (wantLabels) lastLabelAt = now;
        if (wantFace) lastFaceAt = now;
        InputImage upright = InputImage.fromBitmap(frame, rotation);

        poseDetector.process(upright)
                .addOnSuccessListener(pose -> updateTarget(pose, w, h))
                .addOnFailureListener(e -> Log.w(TAG, "pose failed: " + e.getMessage()))
                .addOnCompleteListener(t -> {
                    if (!running) {
                        frame.recycle();
                        return;
                    }
                    if (wantLabels) {
                        labeler.process(upright)
                                .addOnSuccessListener(this::updateLabels)
                                .addOnCompleteListener(x -> runFace(frame, rotation, wantFace));
                    } else {
                        runFace(frame, rotation, wantFace);
                    }
                });
    }

    /** 人脸检测 + 特征提取（在 FaceMemory 的后台线程里跑），跑完再回收 Bitmap */
    private void runFace(Bitmap frame, int rotation, boolean want) {
        if (!want || faces == null || !running) {
            frame.recycle();
            return;
        }
        Bitmap upright = rotation == 0 ? frame : rotate(frame, rotation);
        faces.analyze(upright, () -> {
            if (upright != frame) upright.recycle();
            frame.recycle();
        });
    }

    /** RGBA_8888 的 ImageProxy → Bitmap（自己拷一份，就能立刻 close 掉 proxy） */
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

    /** 粗判空白帧：过曝/全黑的画面交给 VLM 只会胡编，直接跳过 */
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

    private void updateTarget(Pose pose, int w, int h) {
        // 优先用鼻子/肩中点：人走开时背景里别的东西不会把云台带偏
        PoseLandmark nose = pose.getPoseLandmark(PoseLandmark.NOSE);
        PoseLandmark ls = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER);
        PoseLandmark rs = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER);
        float x;
        float y;
        if (ls != null && rs != null) {
            x = (ls.getPosition().x + rs.getPosition().x) / 2f;
            y = (nose != null ? nose.getPosition().y
                    : (ls.getPosition().y + rs.getPosition().y) / 2f);
        } else if (nose != null) {
            x = nose.getPosition().x;
            y = nose.getPosition().y;
        } else {
            setTarget(false, 0.5f, 0.5f);
            return;
        }
        float nx = clamp01(x / w);
        float ny = clamp01(y / h);
        // 前置画面是镜像的；倒立时画面又整体转了 180°。把这两件事折算掉，
        // 不管内屏还是外屏、正着拿还是倒着拿，云台都朝「人实际所在的方向」转
        if (frontCamera != flipped) nx = 1f - nx;
        if (flipped) ny = 1f - ny;
        // 平滑：舵机跟踪时不会因为检测抖动一直来回摆
        smoothX = smoothX < 0 ? nx : smoothX * 0.7f + nx * 0.3f;
        smoothY = smoothY < 0 ? ny : smoothY * 0.7f + ny * 0.3f;
        setTarget(true, smoothX, smoothY);
    }

    private void setTarget(boolean found, float x, float y) {
        Target t = new Target(System.currentTimeMillis(), found, x, y);
        target = t;
    }

    private void updateLabels(List<ImageLabel> list) {
        if (list == null || list.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        int n = Math.min(3, list.size());
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(' ');
            sb.append(list.get(i).getText());
        }
        labels = sb.toString();
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
            /* 不在主线程时可能抛异常，忽略即可 */
        }
        if (provider != null) {
            try {
                provider.unbindAll();
            } catch (Exception ignored) {
            }
            provider = null;
        }
        if (poseDetector != null) {
            poseDetector.close();
            poseDetector = null;
        }
        if (labeler != null) {
            labeler.close();
            labeler = null;
        }
        executor.shutdown();
        previewView = null;
        latestJpeg = null;
        target = new Target(System.currentTimeMillis(), false, 0.5f, 0.5f);
        Log.i(TAG, "vision stop");
    }
}
