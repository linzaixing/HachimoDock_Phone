package com.example.hachimophone;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

/**
 * 声源方向估计（DOA）：用双通道麦克风做互相关，估计声音在左右方向上的入射角。
 *
 * 硬件依据（SM-F731B，/vendor/etc/microphone_characteristics.xml）：
 *   4 颗 mic，实际可用的一组是上下两颗（底部 y≈0.006m，顶部 y≈0.146m），间距约 0.14m。
 *   所以 DOA 是沿机身长轴的：横屏拿着时就是左右；竖屏时它测的是上下。
 *
 * 做法很朴素：16k 采样、每 20ms 读一帧立体声，攒 1024 点后对左右通道做
 * 归一化互相关，取峰值偏移（样点）→ 时差 → 入射角：
 *   sinθ = lag * c / (fs * d)
 * 只在能量足够、相关峰明显时更新，并用指数平滑抑制抖动。
 */
final class DoaTracker {

    private static final String TAG = "HachimoPhone";
    private static final int RATE = 16000;
    /** 20ms */
    private static final int FRAME_SAMPLES = 320;
    /** 互相关窗口 64ms@16k / 43ms@48k */
    private static final int WINDOW = 2048;
    /** 上下 mic 间距（米），来自 microphone_characteristics.xml */
    private static final float MIC_SPACING = 0.14f;
    /** 理论上限 ±6~7 样点，留点余量 */
    private static final int MAX_LAG = 12;
    /** 峰值相关度低于这个值就不采信（环境噪声/两通道被处理成一样时相关性反而很高，另判） */
    private static final float MIN_CORR = 0.35f;
    /** 单通道 RMS 门槛，太安静不更新 */
    private static final float MIN_RMS = 220f;

    private final Context ctx;
    private AudioRecord record;
    private Thread thread;
    private volatile boolean running;
    private volatile float azimuthDeg;
    private volatile float lastCorr;
    private volatile long lastUpdateAt;
    private volatile int source = -1;
    private volatile int channels;
    /** 调试：只试指定音源/采样率，并在很安静时也打印互相关结果 */
    private boolean probe;
    private int probeSrc = -1;
    private int probeRate;

    DoaTracker(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    /** 调试用：--ez doa_test true --ei doa_src 1 --ei doa_rate 48000 */
    void setProbe(int src, int rate) {
        probe = true;
        probeSrc = src;
        probeRate = rate;
    }

    /** 最近一次估计的入射角（度）：负=机身长轴一端，正=另一端；无有效值时为 0 */
    float azimuth() {
        return azimuthDeg;
    }

    boolean valid() {
        return running && System.currentTimeMillis() - lastUpdateAt < 1500;
    }

    int source() {
        return source;
    }

    int channels() {
        return channels;
    }

    boolean start() {
        if (running) return true;
        int rate = probe && probeRate > 0 ? probeRate : RATE;
        if (probe && probeSrc > 0) {
            AudioRecord rec = open(probeSrc, rate, AudioFormat.CHANNEL_IN_STEREO);
            if (rec == null) {
                Log.w(TAG, "doa unavailable: probe src=" + probeSrc + " rate=" + rate);
                return false;
            }
            return launch(rec, probeSrc, rate);
        }
        int[] sources = {
                MediaRecorder.AudioSource.UNPROCESSED,
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.CAMCORDER,
        };
        for (int src : sources) {
            for (int r : new int[]{RATE, 48000}) {
                AudioRecord rec = open(src, r, AudioFormat.CHANNEL_IN_STEREO);
                if (rec != null) return launch(rec, src, r);
            }
        }
        Log.w(TAG, "doa unavailable: 双通道采集不可用");
        return false;
    }

    private boolean launch(AudioRecord rec, int src, int rate) {
        record = rec;
        source = src;
        channels = 2;
        running = true;
        thread = new Thread(() -> loop(rec, rate), "doa-tracker");
        thread.start();
        Log.i(TAG, "doa start src=" + src + " ch=2 rate=" + rate);
        return true;
    }

    private AudioRecord open(int src, int rate, int channelMask) {
        if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return null;
        }
        int min = AudioRecord.getMinBufferSize(rate, channelMask,
                AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) return null;
        try {
            AudioRecord rec = new AudioRecord(src, rate, channelMask,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, FRAME_SAMPLES * 2 * 2 * 10));
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                rec.release();
                return null;
            }
            if (rec.getChannelCount() < 2) {
                rec.release();
                Log.i(TAG, "doa: src=" + src + " 只拿到单声道");
                return null;
            }
            rec.startRecording();
            return rec;
        } catch (SecurityException se) {
            Log.w(TAG, "doa open permission denied: " + se.getMessage());
            return null;
        } catch (Exception e) {
            Log.w(TAG, "doa open src=" + src + " failed: " + e.getMessage());
            return null;
        }
    }

    void stop() {
        running = false;
        AudioRecord rec = record;
        record = null;
        if (rec != null) {
            try {
                rec.stop();
            } catch (Exception ignored) {
            }
            try {
                rec.release();
            } catch (Exception ignored) {
            }
        }
        Thread t = thread;
        thread = null;
        if (t != null) {
            t.interrupt();
            try {
                t.join(150);
            } catch (InterruptedException ignored) {
            }
        }
        azimuthDeg = 0;
        lastUpdateAt = 0;
        Log.i(TAG, "doa stopped");
    }

    private void loop(AudioRecord rec, int rate) {
        short[] buf = new short[FRAME_SAMPLES * 2];
        float[] left = new float[WINDOW];
        float[] right = new float[WINDOW];
        int maxLag = probe ? 48 : Math.max(8, Math.min(48, Math.round(rate * 0.16f / 343f)));
        float minRms = probe ? 30f : MIN_RMS;
        int filled = 0;
        long lastLog = 0;
        while (running) {
            int n;
            try {
                n = rec.read(buf, 0, buf.length);
            } catch (Exception e) {
                break;
            }
            if (n <= 0) {
                if (n < 0) {
                    Log.w(TAG, "doa read error " + n);
                    break;
                }
                if (System.currentTimeMillis() - lastLog > 2000) {
                    lastLog = System.currentTimeMillis();
                    Log.i(TAG, "doa read 0 (没有数据，可能被其它录音占用)");
                }
                continue;
            }
            int frames = n / 2;
            for (int i = 0; i < frames && filled < WINDOW; i++) {
                left[filled] = buf[i * 2];
                right[filled] = buf[i * 2 + 1];
                filled++;
            }
            if (filled < WINDOW) continue;
            filled = 0;

            // 预加重：抬高高频，拍手/辅音这类宽带瞬态的相关峰更尖，也弱化低频轰鸣
            for (int i = WINDOW - 1; i > 0; i--) {
                left[i] -= 0.95f * left[i - 1];
                right[i] -= 0.95f * right[i - 1];
            }

            float rmsL = rms(left);
            float rmsR = rms(right);
            float lrDiff = diffRms(left, right);
            long now = System.currentTimeMillis();
            boolean logNow = now - lastLog > 1000;
            if (rmsL < minRms || rmsR < minRms) {
                // 没声音也要留一行，便于区分「麦克风被别的采集占用，读到全 0」和「确实安静」
                if (logNow) {
                    lastLog = now;
                    Log.i(TAG, "doa src=" + source + " ch=" + channels
                            + " rmsL=" + Math.round(rmsL) + " rmsR=" + Math.round(rmsR)
                            + " diff=" + Math.round(lrDiff) + " (安静)");
                }
                continue;
            }

            int bestLag = 0;
            float best = -1f;
            float second = -1f;
            for (int lag = -maxLag; lag <= maxLag; lag++) {
                float c = normalizedCorr(left, right, lag);
                if (c > best) {
                    second = best;
                    best = c;
                    bestLag = lag;
                } else if (c > second) {
                    second = c;
                }
            }

            // 时差 → 入射角；两通道完全一样时（HAL 已下混/处理）时差恒为 0，只能给出 0°
            double sin = bestLag * 343.0 / (rate * (double) MIC_SPACING);
            if (sin > 1) sin = 1;
            if (sin < -1) sin = -1;
            float az = (float) Math.toDegrees(Math.asin(sin));

            if (best >= MIN_CORR) {
                // 平滑：越小越稳，越大越跟手
                azimuthDeg = azimuthDeg * 0.75f + az * 0.25f;
                lastCorr = best;
                lastUpdateAt = System.currentTimeMillis();
            }

            if (logNow) {
                lastLog = now;
                Log.i(TAG, "doa src=" + source + " ch=" + channels
                        + " rmsL=" + Math.round(rmsL) + " rmsR=" + Math.round(rmsR)
                        + " diff=" + Math.round(lrDiff)
                        + " lag=" + bestLag + " corr=" + String.format("%.2f", best)
                        + " corr2=" + String.format("%.2f", second)
                        + " az=" + Math.round(azimuthDeg)
                        + (valid() ? "" : " (无有效声源)"));
            }
        }
        if (record == rec) {
            record = null;
        }
        running = false;
    }

    private static float rms(float[] x) {
        double s = 0;
        for (float v : x) s += v * v;
        return (float) Math.sqrt(s / x.length);
    }

    /** 左右通道差值的 RMS：接近 0 说明两通道是同一路信号（HAL 下混），做不了 DOA */
    private static float diffRms(float[] a, float[] b) {
        double s = 0;
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            double d = a[i] - b[i];
            s += d * d;
        }
        return (float) Math.sqrt(s / n);
    }

    /** 归一化互相关：lag>0 表示右通道滞后（声音先到左 mic） */
    private static float normalizedCorr(float[] a, float[] b, int lag) {
        int start = Math.max(0, -lag);
        int end = Math.min(a.length, b.length - lag);
        double sa = 0, sb = 0, sab = 0;
        for (int i = start; i < end; i++) {
            sa += a[i] * a[i];
            sb += b[i + lag] * b[i + lag];
            sab += a[i] * b[i + lag];
        }
        if (sa <= 0 || sb <= 0) return 0;
        return (float) (sab / Math.sqrt(sa * sb));
    }
}
