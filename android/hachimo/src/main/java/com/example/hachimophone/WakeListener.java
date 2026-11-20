package com.example.hachimophone;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;

/**
 * 常驻监听：一直开着麦克风做本地 VAD，只在「确实有人在说话」时切出一段 PCM 交给上层。
 * <p>
 * 目的有两个：
 * <ul>
 *   <li>滤掉环境杂音：噪底自适应 + 入音/收音双向判决 + 最短时长门槛，空调声、键盘声不会被当成一句话；</li>
 *   <li>省电省流量：空闲时不上传、不识别，只有 VAD 判出人声才发一段。</li>
 * </ul>
 * 一次创建、长期复用同一个 AudioRecord，避免反复开关麦克风把 HAL 折腾坏。
 */
final class WakeListener {

    interface Callback {
        /** 一段人声（16k / 16bit / 单声道），回调在主线程 */
        void onSegment(byte[] pcm, int durationMs);

        /** 麦克风不可用（被别的 App 占用等），上层可以稍后重试 */
        void onError(String message);
    }

    private static final String TAG = "HachimoPhone";
    private static final int RATE = 16000;
    /** 20ms 一帧 */
    private static final int FRAME = 320;
    /** 入音前保留 200ms，别把第一个字削掉 */
    private static final int PREROLL = 10;
    /** 连续 3 帧（60ms，允许单帧偶发跌落）超过阈值才开始，兼顾防咔嗒声与轻声唤醒 */
    private static final int START_FRAMES = 3;
    /** 连续 800ms 安静就认为说完了：唤醒词常被拆成两段，门限太短会截断 */
    private static final int END_FRAMES = 40;
    /** 太短的片段直接丢（咳嗽、碰桌子） */
    private static final int MIN_MS = 300;
    /** 单段最长 8 秒：唤醒词很短，环境吵时别切出 15 秒噪声去烧识别费 */
    private static final int MAX_MS = 8000;
    /** 绝对人声门槛下限；实际门限 = max(这个值, 噪底×2.5)。外屏麦克风偏轻，门槛要低 */
    private static final double VOICE_MIN = 260;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private AudioRecord record;
    private Thread thread;
    private volatile Callback callback;
    private volatile boolean running;
    /** 播放回复时暂停判决（麦克风仍然开着，只是不再切片段） */
    private volatile boolean muted;
    private volatile int gen;

    boolean isRunning() {
        return running;
    }

    boolean isMuted() {
        return muted;
    }

    @android.annotation.SuppressLint("MissingPermission")
    boolean start(Callback cb) {
        if (running) {
            callback = cb;
            return true;
        }
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) return false;
        AudioRecord rec = null;
        int usedSrc = -1;
        // 原始 MIC 电平通常比 VOICE_RECOGNITION（带降噪/AGC）高，端侧 KWS 更吃电平，优先用 MIC
        int[] sources = {MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.VOICE_RECOGNITION};
        for (int src : sources) {
            AudioRecord cand = null;
            try {
                cand = new AudioRecord(src, RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                        Math.max(min, FRAME * 2 * 20));
                if (cand.getState() != AudioRecord.STATE_INITIALIZED) {
                    cand.release();
                    continue;
                }
                cand.startRecording();
                rec = cand;
                usedSrc = src;
                break;
            } catch (Exception e) {
                Log.w(TAG, "wake listener src=" + src + " failed: " + e.getMessage());
                release(cand);
            }
        }
        if (rec == null) return false;
        Log.i(TAG, "wake source=" + (usedSrc == MediaRecorder.AudioSource.MIC ? "MIC" : "VOICE_RECOGNITION"));
        callback = cb;
        running = true;
        muted = false;
        final AudioRecord finalRec = rec;
        final int myGen = ++gen;
        record = rec;
        thread = new Thread(() -> loop(finalRec, myGen), "wake-listener");
        thread.start();
        Log.i(TAG, "wake listener started");
        return true;
    }

    /** 播放回复期间调用：不切片段，也不让它把 TTS 当成用户说话 */
    void mute() {
        muted = true;
    }

    void unmute() {
        muted = false;
    }

    void stop() {
        running = false;
        gen++;
        callback = null;
        release(record);
        record = null;
        Thread t = thread;
        thread = null;
        if (t != null) {
            t.interrupt();
            if (Thread.currentThread() != t) {
                try {
                    t.join(120);
                } catch (InterruptedException ignored) {
                }
            }
        }
    }

    private void loop(AudioRecord rec, int myGen) {
        short[] buf = new short[FRAME];
        ByteArrayOutputStream seg = new ByteArrayOutputStream(RATE * 2 * 4);
        ArrayDeque<byte[]> pre = new ArrayDeque<>();
        boolean inSpeech = false;
        int loudFrames = 0;
        int quietFrames = 0;
        double noise = 100;
        int badReads = 0;

        while (running && gen == myGen) {
            int n;
            try {
                n = rec.read(buf, 0, FRAME);
            } catch (Exception e) {
                break;
            }
            if (n < 0) {
                Log.w(TAG, "wake listener read error " + n);
                notifyError("麦克风不可用");
                break;
            }
            if (n == 0) {
                if (++badReads > 50) break;
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    break;
                }
                continue;
            }
            badReads = 0;

            if (muted) {
                // 暂停期间把状态清干净，恢复后不会把刚才的 TTS 残响当成人声
                if (inSpeech || !pre.isEmpty()) {
                    inSpeech = false;
                    loudFrames = 0;
                    quietFrames = 0;
                    seg.reset();
                    pre.clear();
                }
                continue;
            }

            double sum = 0;
            for (int i = 0; i < n; i++) sum += (double) buf[i] * buf[i];
            double rms = Math.sqrt(sum / n);
            double threshold = Math.max(VOICE_MIN, noise * 2.5);
            boolean loud = rms > threshold;
            // 只在真正安静且未进入起音判定时学噪底，避免轻声说话把门槛越推越高
            if (!inSpeech && loudFrames == 0 && !loud) {
                double rate = rms < noise ? 0.15 : 0.02;
                noise = noise * (1.0 - rate) + Math.min(rms, threshold * 0.8) * rate;
            }

            byte[] frame = new byte[n * 2];
            for (int i = 0; i < n; i++) {
                frame[i * 2] = (byte) (buf[i] & 0xff);
                frame[i * 2 + 1] = (byte) ((buf[i] >> 8) & 0xff);
            }

            if (!inSpeech) {
                pre.addLast(frame);
                if (pre.size() > PREROLL) pre.removeFirst();
                if (loud) {
                    if (++loudFrames >= START_FRAMES) {
                        inSpeech = true;
                        quietFrames = 0;
                        seg.reset();
                        for (byte[] f : pre) seg.write(f, 0, f.length);
                        pre.clear();
                    }
                } else if (loudFrames > 0) {
                    // 汉字声母/韵母过渡可能掉 1 帧，退 1 格而非清零，避免「哈-基-米」起音被切碎
                    loudFrames--;
                }
                continue;
            }

            seg.write(frame, 0, frame.length);
            if (loud) {
                quietFrames = 0;
            } else {
                quietFrames++;
            }
            int ms = seg.size() / (RATE / 1000 * 2);
            if (quietFrames >= END_FRAMES || ms >= MAX_MS) {
                final byte[] pcm = seg.toByteArray();
                final int duration = ms;
                inSpeech = false;
                loudFrames = 0;
                quietFrames = 0;
                seg.reset();
                // 和 v0.1.0 一致：不再做二次声学/峰值门禁，够长的人声一律交给识别，
                // 由识别文本判断是不是「哈基米」，避免喊了却被本地过滤掉
                if (duration >= MIN_MS) {
                    ui.post(() -> {
                        Callback c = callback;
                        if (c != null && running && !muted) c.onSegment(pcm, duration);
                    });
                } else {
                    Log.i(TAG, "wake listener drop short segment " + duration + "ms");
                }
            }
        }

        release(rec);
        if (gen != myGen) return;
        if (record == rec) record = null;
        running = false;
        Log.i(TAG, "wake listener stopped");
    }

    private void notifyError(String message) {
        ui.post(() -> {
            Callback c = callback;
            if (c != null) c.onError(message);
        });
    }

    private static void release(AudioRecord rec) {
        if (rec == null) return;
        try {
            rec.stop();
        } catch (Exception ignored) {
        }
        try {
            rec.release();
        } catch (Exception ignored) {
        }
    }
}
