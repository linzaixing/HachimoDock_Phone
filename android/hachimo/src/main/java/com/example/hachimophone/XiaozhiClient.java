package com.example.hachimophone;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.github.jaredmdobson.concentus.OpusApplication;
import io.github.jaredmdobson.concentus.OpusDecoder;
import io.github.jaredmdobson.concentus.OpusEncoder;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * 小智（xiaozhi-esp32）实验性客户端接入：
 *   - 伪造本地管理单播 MAC（02:xx:xx:xx:xx:xx）与 UUID v4，走 Activation-Version: 1 激活流程；
 *   - 仅依赖纯 Java Concentus 做 16kHz 上行 / 24kHz 下行 Opus 帧编解码，零原生 .so 开销；
 *   - 通过 WebSocket 对接兼容小智协议的自建或授权服务端（如 xiaozhi-esp32-server）。
 */
final class XiaozhiClient {

    private static final String TAG = "HachimoXiaozhi";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    static final String DEFAULT_OTA_URL = "";
    private static final String BOARD_TYPE = "bread-compact-wifi";
    private static final String APP_VERSION = "2.5.4";
    private static final int IN_RATE = 16000;
    private static final int FRAME_MS = 60;
    private static final int FRAME_SAMPLES = IN_RATE * FRAME_MS / 1000; // 960
    private static final int FRAME_BYTES = FRAME_SAMPLES * 2;           // 1920

    interface OtaCallback {
        void onNeedActivation(String code);

        void onReady(String wsUrl, String token);

        void onError(String message);
    }

    interface TurnListener {
        void onNeedActivation(String code);

        void onStt(String text);

        void onReplyText(String text);

        void onAudioStart(int sampleRate);

        void onAudioPcm(byte[] pcm);

        void onDone();

        void onError(String message);
    }

    private static final Handler ui = new Handler(Looper.getMainLooper());
    private static final SecureRandom RND = new SecureRandom();
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build();

    private static final AtomicReference<WebSocket> activeWs = new AtomicReference<>();
    private static final AtomicBoolean pollingActivate = new AtomicBoolean(false);
    private static volatile Thread pollThread;

    private XiaozhiClient() {
    }

    // ------------------------------------------------------------ 配置与伪造设备身份

    static boolean enabled(Context c) {
        return PhoneVoice.planMode(c) == PhoneVoice.PLAN_XIAOZHI;
    }

    static String otaUrl(Context c) {
        String v = BridgeService.prefs(c).getString("xz_ota_url", "");
        if (v == null || v.trim().isEmpty()) return DEFAULT_OTA_URL;
        v = v.trim();
        return v.endsWith("/") ? v : (v + "/");
    }

    static void setOtaUrl(Context c, String v) {
        String clean = v == null ? "" : v.trim();
        if (!clean.isEmpty() && !clean.endsWith("/")) clean += "/";
        BridgeService.prefs(c).edit().putString("xz_ota_url", clean).apply();
    }

    static synchronized String deviceId(Context c) {
        String mac = BridgeService.prefs(c).getString("xz_device_id", "");
        if (!mac.isEmpty()) return mac;
        mac = randomMac();
        BridgeService.prefs(c).edit().putString("xz_device_id", mac).apply();
        return mac;
    }

    static synchronized String clientId(Context c) {
        String id = BridgeService.prefs(c).getString("xz_client_id", "");
        if (!id.isEmpty()) return id;
        id = UUID.randomUUID().toString();
        BridgeService.prefs(c).edit().putString("xz_client_id", id).apply();
        return id;
    }

    static synchronized void resetIdentity(Context c) {
        stopActivatePolling();
        String mac = randomMac();
        String uuid = UUID.randomUUID().toString();
        BridgeService.prefs(c).edit()
                .putString("xz_device_id", mac)
                .putString("xz_client_id", uuid)
                .putBoolean("xz_activated", false)
                .putString("xz_code", "")
                .putString("xz_ws_url", "")
                .putString("xz_ws_token", "")
                .apply();
    }

    static boolean activated(Context c) {
        return BridgeService.prefs(c).getBoolean("xz_activated", false);
    }

    static String activationCode(Context c) {
        return BridgeService.prefs(c).getString("xz_code", "");
    }

    static String wsUrl(Context c) {
        return BridgeService.prefs(c).getString("xz_ws_url", "");
    }

    static String wsToken(Context c) {
        return BridgeService.prefs(c).getString("xz_ws_token", "");
    }

    private static String randomMac() {
        byte[] b = new byte[6];
        RND.nextBytes(b);
        b[0] = (byte) ((b[0] & 0xFC) | 0x02); // 本地管理单播 MAC
        return String.format(Locale.US, "%02x:%02x:%02x:%02x:%02x:%02x",
                b[0] & 0xff, b[1] & 0xff, b[2] & 0xff,
                b[3] & 0xff, b[4] & 0xff, b[5] & 0xff);
    }

    // ------------------------------------------------------------ OTA 与 Activation-Version: 1

    static void stopActivatePolling() {
        pollingActivate.set(false);
        Thread t = pollThread;
        pollThread = null;
        if (t != null) t.interrupt();
    }

    static void checkOtaAndActivate(Context c, OtaCallback cb) {
        final Context app = c.getApplicationContext();
        stopActivatePolling();
        new Thread(() -> {
            try {
                OtaResult res = queryOtaSync(app);
                if (res.needActivation) {
                    ui.post(() -> {
                        if (cb != null) cb.onNeedActivation(res.code);
                    });
                    startActivatePollLoop(app, cb);
                } else {
                    ui.post(() -> {
                        if (cb != null) cb.onReady(res.wsUrl, res.wsToken);
                    });
                }
            } catch (Exception e) {
                Log.w(TAG, "ota failed", e);
                ui.post(() -> {
                    if (cb != null) cb.onError("OTA 请求失败：" + e.getMessage());
                });
            }
        }, "xz-ota").start();
    }

    private static final class OtaResult {
        String wsUrl;
        String wsToken;
        boolean needActivation;
        String code;
    }

    private static OtaResult queryOtaSync(Context app) throws Exception {
        String url = otaUrl(app);
        if (url.isEmpty()) {
            throw new Exception("请先填写小智 OTA 地址");
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new Exception("OTA 地址需以 http:// 或 https:// 开头");
        }
        String mac = deviceId(app);
        String uuid = clientId(app);
        JSONObject appObj = new JSONObject()
                .put("name", "xiaozhi")
                .put("version", APP_VERSION);
        JSONObject boardObj = new JSONObject()
                .put("type", BOARD_TYPE)
                .put("name", BOARD_TYPE)
                .put("mac", mac);
        JSONObject payload = new JSONObject()
                .put("version", 2)
                .put("language", "zh-CN")
                .put("mac_address", mac)
                .put("uuid", uuid)
                .put("application", appObj)
                .put("board", boardObj);

        Request req = new Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .header("Device-Id", mac)
                .header("Client-Id", uuid)
                .header("Activation-Version", "1")
                .header("User-Agent", BOARD_TYPE + "/" + APP_VERSION)
                .header("Accept-Language", "zh-CN")
                .post(RequestBody.create(JSON, payload.toString()))
                .build();

        try (Response resp = HTTP.newCall(req).execute()) {
            String body = resp.body() == null ? "" : resp.body().string();
            if (!resp.isSuccessful()) {
                throw new Exception("HTTP " + resp.code() + " " + body);
            }
            JSONObject out = new JSONObject(body);
            JSONObject ws = out.optJSONObject("websocket");
            String wsUrl = ws == null ? "" : ws.optString("url", "");
            String wsToken = ws == null ? "test-token" : ws.optString("token", "test-token");
            if (wsToken.isEmpty()) wsToken = "test-token";

            JSONObject act = out.optJSONObject("activation");
            OtaResult res = new OtaResult();
            res.wsUrl = wsUrl;
            res.wsToken = wsToken;
            if (act != null && !act.optString("code", "").isEmpty()) {
                res.needActivation = true;
                res.code = act.optString("code", "");
                BridgeService.prefs(app).edit()
                        .putBoolean("xz_activated", false)
                        .putString("xz_code", res.code)
                        .putString("xz_ws_url", wsUrl)
                        .putString("xz_ws_token", wsToken)
                        .apply();
            } else {
                res.needActivation = false;
                res.code = "";
                BridgeService.prefs(app).edit()
                        .putBoolean("xz_activated", true)
                        .putString("xz_code", "")
                        .putString("xz_ws_url", wsUrl)
                        .putString("xz_ws_token", wsToken)
                        .apply();
            }
            Log.i(TAG, "ota ok mac=" + mac + " activated=" + !res.needActivation
                    + " code=" + res.code + " ws=" + wsUrl);
            return res;
        }
    }

    private static void startActivatePollLoop(Context app, OtaCallback cb) {
        if (!pollingActivate.compareAndSet(false, true)) return;
        Thread t = new Thread(() -> {
            String base = otaUrl(app);
            String activateUrl = base + "activate";
            String mac = deviceId(app);
            String uuid = clientId(app);
            for (int i = 0; i < 75 && pollingActivate.get(); i++) {
                try {
                    Thread.sleep(4000);
                } catch (InterruptedException e) {
                    break;
                }
                if (!pollingActivate.get()) break;
                try {
                    Request req = new Request.Builder()
                            .url(activateUrl)
                            .header("Content-Type", "application/json")
                            .header("Activation-Version", "1")
                            .header("Device-Id", mac)
                            .header("Client-Id", uuid)
                            .post(RequestBody.create(JSON, "{}"))
                            .build();
                    try (Response resp = HTTP.newCall(req).execute()) {
                        int code = resp.code();
                        if (code == 200) {
                            OtaResult refreshed = queryOtaSync(app);
                            pollingActivate.set(false);
                            ui.post(() -> {
                                if (cb != null) cb.onReady(refreshed.wsUrl, refreshed.wsToken);
                            });
                            return;
                        }
                        if (code != 202) {
                            Log.w(TAG, "activate poll HTTP " + code);
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "activate poll err", e);
                }
            }
            pollingActivate.set(false);
        }, "xz-activate-poll");
        pollThread = t;
        t.start();
    }

    // ------------------------------------------------------------ WebSocket 对话回合

    static void abort() {
        WebSocket ws = activeWs.getAndSet(null);
        if (ws != null) {
            try {
                ws.cancel();
            } catch (Exception ignored) {
            }
        }
    }

    static void sendAudioTurn(Context c, byte[] pcm16k, TurnListener l) {
        if (pcm16k == null || pcm16k.length < 320) {
            ui.post(() -> l.onError("没听清"));
            return;
        }
        runTurn(c, pcm16k, null, l);
    }

    static void sendTextTurn(Context c, String text, TurnListener l) {
        if (text == null || text.trim().isEmpty()) {
            ui.post(() -> l.onError("输入为空"));
            return;
        }
        runTurn(c, null, text.trim(), l);
    }

    private static void runTurn(Context c, byte[] pcm16k, String textInput, TurnListener l) {
        final Context app = c.getApplicationContext();
        abort();
        new Thread(() -> {
            try {
                String url = wsUrl(app);
                String tok = wsToken(app);
                if (url.isEmpty() || !activated(app)) {
                    OtaResult ota = queryOtaSync(app);
                    if (ota.needActivation) {
                        ui.post(() -> l.onNeedActivation(ota.code));
                        startActivatePollLoop(app, null);
                        return;
                    }
                    url = ota.wsUrl;
                    tok = ota.wsToken;
                }
                if (url.isEmpty()) {
                    ui.post(() -> l.onError("OTA 未返回 WebSocket 地址"));
                    return;
                }
                openTurnSocket(app, url, tok, pcm16k, textInput, l);
            } catch (Exception e) {
                Log.w(TAG, "xiaozhi turn init failed", e);
                ui.post(() -> l.onError("小智连接失败：" + e.getMessage()));
            }
        }, "xz-turn").start();
    }

    private static void openTurnSocket(Context app, String url, String token,
                                       byte[] pcm16k, String textInput, TurnListener l) {
        final String mac = deviceId(app);
        final String uuid = clientId(app);
        final AtomicBoolean finished = new AtomicBoolean(false);
        final AtomicBoolean audioStarted = new AtomicBoolean(false);
        final AtomicBoolean helloDone = new AtomicBoolean(false);
        final AtomicReference<String> sessionIdRef = new AtomicReference<>("");
        final AtomicReference<OpusDecoder> decoderRef = new AtomicReference<>();
        final int[] downRate = {24000};

        OkHttpClient wsClient = HTTP.newBuilder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .build();
        Request req = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + token)
                .header("Protocol-Version", "1")
                .header("Device-Id", mac)
                .header("Client-Id", uuid)
                .build();

        final Runnable timeoutTask = () -> {
            if (finished.compareAndSet(false, true)) {
                WebSocket cur = activeWs.getAndSet(null);
                if (cur != null) cur.cancel();
                l.onError("小智响应超时");
            }
        };
        ui.postDelayed(timeoutTask, 25000);

        WebSocket ws = wsClient.newWebSocket(req, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                try {
                    JSONObject audioParams = new JSONObject()
                            .put("format", "opus")
                            .put("sample_rate", IN_RATE)
                            .put("channels", 1)
                            .put("frame_duration", FRAME_MS);
                    JSONObject hello = new JSONObject()
                            .put("type", "hello")
                            .put("version", 1)
                            .put("features", new JSONObject().put("mcp", false))
                            .put("transport", "websocket")
                            .put("audio_params", audioParams);
                    ws.send(hello.toString());
                } catch (Exception e) {
                    if (finished.compareAndSet(false, true)) {
                        ui.removeCallbacks(timeoutTask);
                        activeWs.compareAndSet(ws, null);
                        ws.cancel();
                        ui.post(() -> l.onError("小智握手构造失败"));
                    }
                }
            }

            @Override
            public void onMessage(WebSocket ws, String message) {
                if (finished.get()) return;
                try {
                    JSONObject m = new JSONObject(message);
                    String type = m.optString("type", "");
                    if ("hello".equals(type)) {
                        if (!helloDone.compareAndSet(false, true)) return;
                        String sid = m.optString("session_id", "");
                        sessionIdRef.set(sid);
                        JSONObject ap = m.optJSONObject("audio_params");
                        int sr = ap == null ? 24000 : ap.optInt("sample_rate", 24000);
                        if (sr <= 0) sr = 24000;
                        downRate[0] = sr;
                        decoderRef.set(new OpusDecoder(sr, 1));
                        Log.i(TAG, "xiaozhi hello ok sid=" + sid + " downRate=" + sr);

                        new Thread(() -> {
                            try {
                                if (textInput != null) {
                                    JSONObject detect = new JSONObject()
                                            .put("type", "listen")
                                            .put("state", "detect")
                                            .put("text", textInput)
                                            .put("source", "text");
                                    if (!sid.isEmpty()) detect.put("session_id", sid);
                                    ws.send(detect.toString());
                                } else {
                                    JSONObject start = new JSONObject()
                                            .put("type", "listen")
                                            .put("state", "start")
                                            .put("mode", "manual");
                                    if (!sid.isEmpty()) start.put("session_id", sid);
                                    ws.send(start.toString());

                                    OpusEncoder enc = new OpusEncoder(
                                            IN_RATE, 1, OpusApplication.OPUS_APPLICATION_VOIP);
                                    enc.setBitrate(24000);
                                    byte[] framePcm = new byte[FRAME_BYTES];
                                    byte[] opusOut = new byte[1275];
                                    int off = 0;
                                    while (off < pcm16k.length && !finished.get()) {
                                        int rem = pcm16k.length - off;
                                        int copy = Math.min(FRAME_BYTES, rem);
                                        if (copy < 320) break;
                                        java.util.Arrays.fill(framePcm, (byte) 0);
                                        System.arraycopy(pcm16k, off, framePcm, 0, copy);
                                        int len = enc.encode(framePcm, 0, FRAME_SAMPLES,
                                                opusOut, 0, opusOut.length);
                                        if (len > 0 && !ws.send(ByteString.of(opusOut, 0, len))) {
                                            break;
                                        }
                                        off += copy;
                                    }

                                    if (!finished.get()) {
                                        JSONObject stop = new JSONObject()
                                                .put("type", "listen")
                                                .put("state", "stop");
                                        if (!sid.isEmpty()) stop.put("session_id", sid);
                                        ws.send(stop.toString());
                                    }
                                }
                            } catch (Exception e) {
                                Log.w(TAG, "xiaozhi send turn err", e);
                            }
                        }, "xz-send-opus").start();
                    } else if ("stt".equals(type)) {
                        String stt = m.optString("text", "").trim();
                        if (!stt.isEmpty()) {
                            ui.post(() -> l.onStt(stt));
                        }
                    } else if ("tts".equals(type)) {
                        String state = m.optString("state", "");
                        if ("start".equals(state)) {
                            if (audioStarted.compareAndSet(false, true)) {
                                final int sr = downRate[0];
                                ui.post(() -> l.onAudioStart(sr));
                            }
                        } else if ("sentence_start".equals(state)) {
                            String txt = m.optString("text", "").trim();
                            if (!txt.isEmpty()) {
                                ui.post(() -> l.onReplyText(txt));
                            }
                        } else if ("stop".equals(state)) {
                            if (!finished.compareAndSet(false, true)) return;
                            ui.removeCallbacks(timeoutTask);
                            activeWs.compareAndSet(ws, null);
                            ws.close(1000, "done");
                            ui.post(l::onDone);
                        }
                    } else if ("alert".equals(type)) {
                        String st = m.optString("status", "");
                        String msg = m.optString("message", "");
                        String errText = (st + " " + msg).trim();
                        if (errText.isEmpty()) errText = "小智提示异常";
                        if (errText.contains("激活") || errText.contains("绑定")) {
                            BridgeService.prefs(app).edit().putBoolean("xz_activated", false).apply();
                        }
                        if (!finished.compareAndSet(false, true)) return;
                        ui.removeCallbacks(timeoutTask);
                        activeWs.compareAndSet(ws, null);
                        ws.close(1000, "alert");
                        final String finalErr = errText;
                        ui.post(() -> l.onError(finalErr));
                    }
                } catch (Exception e) {
                    Log.w(TAG, "xiaozhi parse msg err", e);
                }
            }

            @Override
            public void onMessage(WebSocket ws, ByteString bytes) {
                if (finished.get()) return;
                OpusDecoder dec = decoderRef.get();
                if (dec == null) return;
                try {
                    byte[] raw = bytes.toByteArray();
                    int maxSamples = downRate[0] * 120 / 1000;
                    short[] pcmShorts = new short[maxSamples];
                    int samples = dec.decode(raw, 0, raw.length, pcmShorts, 0, maxSamples, false);
                    if (samples <= 0) return;
                    byte[] pcmBytes = new byte[samples * 2];
                    for (int i = 0; i < samples; i++) {
                        short s = pcmShorts[i];
                        pcmBytes[i * 2] = (byte) (s & 0xff);
                        pcmBytes[i * 2 + 1] = (byte) ((s >> 8) & 0xff);
                    }
                    if (audioStarted.compareAndSet(false, true)) {
                        final int sr = downRate[0];
                        ui.post(() -> l.onAudioStart(sr));
                    }
                    ui.post(() -> l.onAudioPcm(pcmBytes));
                } catch (Exception e) {
                    Log.w(TAG, "opus decode err", e);
                }
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                if (!finished.compareAndSet(false, true)) return;
                ui.removeCallbacks(timeoutTask);
                activeWs.compareAndSet(ws, null);
                int code = response == null ? 0 : response.code();
                Log.w(TAG, "xiaozhi ws failed code=" + code + " audioStarted=" + audioStarted.get(), t);
                if (audioStarted.get() && t instanceof java.io.EOFException) {
                    // 部分服务端在 TTS 播报完毕后直接断开 TCP 而不发 tts.stop，已收到音频时按正常结束处理
                    ui.post(l::onDone);
                    return;
                }
                if (code == 401 || code == 403) {
                    BridgeService.prefs(app).edit().putBoolean("xz_activated", false).apply();
                }
                ui.post(() -> l.onError(code > 0 ? "小智连接失败 (HTTP " + code + ")" : "小智连接失败"));
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                if (!finished.compareAndSet(false, true)) return;
                ui.removeCallbacks(timeoutTask);
                activeWs.compareAndSet(ws, null);
                if (audioStarted.get()) {
                    ui.post(l::onDone);
                } else {
                    ui.post(() -> l.onError("小智连接已关闭"));
                }
            }
        });
        activeWs.set(ws);
    }
}
