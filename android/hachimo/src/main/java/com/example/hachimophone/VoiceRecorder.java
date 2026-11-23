package com.example.hachimophone;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;

/**
 * 直接录 16k 单声道 PCM，交给 PC 做识别。
 * <p>
 * 为什么需要它：手机自带的语音识别（Google）在国内连不上服务器，离线语言包又常常没下载，
 * 所以本机识别失败后改用「手机只录音、PC 识别」这条路，不需要任何 API Key。
 */
final class VoiceRecorder {

    interface Listener {
        /**
         * 说完停顿 {@link #PROBE_MS} 时先给一份快照（主线程），可以提前送去识别。
         * 之后如果没再说话，{@link #onDone} 的 probeUsable 为 true，识别结果可以直接复用，
         * 省掉「等停顿结束再开始识别」那段时间。
         */
        void onProbe(byte[] pcm);

        /** VAD 首次判定用户开口：给界面一个即时反馈，避免长时间停在「我在听…」 */
        default void onSpeechStart() {
        }

        /**
         * 录完的 PCM（16 bit 小端单声道，16000Hz），回调在主线程。
         *
         * @param probeUsable 快照之后没有再说话：快照的识别结果就是最终结果
         */
        void onDone(byte[] pcm, boolean probeUsable);

        void onError(String message);
    }

    private static final String TAG = "HachimoPhone";
    private static final int RATE = 16000;
    /** 最长录 45 秒：长句子别被 20 秒硬截断 */
    private static final int MAX_MS = 45000;
    /** 一直没人说话就早点结束，别占着麦克风 */
    private static final int LEAD_SILENCE_MS = 4000;
    /** 说完停顿 0.65 秒就自动结束 */
    private static final int SILENCE_MS = 650;
    /** 停顿到 0.3 秒就先送去识别（提前占用识别耗时，结束时可复用结果） */
    private static final int PROBE_MS = 300;
    /** 检测到人声前保留 400ms 前导缓冲（4 个 100ms 块），裁掉开头几秒纯静音，提高短句识别率 */
    private static final int PRE_CHUNKS = 4;
    /** 连续 3 块（300ms）超过门槛才算真的开口：单块尖峰（碰桌子、键盘）不算 */
    private static final int START_CHUNKS = 3;
    /** 人声门槛下限；实际门限 = max(这个值, 噪底×2.5)，外屏麦克风偏轻也能听到 */
    private static final double VOICE_FLOOR = 260;

    private final Handler ui = new Handler(Looper.getMainLooper());
    /** 说完多长的静音算一句话结束：发给 Agent 的指令放宽一点，避免话说一半就被截断 */
    private volatile int silenceMs = SILENCE_MS;
    /** 一开始多久没人说话就放弃 */
    private volatile int leadSilenceMs = LEAD_SILENCE_MS;
    /** 本次最长录多久：环境太吵时 VAD 可能一直判不出「说完了」，必须有硬上限 */
    private volatile int maxMs = MAX_MS;
    private AudioRecord record;
    private Thread thread;
    private volatile Listener listener;
    private volatile boolean running;
    /** 本次录音里有没有检测到人声（对话空闲判定用） */
    private volatile boolean voiceSeen;
    /**
     * 录音代次：每次 start / cancel 都会 +1。
     * 老线程退出时如果发现代次已经变了，就绝对不能再改 running / record，
     * 否则会把刚开始的新录音一起关掉（对话从第三轮起就再也听不到声音）。
     */
    private volatile int gen;
    /** 连续几次录音几乎全零：某些机型 VOICE_RECOGNITION 会录出全零，锁定改用 MIC */
    private int deadRuns;
    private volatile boolean forceMic;

    boolean isActive() {
        return running;
    }

    /** 当前这段录音里有没有听到人声 */
    boolean heardVoice() {
        return voiceSeen;
    }

    /** 调整本次的断句参数（默认为类里的常量） */
    void configure(int silence, int leadSilence) {
        configure(silence, leadSilence, MAX_MS);
    }

    /** 同上，另外限定本次最长录音时长（毫秒） */
    void configure(int silence, int leadSilence, int maxDurationMs) {
        silenceMs = silence > 200 ? silence : SILENCE_MS;
        leadSilenceMs = leadSilence > 500 ? leadSilence : LEAD_SILENCE_MS;
        maxMs = maxDurationMs > 3000 ? maxDurationMs : MAX_MS;
    }

    @android.annotation.SuppressLint("MissingPermission")
    boolean start(Listener l) {
        cancel();
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) return false;
        int source = (forceMic || deadRuns >= 2)
                ? MediaRecorder.AudioSource.MIC
                : MediaRecorder.AudioSource.VOICE_RECOGNITION;
        AudioRecord rec = null;
        try {
            rec = new AudioRecord(source, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(min, RATE / 5 * 2));
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                rec.release();
                return false;
            }
            rec.startRecording();
        } catch (Exception e) {
            Log.e(TAG, "record start failed", e);
            // 构造成功但 startRecording 抛异常时必须自己释放，否则麦克风一直被占着：
            // 之后每次录音都只能读到全零，听起来就是「问它没反应」
            release(rec);
            return false;
        }
        Log.i(TAG, "record start source=" + (source == MediaRecorder.AudioSource.MIC ? "MIC" : "RECOG"));
        record = rec;
        listener = l;
        voiceSeen = false;
        final int myGen = ++gen;
        running = true;
        final AudioRecord finalRec = rec;
        thread = new Thread(() -> loop(finalRec, myGen), "voice-recorder");
        thread.start();
        return true;
    }

    /** 手动结束：停止采集，录到的内容照常回调 */
    void stop() {
        running = false;
    }

    /** 放弃本次录音，不回调 */
    void cancel() {
        running = false;
        gen++;
        listener = null;
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
        ByteArrayOutputStream out = new ByteArrayOutputStream(RATE * 2 * 20);
        java.util.ArrayDeque<byte[]> pre = new java.util.ArrayDeque<>();
        short[] buf = new short[RATE / 10];      // 100ms 一块
        long startedAt = System.currentTimeMillis();
        long silentSince = 0;
        boolean sawVoice = false;
        boolean probeSent = false;
        boolean probeUsable = false;
        double maxRms = 0;
        /** 开口后的峰值：说完的静音门槛按它的 0.4 倍自适应，避免环境声一直顶在固定门槛上 */
        double speechPeak = 0;
        /** 诊断用：第一个/最后一个超过门槛的块、最低电平，用来判断「说完多久才收尾」 */
        long firstLoudAt = 0;
        long lastLoudAt = 0;
        double minRms = Double.MAX_VALUE;
        boolean loggedAmbient = false;
        int badReads = 0;
        int loudRun = 0;
        int chunks = 0;
        double noise = 110;
        while (running && gen == myGen) {
            int n = rec.read(buf, 0, buf.length);
            long now = System.currentTimeMillis();
            if (n < 0) {
                // ERROR_INVALID_OPERATION / ERROR_DEAD_OBJECT：麦克风已经不可用，别在这儿空转
                Log.w(TAG, "record read error " + n);
                break;
            }
            if (n == 0) {
                if (++badReads > 30) {
                    Log.w(TAG, "record no data, give up");
                    break;
                }
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    break;
                }
                continue;
            }
            badReads = 0;
            byte[] chunk = new byte[n * 2];
            double sum = 0;
            for (int i = 0; i < n; i++) {
                short s = buf[i];
                chunk[i * 2] = (byte) (s & 0xff);
                chunk[i * 2 + 1] = (byte) ((s >> 8) & 0xff);
                sum += (double) s * s;
            }
            double rms = Math.sqrt(sum / n);
            if (rms > maxRms) maxRms = rms;
            if (rms < minRms) minRms = rms;
            // 噪底自适应：麦克风偏轻的机器上固定高门槛会「听不见」，且只在入声前慢速适应环境底噪
            double threshold = Math.max(VOICE_FLOOR, noise * 2.5);
            boolean loud = rms > threshold;
            if (!loggedAmbient && now - startedAt > 600) {
                loggedAmbient = true;
                Log.i(TAG, "rec ambient rms=" + Math.round(rms) + " min=" + Math.round(minRms)
                        + " noise=" + Math.round(noise) + " thr=" + Math.round(threshold));
            }
            // 每 2 秒打一行电平：排查「说了半天才回」时能看出是环境底噪顶着门槛还是识别慢
            if (++chunks % 20 == 0) {
                Log.i(TAG, "rec " + (now - startedAt) + "ms rms=" + Math.round(rms)
                        + " noise=" + Math.round(noise) + " thr=" + Math.round(threshold)
                        + " qthr=" + Math.round(Math.max(threshold, speechPeak * 0.4))
                        + " voice=" + sawVoice);
            }
            if (!sawVoice && !loud) {
                double rate = rms < noise ? 0.15 : 0.02;
                noise = noise * (1.0 - rate) + Math.min(rms, threshold * 0.8) * rate;
            }
            if (!sawVoice) {
                // 入声要连续 3 块（300ms）才算数：碰桌子、键盘、咳嗽这类单块尖峰
                // 以前会被当成「他开口了」，之后一直等 1.4 秒静音，听起来就是「说了半天才回」
                pre.addLast(chunk);
                if (pre.size() > PRE_CHUNKS + START_CHUNKS) pre.removeFirst();
                loudRun = loud ? loudRun + 1 : 0;
                if (loudRun >= START_CHUNKS) {
                    sawVoice = true;
                    voiceSeen = true;
                    firstLoudAt = now;
                    lastLoudAt = now;
                    speechPeak = Math.max(maxRms, rms);
                    for (byte[] p : pre) out.write(p, 0, p.length);
                    pre.clear();
                    silentSince = 0;
                    Listener sl = listener;
                    if (sl != null) ui.post(sl::onSpeechStart);
                }
            } else if (rms > Math.max(threshold, speechPeak * 0.4)) {
                lastLoudAt = now;
                speechPeak = Math.max(speechPeak, rms);
                out.write(chunk, 0, chunk.length);
                // 快照之后又说话了：那份快照不能代表最终内容，作废
                if (probeSent) probeUsable = false;
                silentSince = 0;
            } else {
                out.write(chunk, 0, chunk.length);
                if (silentSince == 0) {
                    silentSince = now;
                } else {
                    long quiet = now - silentSince;
                    if (!probeSent && quiet >= PROBE_MS) {
                        probeSent = true;
                        probeUsable = true;
                        byte[] snap = out.toByteArray();
                        Listener pl = listener;
                        if (pl != null) ui.post(() -> pl.onProbe(snap));
                    }
                    if (quiet > silenceMs) break;
                }
            }
            if (now - startedAt > maxMs) break;
            if (!sawVoice && now - startedAt > leadSilenceMs) break;
        }

        int len = out.size();
        byte[] pcm = sawVoice ? out.toByteArray() : new byte[0];
        final boolean reusable = probeSent && probeUsable;
        release(rec);
        // 已经被新一轮录音取代：共享状态归新线程管，这里什么都别动
        if (gen != myGen) return;
        if (record == rec) record = null;
        running = false;
        if (sawVoice) {
            deadRuns = 0;
        } else {
            deadRuns++;
            // 仅当音源真读出全零/极低底噪时才锁定切换为 MIC，不再反复切回问题音源
            if (deadRuns >= 2 && maxRms < 30) forceMic = true;
        }

        final Listener l = listener;
        listener = null;
        if (l == null) return;                   // 被 cancel 了
        ui.post(() -> {
            if (pcm.length >= 3200) l.onDone(pcm, reusable);
            else l.onError("没听清，再说一次");
        });
        Log.i(TAG, "voice recorded " + len + " bytes rms=" + Math.round(maxRms)
                + " silence=" + silenceMs + (sawVoice ? "" : "（没人声）")
                + " noise=" + Math.round(noise)
                + " total=" + (System.currentTimeMillis() - startedAt)
                + " speech=" + (firstLoudAt == 0 ? 0 : lastLoudAt - firstLoudAt)
                + " tail=" + (firstLoudAt == 0 ? 0 : System.currentTimeMillis() - lastLoudAt)
                + " minRms=" + Math.round(minRms));
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
