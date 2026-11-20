package com.example.hachimophone;

import android.content.Context;
import android.util.Log;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.KeywordSpotter;
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig;
import com.k2fsa.sherpa.onnx.KeywordSpotterResult;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 端侧关键词检测（sherpa-onnx KWS）：只认「哈基米」。
 * 不联网、不用提前录音，模型首次启动时从 assets 复制到应用目录。
 */
public final class KwsWake {
    private static final String TAG = "HachimoKws";
    private static final String DIR = "kws";
    private static final String ENCODER = "encoder-epoch-12-avg-2-chunk-16-left-64.onnx";
    private static final String DECODER = "decoder-epoch-12-avg-2-chunk-16-left-64.onnx";
    private static final String JOINER = "joiner-epoch-12-avg-2-chunk-16-left-64.onnx";
    private static final String TOKENS = "tokens.txt";
    private static final String KEYWORDS = "keywords.txt";

    /** 拼音声母+韵母；参数走 config，避免行内 :score #threshold 语法解析问题 */
    private static final String KEYWORD_LINE =
            "h ā j ī m ǐ @哈基米\n"
                    + "h ā j ī m í @哈基米\n"
                    + "h ā j í m ǐ @哈基米\n"
                    + "h ā j í m í @哈基米\n"
                    + "h ā q í m ǐ @哈基米\n"
                    + "h ǎ j ī m ǐ @哈基米\n"
                    + "h ā j ī m ī @哈基米\n"
                    + "ā j ī m ǐ @哈基米\n"
                    + "h a j i m i @哈基米\n";

    private static volatile KeywordSpotter spotter;
    private static volatile boolean loading;

    private KwsWake() {
    }

    public static boolean ready() {
        return spotter != null;
    }

    /** 后台加载模型，加载完成前唤醒仍走云端识别，不影响使用 */
    public static void preload(Context context) {
        if (spotter != null || loading) return;
        synchronized (KwsWake.class) {
            if (spotter != null || loading) return;
            loading = true;
        }
        final Context app = context.getApplicationContext();
        Thread t = new Thread(() -> {
            try {
                File dir = prepare(app);
                OnlineModelConfig model = OnlineModelConfig.builder()
                        .setTransducer(OnlineTransducerModelConfig.builder()
                                .setEncoder(new File(dir, ENCODER).getAbsolutePath())
                                .setDecoder(new File(dir, DECODER).getAbsolutePath())
                                .setJoiner(new File(dir, JOINER).getAbsolutePath())
                                .build())
                        .setTokens(new File(dir, TOKENS).getAbsolutePath())
                        .setNumThreads(1)
                        .setDebug(false)
                        .build();
                KeywordSpotterConfig config = KeywordSpotterConfig.builder()
                        .setFeatureConfig(FeatureConfig.builder()
                                .setSampleRate(16000)
                                .setFeatureDim(80)
                                .build())
                        .setOnlineModelConfig(model)
                        .setKeywordsFile(new File(dir, KEYWORDS).getAbsolutePath())
                        .setKeywordsScore(3.0f)
                        .setKeywordsThreshold(0.05f)
                        .setMaxActivePaths(10)
                        .setNumTrailingBlanks(3)
                        .build();
                spotter = new KeywordSpotter(config);
                Log.i(TAG, "ready: " + KEYWORD_LINE.trim());
            } catch (Throwable e) {
                Log.w(TAG, "load failed: " + e);
            } finally {
                loading = false;
            }
        }, "kws-init");
        t.start();
    }

    /**
     * 对一段 16kHz/16bit/单声道 PCM 做关键词检测。
     *
     * @return 命中的关键词（通常是「哈基米」），没命中返回 null
     */
    public static String detect(byte[] pcm) {
        if (pcm == null || pcm.length < 3200) return null;
        int n = pcm.length / 2;
        float[] base = new float[n];
        float peak = 0;
        float sum = 0;
        for (int i = 0; i < n; i++) {
            float v = (short) ((pcm[2 * i] & 0xff) | (pcm[2 * i + 1] << 8)) / 32768f;
            base[i] = v;
            sum += v;
            float a = Math.abs(v);
            if (a > peak) peak = a;
        }
        // 去直流：外屏麦克风有 DC 偏置时，fbank 能量会被低频分量带偏
        float mean = n > 0 ? sum / n : 0f;
        if (Math.abs(mean) > 0.001f) {
            for (int i = 0; i < n; i++) base[i] -= mean;
        }
        String hit = tryAll(base, n, peak);
        // 长片段整段解码容易被无关内容带偏：再挑能量最高的 1.5 秒窗口单独检一次
        if (hit == null && n > 16000 * 3 / 2) {
            int win = Math.min(n, 16000 * 3 / 2);
            int best = bestWindow(base, n, win);
            float[] sub = new float[win];
            System.arraycopy(base, best, sub, 0, win);
            float subPeak = 0;
            for (float v : sub) {
                float a = Math.abs(v);
                if (a > subPeak) subPeak = a;
            }
            hit = tryAll(sub, win, subPeak);
            if (hit != null) {
                Log.i(TAG, "detect hit in window @" + (best / 16) + "ms");
            }
        }
        int ms = pcm.length / 32;
        int pct = Math.round(peak * 100);
        if (hit != null) {
            Log.i(TAG, "detect hit: " + hit + " peak=" + pct + "% ms=" + ms);
        } else {
            Log.i(TAG, "detect miss peak=" + pct + "% ms=" + ms);
        }
        return hit;
    }

    private static String tryAll(float[] data, int n, float peak) {
        String hit = run(pad(data, n));
        if (hit == null && peak > 0.003f) {
            float[] targets = {0.5f, 0.9f};
            for (float target : targets) {
                float gain = Math.min(40f, target / peak);
                if (gain <= 1.2f) continue;
                float[] boosted = new float[n];
                for (int i = 0; i < n; i++) boosted[i] = data[i] * gain;
                hit = run(pad(boosted, n));
                if (hit != null) {
                    Log.i(TAG, "detect hit after gain x" + Math.round(gain * 10) / 10f);
                    break;
                }
            }
        }
        return hit;
    }

    /** 末尾补 40ms 静音：太长会把刚命中的结果冲出有效窗口 */
    private static float[] pad(float[] data, int n) {
        float[] out = new float[n + 640];
        System.arraycopy(data, 0, out, 0, n);
        return out;
    }

    /** 用 100ms 块能量找最像人声的窗口 */
    private static int bestWindow(float[] data, int n, int win) {
        int block = 1600;
        int blocks = n / block;
        float[] energy = new float[blocks];
        for (int b = 0; b < blocks; b++) {
            float e = 0;
            for (int i = b * block; i < (b + 1) * block; i++) e += data[i] * data[i];
            energy[b] = e;
        }
        int wb = Math.max(1, win / block);
        float sum = 0;
        for (int i = 0; i < wb && i < blocks; i++) sum += energy[i];
        float bestSum = sum;
        int bestStart = 0;
        for (int i = wb; i < blocks; i++) {
            sum += energy[i] - energy[i - wb];
            if (sum > bestSum) {
                bestSum = sum;
                bestStart = i - wb + 1;
            }
        }
        return Math.min(bestStart * block, Math.max(0, n - win));
    }

    private static String run(float[] samples) {
        synchronized (KwsWake.class) {
            KeywordSpotter s = spotter;
            if (s == null) return null;
            OnlineStream stream = s.createStream();
            try {
                stream.acceptWaveform(samples, 16000);
                stream.inputFinished();
                // 官方用法：每解完一个 chunk 就取一次结果。
                // getResult 只在「刚命中」的那一帧有效，只在循环结束后取会永远为空。
                String hit = null;
                while (s.isReady(stream)) {
                    s.decode(stream);
                    KeywordSpotterResult r = s.getResult(stream);
                    if (r != null && r.getKeyword() != null && !r.getKeyword().isEmpty()) {
                        hit = r.getKeyword();
                        break;
                    }
                }
                return hit;
            } catch (Throwable e) {
                Log.w(TAG, "detect failed: " + e);
                return null;
            } finally {
                stream.release();
            }
        }
    }

    /** 模型资源版本：换模型时 +1，旧缓存会被强制覆盖 */
    private static final int ASSET_VER = 12;

    private static File prepare(Context context) throws Exception {
        File dir = new File(context.getFilesDir(), DIR);
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("mkdirs failed: " + dir);
        android.content.SharedPreferences p = BridgeService.prefs(context);
        boolean force = p.getInt("kws_asset_ver", 0) != ASSET_VER;
        copy(context, ENCODER, dir, force);
        copy(context, DECODER, dir, force);
        copy(context, JOINER, dir, force);
        copy(context, TOKENS, dir, force);
        try (OutputStream out = new FileOutputStream(new File(dir, KEYWORDS))) {
            out.write(KEYWORD_LINE.getBytes("UTF-8"));
        }
        if (force) p.edit().putInt("kws_asset_ver", ASSET_VER).apply();
        return dir;
    }

    private static void copy(Context context, String name, File dir, boolean force) throws Exception {
        File file = new File(dir, name);
        if (!force && file.exists() && file.length() > 0) return;
        try (InputStream in = context.getAssets().open(DIR + "/" + name);
             OutputStream out = new FileOutputStream(file)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        Log.i(TAG, "copied " + name + " " + file.length() + "B");
    }
}
