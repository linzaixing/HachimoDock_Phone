package com.example.hachimophone;

import android.content.Context;
import android.util.Log;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 手机端离线 TTS：sherpa-onnx + 中文 VITS 模型（csukuangfj/sherpa-onnx-vits-zh-ll，5 个音色）。
 * 首次使用从 hf-mirror 下载约 115MB 到 files/tts_zh_ll，之后完全离线、不花钱。
 */
final class LocalTts {

    private static final String TAG = "HachimoPhone";
    private static final String BASE =
            "https://hf-mirror.com/csukuangfj/sherpa-onnx-vits-zh-ll/resolve/main/";
    private static final long MODEL_MIN = 100L * 1024 * 1024;
    private static final String[] FILES = {
            "model.onnx", "tokens.txt", "lexicon.txt", "date.fst", "number.fst",
            "dict/hmm_model.utf8", "dict/idf.utf8", "dict/jieba.dict.utf8",
            "dict/stop_words.utf8", "dict/user.dict.utf8",
            "dict/pos_dict/char_state_tab.utf8", "dict/pos_dict/prob_emit.utf8",
            "dict/pos_dict/prob_start.utf8", "dict/pos_dict/prob_trans.utf8",
    };

    interface Callback {
        void onFile(File file);

        void onError(String message);
    }

    interface Progress {
        void onProgress(String message);
    }

    private static OfflineTts engine;
    private static Thread downloadThread;

    private LocalTts() {
    }

    static int speakerId(Context c) {
        return BridgeService.prefs(c).getInt("local_sid", 0);
    }

    static void setSpeakerId(Context c, int sid) {
        int clamped = sid < 0 ? 0 : (sid > 4 ? 4 : sid);
        BridgeService.prefs(c).edit().putInt("local_sid", clamped).apply();
    }

    static File dir(Context c) {
        return new File(c.getFilesDir(), "tts_zh_ll");
    }

    static boolean ready(Context c) {
        File d = dir(c);
        File marker = new File(d, ".ready");
        File m = new File(d, "model.onnx");
        if (marker.exists() && m.length() > MODEL_MIN) return true;
        // 兼容旧版未写 .ready 标记的完整安装：校验全部必需文件都存在
        if (m.length() <= MODEL_MIN) return false;
        for (String name : FILES) {
            if (new File(d, name).length() <= 0) return false;
        }
        try {
            marker.createNewFile();
        } catch (Exception ignored) {
        }
        return true;
    }

    /** 后台预下载模型；失败静默，不影响其它功能 */
    static synchronized void preload(Context c, Progress p) {
        if (ready(c) || downloadThread != null) return;
        Context app = c.getApplicationContext();
        downloadThread = new Thread(() -> {
            try {
                ensureModel(app, p);
                Log.i(TAG, "local tts model downloaded");
            } catch (Exception e) {
                Log.w(TAG, "tts model download failed: " + e.getMessage());
            } finally {
                synchronized (LocalTts.class) {
                    downloadThread = null;
                }
            }
        }, "tts-download");
        downloadThread.start();
    }

    static void speak(Context c, String text, Callback cb, Progress p) {
        if (!ready(c)) {
            preload(c, p);
            cb.onError("本机音色首次使用，正在后台下载模型");
            return;
        }
        Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                OfflineTts tts = engine(app);
                int sid = BridgeService.prefs(app).getInt("local_sid", 0);
                if (sid < 0 || sid >= tts.getNumSpeakers()) sid = 0;
                GeneratedAudio audio = tts.generate(text, sid, 1.0f);
                File out = new File(app.getCacheDir(), "tts_local.wav");
                if (!audio.save(out.getAbsolutePath())) {
                    throw new Exception("写 wav 失败");
                }
                boostWav(out, 5.0);
                cb.onFile(out);
            } catch (Throwable e) {
                Log.w(TAG, "local tts failed", e);
                cb.onError("本机音色合成失败：" + e.getMessage());
            }
        }, "local-tts").start();
    }

    /** 本机童声默认音量偏小：对 WAV 数据段做软限幅增益（tanh，避免硬削波）；用流式 IO 兼容 API 24+ */
    private static void boostWav(File file, double gain) {
        try {
            byte[] b;
            try (java.io.FileInputStream in = new java.io.FileInputStream(file);
                 java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream((int) file.length())) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                b = out.toByteArray();
            }
            int off = 12;
            int dataOff = -1;
            int dataLen = 0;
            while (off + 8 <= b.length) {
                String id = new String(b, off, 4, "US-ASCII");
                int sz = (b[off + 4] & 0xff) | ((b[off + 5] & 0xff) << 8)
                        | ((b[off + 6] & 0xff) << 16) | ((b[off + 7] & 0xff) << 24);
                if (sz < 0) break;
                if ("data".equals(id)) {
                    dataOff = off + 8;
                    dataLen = Math.min(sz, b.length - dataOff);
                    break;
                }
                off += 8 + sz + (sz & 1);
            }
            if (dataOff < 0 || dataLen <= 0) return;
            for (int i = 0; i + 1 < dataLen; i += 2) {
                short s = (short) ((b[dataOff + i] & 0xff) | (b[dataOff + i + 1] << 8));
                double y = Math.tanh(s * gain / 32768.0) * 32767.0;
                short o = (short) Math.max(-32768, Math.min(32767, Math.round(y)));
                b[dataOff + i] = (byte) (o & 0xff);
                b[dataOff + i + 1] = (byte) ((o >> 8) & 0xff);
            }
            try (FileOutputStream fo = new FileOutputStream(file)) {
                fo.write(b);
            }
            Log.i(TAG, "local tts boost x" + gain);
        } catch (Exception e) {
            Log.w(TAG, "boost failed", e);
        }
    }

    private static synchronized OfflineTts engine(Context c) {
        if (engine != null) return engine;
        File d = dir(c);
        OfflineTtsVitsModelConfig vits = OfflineTtsVitsModelConfig.builder()
                .setModel(new File(d, "model.onnx").getAbsolutePath())
                .setLexicon(new File(d, "lexicon.txt").getAbsolutePath())
                .setTokens(new File(d, "tokens.txt").getAbsolutePath())
                .setDictDir(new File(d, "dict").getAbsolutePath())
                .build();
        OfflineTtsModelConfig model = OfflineTtsModelConfig.builder()
                .setVits(vits)
                .setNumThreads(2)
                .setDebug(false)
                .build();
        OfflineTtsConfig config = OfflineTtsConfig.builder()
                .setModel(model)
                .setRuleFsts(new File(d, "date.fst").getAbsolutePath() + ","
                        + new File(d, "number.fst").getAbsolutePath())
                .setMaxNumSentences(1)
                .build();
        engine = new OfflineTts(config);
        Log.i(TAG, "local tts ready speakers=" + engine.getNumSpeakers()
                + " rate=" + engine.getSampleRate());
        return engine;
    }

    private static void ensureModel(Context c, Progress p) throws Exception {
        File d = dir(c);
        for (String name : FILES) {
            File f = new File(d, name);
            boolean ok = f.length() > 0;
            if (name.equals("model.onnx")) ok = f.length() > MODEL_MIN;
            if (ok) continue;
            File parent = f.getParentFile();
            if (parent != null) parent.mkdirs();
            download(BASE + name, f, p, name);
        }
        File marker = new File(d, ".ready");
        if (!marker.exists()) marker.createNewFile();
    }

    private static void download(String urlStr, File out, Progress p, String name) throws Exception {
        File part = new File(out.getAbsolutePath() + ".part");
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(60000);
        conn.setInstanceFollowRedirects(true);
        try {
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new Exception(name + " HTTP " + code);
            }
            long total = 0;
            try {
                total = Long.parseLong(String.valueOf(conn.getHeaderField("Content-Length")));
            } catch (Exception ignored) {
            }
            long done = 0;
            long last = -1;
            byte[] buf = new byte[64 * 1024];
            try (InputStream in = conn.getInputStream();
                 FileOutputStream fo = new FileOutputStream(part)) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    fo.write(buf, 0, n);
                    done += n;
                    if (p != null && done - last > 5 * 1024 * 1024) {
                        last = done;
                        p.onProgress("下载本机音色 " + (done / 1024 / 1024) + "MB"
                                + (total > 0 ? "/" + (total / 1024 / 1024) + "MB" : ""));
                    }
                }
            }
            if (!part.renameTo(out)) {
                throw new Exception("保存 " + name + " 失败");
            }
        } finally {
            conn.disconnect();
            if (part.exists()) part.delete();
        }
    }
}
