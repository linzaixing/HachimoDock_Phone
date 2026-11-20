package com.example.hachimophone;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.util.Log;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;
import com.google.mlkit.vision.face.FaceLandmark;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/**
 * 本地人脸记忆：ML Kit 检测人脸 + ArcFace(MobileFaceNet) 提特征 + 余弦相似度认人。
 *
 * 模型不随 APK 打包（APK 已经很大），首次对话时从 hf-mirror 下载 13MB 到应用私有目录，
 * 之后完全离线。认不出的人不会上传任何图片。
 */
final class FaceMemory {

    interface Ready {
        void onReady(boolean ok);
    }

    private static final String TAG = "HachimoPhone";
    private static final String PREF = "face_gallery";
    /**
     * 优先用轻量版（buffalo_s / MobileFaceNet，13MB），兼容低存储/慢网速手机；
     * 若下载失败再尝试 buffalo_l（174MB）。
     */
    private static final String[][] MODELS = {
            {"face_recognition.onnx",
                    "https://hf-mirror.com/immich-app/buffalo_s/resolve/main/recognition/model.onnx"},
            {"face_recognition_l.onnx",
                    "https://hf-mirror.com/immich-app/buffalo_l/resolve/main/recognition/model.onnx"},
    };
    private static final int SIZE = 112;
    /** 越高越严格：0.55 对家人重识别比较稳，低于 0.45 容易认错 */
    private static final float MATCH_THRESHOLD = 0.55f;
    private static final int MAX_SAMPLES = 5;
    /** ArcFace 5 点对齐里两只眼睛的目标坐标（112x112） */
    private static final float EYE_LEFT_X = 38.2946f;
    private static final float EYE_LEFT_Y = 51.6963f;
    private static final float EYE_RIGHT_X = 73.5318f;
    private static final float EYE_RIGHT_Y = 51.5014f;

    private final Context ctx;
    private final FaceDetector detector;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private volatile boolean modelReady;
    private volatile boolean downloading;
    /** 当前加载的是哪个模型：大小模型的特征不通用，名册要分开存 */
    private volatile String modelTag = "s";
    private OrtEnvironment env;
    private OrtSession session;
    private String inputName;
    private String outputName;

    /** 当前画面里最大那张脸的识别结果 */
    private volatile String lastName = "";
    private volatile float[] lastEmbedding;
    private volatile int faceCount;
    /** 内存缓存的名册，避免每 800ms 识别一次都重新解析 SharedPreferences JSON */
    private Map<String, List<float[]>> galleryCache;
    private String galleryCacheTag = "";

    FaceMemory(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        detector = FaceDetection.getClient(new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                // 需要眼睛关键点做对齐，对齐后人脸识别准确率高很多
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setMinFaceSize(0.12f)
                .build());
        io.execute(this::loadModel);
    }

    boolean isReady() {
        return modelReady;
    }

    String currentName() {
        return lastName;
    }

    int faceCount() {
        return faceCount;
    }

    float[] lastEmbedding() {
        return lastEmbedding;
    }

    // ------------------------------------------------------------ 模型

    private void loadModel() {
        for (String[] model : MODELS) {
            File file = new File(ctx.getFilesDir(), model[0]);
            if (!file.exists() || file.length() < 5 * 1024 * 1024) {
                Log.i(TAG, "face model downloading: " + model[0]);
                if (!download(file, model[1])) {
                    Log.w(TAG, "face model download failed: " + model[0]);
                    continue;                       // 大的下不动就退小的
                }
            }
            try {
                env = OrtEnvironment.getEnvironment();
                session = env.createSession(file.getAbsolutePath(), new OrtSession.SessionOptions());
                Iterator<String> in = session.getInputNames().iterator();
                Iterator<String> out = session.getOutputNames().iterator();
                inputName = in.hasNext() ? in.next() : "input";
                outputName = out.hasNext() ? out.next() : "output";
                modelReady = true;
                modelTag = model[0].contains("_l.") ? "l" : "s";
                Log.i(TAG, "face model ready " + model[0] + " in=" + inputName + " out=" + outputName);
                return;
            } catch (Exception e) {
                Log.e(TAG, "face model load failed: " + model[0], e);
            }
        }
        Log.w(TAG, "no face model available");
    }

    private boolean download(File target, String url) {
        if (downloading) return false;
        downloading = true;
        long t0 = System.currentTimeMillis();
        File tmp = new File(target.getAbsolutePath() + ".part");
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                Log.w(TAG, "face model HTTP " + code + " " + url);
                return false;
            }
            long total = conn.getContentLength();
            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                long got = 0;
                long nextLog = 20L * 1024 * 1024;
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    got += n;
                    if (got >= nextLog) {
                        Log.i(TAG, "face model " + got / 1024 / 1024 + "MB / "
                                + (total > 0 ? total / 1024 / 1024 + "MB" : "?"));
                        nextLog += 20L * 1024 * 1024;
                    }
                }
                Log.i(TAG, "face model downloaded " + got / 1024 + "KB / " + total / 1024 + "KB in "
                        + (System.currentTimeMillis() - t0) + "ms");
            }
            return tmp.renameTo(target);
        } catch (Exception e) {
            Log.w(TAG, "face model download error: " + e.getMessage());
            return false;
        } finally {
            downloading = false;
            if (conn != null) conn.disconnect();
            if (tmp.exists() && !target.exists()) tmp.delete();
        }
    }

    // ------------------------------------------------------------ 检测 + 识别

    /** 由 VisionTracker 在后台线程调用；done 用来串行释放 Bitmap */
    void analyze(Bitmap upright, Runnable done) {
        if (!modelReady) {
            done.run();
            return;
        }
        InputImage image = InputImage.fromBitmap(upright, 0);
        detector.process(image)
                .addOnSuccessListener(faces -> {
                    faceCount = faces.size();
                    if (faces.isEmpty()) {
                        lastName = "";
                        lastEmbedding = null;
                        done.run();
                        return;
                    }
                    Face biggest = null;
                    for (Face f : faces) {
                        if (biggest == null
                                || area(f.getBoundingBox()) > area(biggest.getBoundingBox())) {
                            biggest = f;
                        }
                    }
                    final Face chosen = biggest;
                    io.execute(() -> {
                        try {
                            float[] v = embed(upright, chosen);
                            if (v != null) {
                                lastEmbedding = v;
                                lastName = match(v);
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "face embed failed: " + e.getMessage());
                        } finally {
                            done.run();
                        }
                    });
                })
                .addOnFailureListener(e -> {
                    faceCount = 0;
                    done.run();
                });
    }

    private static int area(Rect r) {
        return Math.max(0, r.width()) * Math.max(0, r.height());
    }

    private float[] embed(Bitmap src, Face face) throws Exception {
        if (session == null) return null;
        Bitmap scaled = align(src, face);

        int[] px = new int[SIZE * SIZE];
        scaled.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE);
        scaled.recycle();

        float[] data = new float[3 * SIZE * SIZE];
        int plane = SIZE * SIZE;
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            data[i] = (((p >> 16) & 0xff) - 127.5f) / 128f;          // R
            data[plane + i] = (((p >> 8) & 0xff) - 127.5f) / 128f;   // G
            data[2 * plane + i] = ((p & 0xff) - 127.5f) / 128f;      // B
        }

        try (OnnxTensor tensor = OnnxTensor.createTensor(env,
                FloatBuffer.wrap(data), new long[]{1, 3, SIZE, SIZE});
             OrtSession.Result result = session.run(Collections.singletonMap(inputName, tensor))) {
            Object raw = result.get(0);
            float[][] out;
            if (raw instanceof float[][]) {
                out = (float[][]) raw;
            } else if (raw instanceof OnnxTensor) {
                out = (float[][]) ((OnnxTensor) raw).getValue();
            } else {
                return null;
            }
            return normalize(out[0]);
        }
    }

    /** 用两只眼睛做相似变换，对齐成 ArcFace 的 112x112；没关键点就按人脸框裁剪兜底 */
    private Bitmap align(Bitmap src, Face face) {
        FaceLandmark a = null;
        FaceLandmark b = null;
        for (FaceLandmark lm : face.getAllLandmarks()) {
            if (lm.getLandmarkType() == FaceLandmark.LEFT_EYE) a = lm;
            else if (lm.getLandmarkType() == FaceLandmark.RIGHT_EYE) b = lm;
        }
        if (a != null && b != null) {
            // 按图像里的左右排序，避免「人的左眼」和「画面左侧」搞反
            FaceLandmark l = a.getPosition().x <= b.getPosition().x ? a : b;
            FaceLandmark r = l == a ? b : a;
            float[] s = {l.getPosition().x, l.getPosition().y,
                    r.getPosition().x, r.getPosition().y};
            float[] d = {EYE_LEFT_X, EYE_LEFT_Y, EYE_RIGHT_X, EYE_RIGHT_Y};
            android.graphics.Matrix m = new android.graphics.Matrix();
            if (m.setPolyToPoly(s, 0, d, 0, 2)) {
                Bitmap out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
                android.graphics.Canvas c = new android.graphics.Canvas(out);
                android.graphics.Paint paint = new android.graphics.Paint();
                paint.setFilterBitmap(true);
                c.drawBitmap(src, m, paint);
                return out;
            }
        }
        Rect box = face.getBoundingBox();
        int side = (int) (Math.max(box.width(), box.height()) * 1.5f);
        side = Math.max(32, Math.min(side, Math.min(src.getWidth(), src.getHeight())));
        int left = Math.max(0, Math.min(src.getWidth() - side, box.centerX() - side / 2));
        int top = Math.max(0, Math.min(src.getHeight() - side, box.centerY() - side / 2));
        Bitmap crop = Bitmap.createBitmap(src, left, top, side, side);
        Bitmap scaled = Bitmap.createScaledBitmap(crop, SIZE, SIZE, true);
        if (crop != scaled) crop.recycle();
        return scaled;
    }

    private static float[] normalize(float[] v) {
        double sum = 0;
        for (float f : v) sum += f * f;
        double norm = Math.sqrt(sum);
        if (norm < 1e-6) return v;
        for (int i = 0; i < v.length; i++) v[i] /= (float) norm;
        return v;
    }

    // ------------------------------------------------------------ 家人名册

    /** 返回相识度最高且过阈值的名字，认不出返回空串 */
    private String match(float[] v) {
        String bestName = "";
        float best = MATCH_THRESHOLD;
        for (Map.Entry<String, List<float[]>> e : gallery().entrySet()) {
            for (float[] sample : e.getValue()) {
                float sim = cosine(v, sample);
                if (sim > best) {
                    best = sim;
                    bestName = e.getKey();
                }
            }
        }
        return bestName;
    }

    /** 把当前这张脸记到某个名字下 */
    boolean remember(String name, float[] v) {
        if (name == null || name.trim().isEmpty() || v == null) return false;
        Map<String, List<float[]>> g = gallery();
        List<float[]> list = g.get(name);
        if (list == null) list = new ArrayList<>();
        list.add(v);
        while (list.size() > MAX_SAMPLES) list.remove(0);
        g.put(name, list);
        save(g);
        lastName = name.trim();
        Log.i(TAG, "face remember " + name + " samples=" + list.size());
        return true;
    }

    String knownNames() {
        StringBuilder sb = new StringBuilder();
        for (String n : gallery().keySet()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(n);
        }
        return sb.toString();
    }

    private synchronized Map<String, List<float[]>> gallery() {
        if (galleryCache != null && modelTag.equals(galleryCacheTag)) {
            return galleryCache;
        }
        Map<String, List<float[]>> map = new java.util.LinkedHashMap<>();
        try {
            String raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .getString("people_" + modelTag, "{}");
            JSONObject obj = new JSONObject(raw);
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String name = keys.next();
                JSONArray arr = obj.getJSONArray(name);
                List<float[]> list = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONArray vec = arr.getJSONArray(i);
                    float[] f = new float[vec.length()];
                    for (int j = 0; j < vec.length(); j++) f[j] = (float) vec.getDouble(j);
                    list.add(f);
                }
                map.put(name, list);
            }
        } catch (Exception ignored) {
            /* 坏数据就当空名册 */
        }
        galleryCache = map;
        galleryCacheTag = modelTag;
        return map;
    }

    private synchronized void save(Map<String, List<float[]>> g) {
        galleryCache = g;
        galleryCacheTag = modelTag;
        try {
            JSONObject obj = new JSONObject();
            for (Map.Entry<String, List<float[]>> e : g.entrySet()) {
                JSONArray arr = new JSONArray();
                for (float[] v : e.getValue()) {
                    JSONArray vec = new JSONArray();
                    for (float f : v) vec.put((double) Math.round(f * 10000) / 10000.0);
                    arr.put(vec);
                }
                obj.put(e.getKey(), arr);
            }
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .edit().putString("people_" + modelTag, obj.toString()).apply();
        } catch (Exception e) {
            Log.w(TAG, "face gallery save failed: " + e.getMessage());
        }
    }

    private static float cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return -1;
        double dot = 0;
        for (int i = 0; i < a.length; i++) dot += a[i] * b[i];
        return (float) dot;
    }

    void close() {
        modelReady = false;
        try {
            detector.close();
        } catch (Exception ignored) {
        }
        if (session != null) {
            try {
                session.close();
            } catch (Exception ignored) {
            }
            session = null;
        }
        if (env != null) {
            try {
                env.close();
            } catch (Exception ignored) {
            }
            env = null;
        }
        io.shutdownNow();
    }
}
