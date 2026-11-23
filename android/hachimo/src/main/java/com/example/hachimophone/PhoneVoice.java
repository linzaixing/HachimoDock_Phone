package com.example.hachimophone;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 手机独立完成的语音链路（不借助 PC 算力，支持免费云端 API 与手机本地小模型）：
 * <p>
 * 默认走零成本/免费方案：
 *   - 识别（ASR）：硅基流动永久免费 FunAudioLLM/SenseVoiceSmall（/audio/transcriptions），亦兼容千问 ASR 与本地 Whisper；
 *   - 对话（LLM）：智谱 glm-4-flash / 硅基流动免费模型，或手机端本地小模型（127.0.0.1 免 Key）；
 *   - 语音（TTS）：默认 0 元手机内置系统 TTS，可选本地/OpenAI /audio/speech 或阿里云 CosyVoice。
 */
final class PhoneVoice {

    private static final String TAG = "HachimoPhone";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final MediaType WAV = MediaType.parse("audio/wav");

    static final String TTS_MODE_SYSTEM = "system";
    static final String TTS_MODE_OPENAI = "openai";
    static final String TTS_MODE_DASHSCOPE = "dashscope";
    /** 手机端离线 sherpa-onnx 中文 VITS（免费、断网可用） */
    static final String TTS_MODE_LOCAL = "local";

    /** 和 Bridge 侧一致的人设，保证连不连电脑说话口气一样 */
    private static final String PERSONA =
            "你叫哈基米，是一只住在折叠屏外屏里的西高地小狗。说话简短、口语化、有点俏皮。"
                    + "每次只回答一句话，最多 25 个字；不要解释、不要列点、不要客套。"
                    + "不要使用 Markdown，不要提自己是 AI。";
    private static final int CHAT_TURNS = 5;

    interface TextCallback {
        void onText(String text);

        void onError(String message);
    }

    private static final Handler ui = new Handler(Looper.getMainLooper());
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

    /** 最近 5 轮，仅保存在内存里；退出对话时清空 */
    private static final List<String[]> history = new ArrayList<>();

    private PhoneVoice() {
    }

    // ------------------------------------------------------------ 配置

    static String asrKey(Context c) {
        return BridgeService.prefs(c).getString("asr_key", "");
    }

    static void setAsrKey(Context c, String v) {
        BridgeService.prefs(c).edit().putString("asr_key", v == null ? "" : v.trim()).apply();
    }

    static String asrBase(Context c) {
        String v = BridgeService.prefs(c).getString("asr_base", "");
        return v == null || v.trim().isEmpty() ? DASH_BASE : trimSlash(v);
    }

    static void setAsrBase(Context c, String v) {
        BridgeService.prefs(c).edit().putString("asr_base", v == null ? "" : trimSlash(v)).apply();
    }

    static String asrModel(Context c) {
        String v = BridgeService.prefs(c).getString("asr_model", "");
        return v == null || v.trim().isEmpty() ? FREE_MIX_ASR_MODEL : v.trim();
    }

    static void setAsrModel(Context c, String v) {
        BridgeService.prefs(c).edit().putString("asr_model", v == null ? "" : v.trim()).apply();
    }

    static String chatKey(Context c) {
        return BridgeService.prefs(c).getString("chat_key", "");
    }

    static void setChatKey(Context c, String v) {
        BridgeService.prefs(c).edit().putString("chat_key", v == null ? "" : v.trim()).apply();
    }

    static String chatBase(Context c) {
        String v = BridgeService.prefs(c).getString("chat_base", "");
        return v == null || v.trim().isEmpty() ? ZHIPU_BASE : trimSlash(v);
    }

    static void setChatBase(Context c, String v) {
        BridgeService.prefs(c).edit().putString("chat_base", v == null ? "" : trimSlash(v)).apply();
    }

    static String chatModel(Context c) {
        String v = BridgeService.prefs(c).getString("chat_model", "");
        // 默认用智谱永久免费对话模型（glm-4.7-flash，关闭思考链保证语音低延迟）
        return v == null || v.trim().isEmpty() ? FREE_CHAT_MODEL : v.trim();
    }

    static void setChatModel(Context c, String v) {
        BridgeService.prefs(c).edit().putString("chat_model", v == null ? "" : v.trim()).apply();
    }

    /** 手机端直连识别是否就绪（已填 Key，指向本机模型，或已启用小智云） */
    static boolean asrReady(Context c) {
        return XiaozhiClient.enabled(c) || !asrKey(c).isEmpty() || isLocalUrl(asrBase(c));
    }

    /** 手机端直连对话是否就绪（已填 Key，指向本机模型，或已启用小智云） */
    static boolean chatReady(Context c) {
        return XiaozhiClient.enabled(c) || !chatKey(c).isEmpty() || isLocalUrl(chatBase(c));
    }

    /** TTS 模式：local（默认 0 元离线童声）/ system（0 元系统语音）/ dashscope */
    static String ttsMode(Context c) {
        String v = BridgeService.prefs(c).getString("tts_mode", "");
        if (v == null || v.trim().isEmpty()) return TTS_MODE_LOCAL;
        return v.trim();
    }

    static void setTtsMode(Context c, String v) {
        BridgeService.prefs(c).edit().putString("tts_mode", v == null ? TTS_MODE_LOCAL : v.trim()).apply();
    }

    static String ttsBase(Context c) {
        String v = BridgeService.prefs(c).getString("tts_base", "");
        return v == null || v.trim().isEmpty() ? "http://127.0.0.1:8880/v1" : trimSlash(v);
    }

    static void setTtsBase(Context c, String v) {
        BridgeService.prefs(c).edit().putString("tts_base", v == null ? "" : trimSlash(v)).apply();
    }

    static String ttsVoice(Context c) {
        String v = BridgeService.prefs(c).getString("tts_voice", "");
        return v == null || v.trim().isEmpty() ? "longjielidou_v3" : v.trim();
    }

    static String ttsModel(Context c) {
        String v = BridgeService.prefs(c).getString("tts_model", "");
        return v == null || v.trim().isEmpty() ? "cosyvoice-v3-flash" : v.trim();
    }

    static void setTtsModel(Context c, String v) {
        BridgeService.prefs(c).edit().putString("tts_model", v == null ? "" : v.trim()).apply();
    }

    static void setTtsVoice(Context c, String v) {
        BridgeService.prefs(c).edit().putString("tts_voice", v == null ? "" : v.trim()).apply();
    }

    /** 是否启用云端/本地非系统 TTS（默认 system 模式返回 false，直接用 0 元本机系统 TTS） */
    static boolean ttsReady(Context c) {
        String mode = ttsMode(c);
        if (TTS_MODE_DASHSCOPE.equals(mode)) return !dashAuthKey(c).isEmpty();
        if (TTS_MODE_OPENAI.equals(mode)) return isLocalUrl(ttsBase(c)) || !asrKey(c).isEmpty() || !chatKey(c).isEmpty();
        return false;
    }

    // ------------------------------------------------------------ 智谱免费视觉模型

    static String zhipuKey(Context c) {
        String v = BridgeService.prefs(c).getString("zhipu_key", "");
        if (!v.isEmpty()) return v;
        if (chatBase(c).contains("bigmodel.cn")) return chatKey(c);
        return "";
    }

    static void setZhipuKey(Context c, String v) {
        BridgeService.prefs(c).edit().putString("zhipu_key", v == null ? "" : v.trim()).apply();
    }

    static String zhipuBase(Context c) {
        String v = BridgeService.prefs(c).getString("zhipu_base", "");
        return v == null || v.trim().isEmpty()
                ? "https://open.bigmodel.cn/api/paas/v4" : trimSlash(v);
    }

    static String vlmModel(Context c) {
        String v = BridgeService.prefs(c).getString("vlm_model", "");
        // glm-4v-flash 是智谱免费的通用视觉模型（4.6v-flash 当前对该账号返回空）
        return v == null || v.trim().isEmpty() ? "glm-4v-flash" : v.trim();
    }

    /** 是否配好了视觉模型（智谱免费 glm-4v-flash，或阿里云百炼 qwen-vl-plus） */
    static boolean vlmReady(Context c) {
        return !zhipuKey(c).isEmpty() || !dashVlmKey(c).isEmpty();
    }

    private static String dashVlmKey(Context c) {
        if (!planPaid(c)) return "";
        String dk = dashKey(c);
        if (!dk.isEmpty()) return dk;
        if (!asrKey(c).isEmpty() && asrBase(c).contains("dashscope")) return asrKey(c);
        return "";
    }

    /** 阿里云语音通道统一鉴权 Key（优先 dashKey，兼容从识别/对话输入框读取） */
    private static String dashAuthKey(Context c) {
        String dk = dashKey(c);
        if (!dk.isEmpty()) return dk;
        if (asrBase(c).contains("dashscope") && !asrKey(c).isEmpty()) return asrKey(c);
        if (chatBase(c).contains("dashscope") && !chatKey(c).isEmpty()) return chatKey(c);
        return "";
    }

    // ------------------------------------------------------------ 免费/付费方案一键切换

    static final String ZHIPU_BASE = "https://open.bigmodel.cn/api/paas/v4";
    static final String FREE_BASE = "https://api.siliconflow.cn/v1";
    static final String FREE_ASR_MODEL = "XingChenAGI/XingChenASR-V3.2-Ultra";
    static final String FREE_CHAT_MODEL = "glm-4.7-flash";
    static final String FREE_SF_CHAT_MODEL = "Qwen/Qwen3-8B";
    /** 免费高音质语音：阿里云每月1日重置长期免费识别(10h/月) + Sambert语音(3万字/音色/月) */
    static final String FREE_MIX_ASR_MODEL = "paraformer-realtime-v2";
    static final String FREE_MIX_TTS_MODEL = "sambert-zhimiao-emo-v1";
    static final String PAID_BASE = "https://api.deepseek.com/v1";
    static final String PAID_CHAT_MODEL = "deepseek-chat";
    /** 付费方案：一个阿里云 DashScope Key 跑通全部（识别 + DeepSeek-V3 对话 + CosyVoice + 看图） */
    static final String DASH_BASE = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    static final String DASH_ASR_MODEL = "qwen3-asr-flash";
    static final String DASH_CHAT_MODEL = "deepseek-v3";
    static final String DASH_VLM_MODEL = "qwen-vl-plus";
    static final String DASH_TTS_MODEL = "cosyvoice-v3-flash";
    static final String DASH_TTS_VOICE = "longjielidou_v3";

    static String siliconKey(Context c) {
        String v = BridgeService.prefs(c).getString("silicon_key", "");
        if (!v.isEmpty()) return v;
        // 兼容旧配置：识别地址指向硅基时，asr_key 就是硅基 Key
        if (asrBase(c).contains("siliconflow")) return asrKey(c);
        if (chatBase(c).contains("siliconflow")) return chatKey(c);
        return "";
    }

    static void setSiliconKey(Context c, String v) {
        BridgeService.prefs(c).edit().putString("silicon_key", v == null ? "" : v.trim()).apply();
    }

    static String dashKey(Context c) {
        String v = BridgeService.prefs(c).getString("dashscope_key", "");
        if (!v.isEmpty()) return v;
        if (asrBase(c).contains("dashscope")) return asrKey(c);
        return "";
    }

    static void setDashKey(Context c, String v) {
        BridgeService.prefs(c).edit().putString("dashscope_key", v == null ? "" : v.trim()).apply();
    }

    static String deepseekKey(Context c) {
        String v = BridgeService.prefs(c).getString("deepseek_key", "");
        if (!v.isEmpty()) return v;
        if (chatBase(c).contains("deepseek")) return chatKey(c);
        return "";
    }

    static void setDeepseekKey(Context c, String v) {
        BridgeService.prefs(c).edit().putString("deepseek_key", v == null ? "" : v.trim()).apply();
    }

    static final int PLAN_FREE = 0;
    static final int PLAN_PAID = 1;
    static final int PLAN_XIAOZHI = 2;

    static int planMode(Context c) {
        android.content.SharedPreferences sp = BridgeService.prefs(c);
        if (sp.contains("plan_mode")) {
            int m = sp.getInt("plan_mode", PLAN_FREE);
            return (m >= 0 && m <= 2) ? m : PLAN_FREE;
        }
        return sp.getBoolean("plan_paid", false) ? PLAN_PAID : PLAN_FREE;
    }

    static void setPlanMode(Context c, int mode) {
        int m = (mode >= 0 && mode <= 2) ? mode : PLAN_FREE;
        BridgeService.prefs(c).edit()
                .putInt("plan_mode", m)
                .putBoolean("plan_paid", m == PLAN_PAID)
                .apply();
    }

    static boolean planPaid(Context c) {
        return planMode(c) == PLAN_PAID;
    }

    /**
     * 一键切换方案（即使尚未填 Key 也允许切换界面与模型预设，方便用户先切过去再填 Key）：
     *   免费 = 硅基识别 + 硅基 Qwen3-8B 对话 + 本机离线童声
     *   付费 = v0.1.0 tag 那一版：阿里云 qwen3-asr-flash + DeepSeek + CosyVoice
     *
     * @return 空串表示配置齐全，否则返回待补充 Key 的提示
     */
    static String applyPlan(Context c, boolean paid) {
        String hint = "";
        if (paid) {
            // 切付费前先保留用户在免费框里填过的硅基 Key，防止切回免费版时丢失
            String existingSf = siliconKey(c);
            if (!existingSf.isEmpty() && BridgeService.prefs(c).getString("silicon_key", "").isEmpty()) {
                setSiliconKey(c, existingSf);
            }
            String dk = dashKey(c);
            String ds = deepseekKey(c);
            setAsrKey(c, dk);
            setAsrBase(c, DASH_BASE);
            setAsrModel(c, DASH_ASR_MODEL);
            // 若未填独立 DeepSeek Key，直接复用同一个阿里云 DashScope Key 调百炼托管的 deepseek-v3
            if (ds.isEmpty()) {
                setChatKey(c, dk);
                setChatBase(c, DASH_BASE);
                setChatModel(c, DASH_CHAT_MODEL);
            } else {
                setChatKey(c, ds);
                setChatBase(c, PAID_BASE);
                setChatModel(c, PAID_CHAT_MODEL);
            }
            setTtsMode(c, TTS_MODE_DASHSCOPE);
            setTtsModel(c, DASH_TTS_MODEL);
            setTtsVoice(c, DASH_TTS_VOICE);
            // 付费方案切为「智能分级」：KWS 未命中时仅短唤醒句送 ASR 兜底，长闲聊本地拦截省付费额度
            if (BridgeService.wakeMode(c) == BridgeService.WAKE_MODE_ASR) {
                BridgeService.setWakeMode(c, BridgeService.WAKE_MODE_SMART);
            }
            if (dk.isEmpty()) hint = "请填写阿里云 DashScope Key";
        } else {
            // 切免费前保留已填过的付费 Key，避免来回切换丢失
            String existingDk = dashKey(c);
            if (!existingDk.isEmpty() && BridgeService.prefs(c).getString("dashscope_key", "").isEmpty()) {
                setDashKey(c, existingDk);
            }
            String existingDs = deepseekKey(c);
            if (!existingDs.isEmpty() && BridgeService.prefs(c).getString("deepseek_key", "").isEmpty()) {
                setDeepseekKey(c, existingDs);
            }
            String zp = zhipuKey(c);
            String dk = dashKey(c);
            String sf = siliconKey(c);
            // 方案 A：智谱负责想(glm-4.7-flash)与看(glm-4v-flash)，兼容仅填硅基的老配置
            if (!zp.isEmpty() || sf.isEmpty()) {
                setChatKey(c, zp);
                setChatBase(c, ZHIPU_BASE);
                setChatModel(c, FREE_CHAT_MODEL);
            } else {
                setChatKey(c, sf);
                setChatBase(c, FREE_BASE);
                setChatModel(c, FREE_SF_CHAT_MODEL);
            }
            if (!dk.isEmpty() || sf.isEmpty()) {
                // 阿里云每月1日重置长期免费 paraformer-realtime-v2 + sambert-zhimiao-emo-v1
                setAsrKey(c, dk);
                setAsrBase(c, DASH_BASE);
                setAsrModel(c, FREE_MIX_ASR_MODEL);
                if (!dk.isEmpty()) {
                    setTtsMode(c, TTS_MODE_DASHSCOPE);
                    setTtsModel(c, FREE_MIX_TTS_MODEL);
                    setTtsVoice(c, "");
                } else {
                    setTtsMode(c, TTS_MODE_LOCAL);
                }
                if (BridgeService.wakeMode(c) == BridgeService.WAKE_MODE_ASR) {
                    BridgeService.setWakeMode(c, BridgeService.WAKE_MODE_SMART);
                }
            } else {
                setAsrKey(c, sf);
                setAsrBase(c, FREE_BASE);
                setAsrModel(c, FREE_ASR_MODEL);
                setTtsMode(c, TTS_MODE_LOCAL);
                BridgeService.setWakeMode(c, BridgeService.WAKE_MODE_ASR);
            }
            if (zp.isEmpty() && sf.isEmpty()) {
                hint = dk.isEmpty() ? "请填写智谱 Key 与阿里云 Key" : "请填写智谱 Key（对话+看图）";
            } else if (dk.isEmpty() && sf.isEmpty()) {
                hint = "请填写阿里云 Key（每月免费语音）";
            }
        }
        setPlanMode(c, paid ? PLAN_PAID : PLAN_FREE);
        return hint;
    }

    /** 把一帧 JPEG 交给视觉模型：付费方案走阿里云 qwen-vl-plus，免费方案走智谱免费 glm-4v-flash */
    static void describeImage(Context c, byte[] jpeg, String question, TextCallback cb) {
        final Context app = c.getApplicationContext();
        final boolean useDash = planPaid(app) && !dashVlmKey(app).isEmpty();
        final String key = useDash ? dashVlmKey(app) : zhipuKey(app);
        final String url = useDash ? (DASH_BASE + "/chat/completions") : (zhipuBase(app) + "/chat/completions");
        final String model = useDash ? DASH_VLM_MODEL : vlmModel(app);
        if (key.isEmpty()) {
            postError(cb, "还没填智谱或阿里云视觉 Key");
            return;
        }
        if (jpeg == null || jpeg.length == 0) {
            postError(cb, "没有可用的画面");
            return;
        }
        final String b64 = android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP);
        new Thread(() -> {
            try {
                Log.i(TAG, "vlm req model=" + model + " jpeg=" + jpeg.length + "B");
                JSONObject body = new JSONObject();
                body.put("model", model);
                body.put("max_tokens", 60);
                JSONArray messages = new JSONArray();
                messages.put(new JSONObject().put("role", "system").put("content",
                        "你是哈基米的视觉助手。只用一句话、最多 25 个字说清画面或手势，"
                                + "不要客套、不要分点、不要解释过程。"));
                JSONArray content = new JSONArray();
                content.put(new JSONObject()
                        .put("type", "image_url")
                        .put("image_url", new JSONObject()
                                .put("url", "data:image/jpeg;base64," + b64)));
                content.put(new JSONObject()
                        .put("type", "text")
                        .put("text", question == null || question.trim().isEmpty()
                                ? "描述一下画面里有什么" : question.trim()));
                messages.put(new JSONObject().put("role", "user").put("content", content));
                body.put("messages", messages);

                JSONObject out = post(app, url, key, body, 60);
                if (out == null) {
                    Log.w(TAG, "vlm fail: network null");
                    postError(cb, "看图失败：网络错误");
                    return;
                }
                JSONArray choices = out.optJSONArray("choices");
                JSONObject msg = choices == null ? null
                        : (choices.optJSONObject(0) == null ? null
                        : choices.optJSONObject(0).optJSONObject("message"));
                if (msg == null) {
                    String err = out.optJSONObject("error") == null ? "视觉模型返回异常"
                            : out.optJSONObject("error").optString("message", "视觉模型返回异常");
                    Log.w(TAG, "vlm fail resp=" + out);
                    postError(cb, "看图失败：" + err);
                    return;
                }
                final String text = optNonNullString(msg, "content").trim();
                if (text.isEmpty()) {
                    Log.w(TAG, "vlm fail empty, resp=" + out);
                    postError(cb, "视觉模型没有返回内容");
                    return;
                }
                Log.i(TAG, "vlm ok text=" + text);
                ui.post(() -> cb.onText(text));
            } catch (Exception e) {
                Log.w(TAG, "vlm failed", e);
                postError(cb, "看图失败：" + e.getMessage());
            }
        }, "phone-vlm").start();
    }

    /** 省费开关：关闭 0.3s 预识别可省约 35% ASR 调用次数（默认省费关闭预识别） */
    static boolean probeEnabled(Context c) {
        return BridgeService.prefs(c).getBoolean("asr_probe", false);
    }

    static void setProbeEnabled(Context c, boolean enabled) {
        BridgeService.prefs(c).edit().putBoolean("asr_probe", enabled).apply();
    }

    static boolean isLocalUrl(String url) {
        if (url == null) return false;
        String u = url.trim().toLowerCase(java.util.Locale.US);
        return u.startsWith("http://127.0.0.1")
                || u.startsWith("http://localhost")
                || u.startsWith("http://10.")
                || u.startsWith("http://192.168.")
                || u.startsWith("http://172.");
    }

    private static String trimSlash(String s) {
        String out = s.trim();
        while (out.endsWith("/")) out = out.substring(0, out.length() - 1);
        return out;
    }

    static void resetChat() {
        synchronized (history) {
            history.clear();
        }
    }

    // ------------------------------------------------------------ 识别

    /**
     * 16k 单声道 PCM → 文字。回调在主线程。
     * 自动适配：千问 ASR（chat/completions + input_audio）与免费标准 OpenAI（/audio/transcriptions，如硅基流动 SenseVoiceSmall）。
     */
    static void recognize(Context c, byte[] pcm, int sampleRate, TextCallback cb) {
        final Context app = c.getApplicationContext();
        final String base = asrBase(app);
        final String model = asrModel(app);
        final String key = asrKey(app);
        if (key.isEmpty() && !isLocalUrl(base)) {
            postError(cb, "没连电脑，且未填识别 Key");
            return;
        }
        if (model.toLowerCase(java.util.Locale.US).startsWith("paraformer")) {
            recognizeParaformer(app, key, model, pcm, sampleRate, cb);
            return;
        }
        final byte[] wav = wav(pcm, sampleRate);
        new Thread(() -> {
            final long t0 = System.currentTimeMillis();
            try {
                boolean useDashScope = base.toLowerCase(java.util.Locale.US).contains("dashscope")
                        || model.toLowerCase(java.util.Locale.US).contains("qwen3-asr");
                String result;
                if (useDashScope) {
                    JSONObject body = new JSONObject();
                    body.put("model", model);
                    body.put("stream", false);
                    body.put("asr_options", new JSONObject().put("language", "zh").put("enable_itn", true));
                    JSONArray content = new JSONArray();
                    content.put(new JSONObject()
                            .put("type", "input_audio")
                            .put("input_audio", new JSONObject()
                                    .put("data", "data:audio/wav;base64,"
                                            + android.util.Base64.encodeToString(wav, android.util.Base64.NO_WRAP))));
                    JSONArray messages = new JSONArray();
                    messages.put(new JSONObject().put("role", "user").put("content", content));
                    body.put("messages", messages);

                    JSONObject out = post(app, base + "/chat/completions", key, body, 12);
                    if (out == null) {
                        Thread.sleep(250);
                        out = post(app, base + "/chat/completions", key, body, 12);
                    }
                    if (out == null) {
                        postError(cb, "识别失败：网络错误");
                        return;
                    }
                    JSONObject msg = out.optJSONArray("choices") != null
                            ? out.optJSONArray("choices").optJSONObject(0)
                            : null;
                    msg = msg == null ? null : msg.optJSONObject("message");
                    Object raw = msg == null ? null : msg.opt("content");
                    String text = raw instanceof JSONArray
                            ? joinText((JSONArray) raw)
                            : (raw == null ? "" : String.valueOf(raw));
                    result = cleanAsrText(text);
                } else {
                    OkHttpClient client = HTTP.newBuilder().readTimeout(12, TimeUnit.SECONDS).build();
                    String respText = null;
                    int code = 0;
                    String errBody = "";
                    for (int attempt = 0; attempt < 2 && respText == null; attempt++) {
                        if (attempt > 0) Thread.sleep(250);
                        Request req = new Request.Builder()
                                .url(base + "/audio/transcriptions")
                                .header("accept", "application/json")
                                .post(new MultipartBody.Builder()
                                        .setType(MultipartBody.FORM)
                                        .addFormDataPart("model", model)
                                        .addFormDataPart("file", "voice.wav", RequestBody.create(WAV, wav))
                                        .build())
                                .build();
                        if (!key.isEmpty()) req = req.newBuilder()
                                .header("authorization", "Bearer " + key).build();
                        try (Response res = client.newCall(req).execute()) {
                            String body = res.body() == null ? "" : res.body().string();
                            if (res.isSuccessful()) {
                                respText = body;
                            } else {
                                code = res.code();
                                errBody = body;
                            }
                        } catch (java.io.IOException e) {
                            if (attempt == 1) throw e;
                        }
                    }
                    if (respText == null) {
                        throw new Exception("HTTP " + code + " " + extractErr(errBody));
                    }
                    JSONObject out = new JSONObject(respText);
                    result = cleanAsrText(optNonNullString(out, "text"));
                    if (result.isEmpty()) {
                        // 服务端听到了但没给出文字时，把原始返回打出来，便于判断是静音还是格式问题
                        Log.i(TAG, "asr empty resp=" + respText);
                    }
                }
                Log.i(TAG, "asr took " + (System.currentTimeMillis() - t0) + "ms model=" + model);
                final String finalText = result;
                ui.post(() -> {
                    if (finalText.isEmpty()) cb.onError("没听清");
                    else cb.onText(finalText);
                });
            } catch (Exception e) {
                Log.w(TAG, "phone asr failed", e);
                postError(cb, "识别失败：" + e.getMessage());
            }
        }, "phone-asr").start();
    }

    private static void recognizeParaformer(Context app, String key, String model,
                                            byte[] pcm, int sampleRate, TextCallback cb) {
        if (pcm == null || pcm.length == 0) {
            postError(cb, "没听清");
            return;
        }
        final long t0 = System.currentTimeMillis();
        final String taskId = java.util.UUID.randomUUID().toString();
        final java.util.concurrent.atomic.AtomicBoolean finished =
                new java.util.concurrent.atomic.AtomicBoolean();
        final java.util.concurrent.atomic.AtomicBoolean audioSent =
                new java.util.concurrent.atomic.AtomicBoolean();
        final StringBuffer finalTextBuf = new StringBuffer();
        final java.util.concurrent.atomic.AtomicReference<String> lastInterim =
                new java.util.concurrent.atomic.AtomicReference<>("");

        OkHttpClient client = HTTP.newBuilder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build();
        Request req = new Request.Builder()
                .url("wss://dashscope.aliyuncs.com/api-ws/v1/inference")
                .header("authorization", "Bearer " + key)
                .build();

        final java.util.concurrent.atomic.AtomicReference<okhttp3.WebSocket> wsRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        final Runnable timeoutTask = () -> {
            if (finished.compareAndSet(false, true)) {
                okhttp3.WebSocket active = wsRef.getAndSet(null);
                if (active != null) active.cancel();
                cb.onError("识别超时");
            }
        };
        ui.postDelayed(timeoutTask, 15000);

        okhttp3.WebSocket ws = client.newWebSocket(req, new okhttp3.WebSocketListener() {
            @Override
            public void onOpen(okhttp3.WebSocket ws, okhttp3.Response response) {
                try {
                    JSONObject params = new JSONObject()
                            .put("format", "pcm")
                            .put("sample_rate", sampleRate)
                            .put("punctuation_prediction_enabled", true)
                            .put("inverse_text_normalization_enabled", true);
                    JSONObject payload = new JSONObject()
                            .put("task_group", "audio")
                            .put("task", "asr")
                            .put("function", "recognition")
                            .put("model", model)
                            .put("parameters", params)
                            .put("input", new JSONObject());
                    JSONObject header = new JSONObject()
                            .put("action", "run-task")
                            .put("task_id", taskId)
                            .put("streaming", "duplex");
                    ws.send(new JSONObject().put("header", header).put("payload", payload).toString());
                } catch (Exception e) {
                    if (finished.compareAndSet(false, true)) {
                        ui.removeCallbacks(timeoutTask);
                        ws.cancel();
                        postError(cb, "识别请求构造失败");
                    }
                }
            }

            @Override
            public void onMessage(okhttp3.WebSocket ws, String message) {
                try {
                    JSONObject m = new JSONObject(message);
                    JSONObject header = m.optJSONObject("header");
                    String ev = header == null ? "" : header.optString("event");
                    if ("task-started".equals(ev)) {
                        if (!audioSent.compareAndSet(false, true)) return;
                        new Thread(() -> {
                            try {
                                int off = 0;
                                int chunk = 3200;
                                while (off < pcm.length && !finished.get()) {
                                    int len = Math.min(chunk, pcm.length - off);
                                    if (!ws.send(okio.ByteString.of(pcm, off, len))) break;
                                    off += len;
                                }
                                if (!finished.get()) {
                                    JSONObject fin = new JSONObject()
                                            .put("header", new JSONObject()
                                                    .put("action", "finish-task")
                                                    .put("task_id", taskId)
                                                    .put("streaming", "duplex"))
                                            .put("payload", new JSONObject().put("input", new JSONObject()));
                                    ws.send(fin.toString());
                                }
                            } catch (Exception e) {
                                Log.w(TAG, "paraformer send failed", e);
                            }
                        }, "paraformer-send").start();
                    } else if ("result-generated".equals(ev)) {
                        JSONObject payload = m.optJSONObject("payload");
                        JSONObject output = payload == null ? null : payload.optJSONObject("output");
                        JSONObject sentence = output == null ? null : output.optJSONObject("sentence");
                        if (sentence != null && !sentence.optBoolean("heartbeat", false)) {
                            String txt = optNonNullString(sentence, "text").trim();
                            if (!txt.isEmpty()) {
                                if (sentence.optBoolean("sentence_end", false)) {
                                    finalTextBuf.append(txt);
                                    lastInterim.set("");
                                } else {
                                    lastInterim.set(txt);
                                }
                            }
                        }
                    } else if ("task-finished".equals(ev)) {
                        if (!finished.compareAndSet(false, true)) return;
                        ui.removeCallbacks(timeoutTask);
                        ws.close(1000, "done");
                        String combined = finalTextBuf.toString();
                        if (combined.isEmpty()) combined = lastInterim.get();
                        final String result = cleanAsrText(combined);
                        Log.i(TAG, "asr took " + (System.currentTimeMillis() - t0) + "ms model=" + model);
                        ui.post(() -> {
                            if (result.isEmpty()) cb.onError("没听清");
                            else cb.onText(result);
                        });
                    } else if ("task-failed".equals(ev)) {
                        if (!finished.compareAndSet(false, true)) return;
                        ui.removeCallbacks(timeoutTask);
                        String errMsg = header == null ? "" : header.optString("error_message", "识别失败");
                        Log.w(TAG, "paraformer task-failed: " + message);
                        ws.close(1000, "failed");
                        postError(cb, "识别失败：" + errMsg);
                    }
                } catch (Exception ignored) {
                }
            }

            @Override
            public void onFailure(okhttp3.WebSocket ws, Throwable t, okhttp3.Response response) {
                if (!finished.compareAndSet(false, true)) return;
                ui.removeCallbacks(timeoutTask);
                int code = response == null ? 0 : response.code();
                Log.w(TAG, "paraformer ws failed code=" + code, t);
                postError(cb, code == 401 || code == 403
                        ? "阿里云 Key 无效或未开通识别权限 (HTTP " + code + ")"
                        : (code > 0 ? "识别连接失败 (HTTP " + code + ")" : "识别连接失败"));
            }

            @Override
            public void onClosed(okhttp3.WebSocket ws, int code, String reason) {
                if (!finished.compareAndSet(false, true)) return;
                ui.removeCallbacks(timeoutTask);
                String combined = finalTextBuf.toString();
                if (combined.isEmpty()) combined = lastInterim.get();
                final String result = cleanAsrText(combined);
                ui.post(() -> {
                    if (result.isEmpty()) cb.onError("没听清");
                    else cb.onText(result);
                });
            }
        });
        wsRef.set(ws);
    }

    private static String optNonNullString(JSONObject obj, String key) {
        if (obj == null || obj.isNull(key)) return "";
        return obj.optString(key, "");
    }

    private static String cleanAsrText(String text) {
        if (text == null) return "";
        // SenseVoiceSmall 有时附带 <|zh|><|NEUTRAL|><|Speech|> 标签，统一剥离
        return text.replaceAll("<\\|[^|>]+\\|>", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String extractErr(String text) {
        try {
            JSONObject err = new JSONObject(text).optJSONObject("error");
            if (err != null) return err.optString("message", text);
        } catch (Exception ignored) {
        }
        return text == null ? "" : text.substring(0, Math.min(120, text.length()));
    }

    private static String joinText(JSONArray arr) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null) sb.append(o.optString("text", ""));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ 对话

    private static final String CAMERA_PROMPT_HINT =
            "\n如果用户的提问必须通过手机摄像头看当前眼前画面、手势、物体或穿着才能回答，"
                    + "请只回复 [CAM] 这5个字符，不要输出任何其他字；否则直接简短回答。";

    static boolean isCameraMarker(String reply) {
        if (reply == null) return false;
        String t = reply.trim().toUpperCase(java.util.Locale.US);
        return t.equals("[CAM]") || t.equals("【CAM】") || t.startsWith("[CAM]");
    }

    /** 只有用户明确要求「看」时才允许走视觉，避免「明天穿什么衣服」这类问题误开相机 */
    static boolean looksLikeLookRequest(String text) {
        if (text == null) return false;
        String t = text.replace(" ", "");
        String[] cues = {
                "看看", "看一下", "看下", "瞧瞧", "瞅瞅", "拍我", "镜头",
                "这是什么", "那是啥", "那是什么", "什么颜色", "几个手指", "比个",
                "手势", "表情", "长相", "我手里", "手上拿", "桌上", "面前", "屏幕上",
                "我穿", "我这身", "打扮", "我在干嘛", "我在做什么", "房间里", "窗外", "认得我"
        };
        for (String c : cues) {
            if (t.contains(c)) return true;
        }
        return false;
    }

    /** 本机当前时间，给大模型回答「现在几点/今天几号/星期几」时做对齐 */
    private static String nowText() {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(
                "yyyy年M月d日 EEEE HH:mm", java.util.Locale.CHINA);
        return f.format(new java.util.Date());
    }

    /** 一问一答（不带流式），回调在主线程 */
    static void chat(Context c, String text, TextCallback cb) {        chat(c, text, false, cb);
    }

    /**
     * @param allowCamera 为 true 时允许模型在需要看眼前画面时返回 "[CAM]" 触发相机
     */
    static void chat(Context c, String text, boolean allowCamera, TextCallback cb) {
        final Context app = c.getApplicationContext();
        final String base = chatBase(app);
        final String key = chatKey(app);
        if (key.isEmpty() && !isLocalUrl(base)) {
            postError(cb, "没连电脑，且未填对话 Key");
            return;
        }
        new Thread(() -> {
            try {
                JSONObject body = new JSONObject();
                String model = chatModel(app);
                body.put("model", model);
                body.put("temperature", 0.7);
                body.put("max_tokens", 80);
                // 仅对明确支持关闭思考链的云平台下发对应开关，保证短句语音低延迟
                String lowerBase = base.toLowerCase(java.util.Locale.US);
                String lowerModel = model.toLowerCase(java.util.Locale.US);
                boolean addedThinking = false;
                if (lowerBase.contains("bigmodel")) {
                    if (lowerModel.contains("glm-4.7") || lowerModel.contains("glm-4.6")
                            || lowerModel.contains("glm-4.5") || lowerModel.contains("glm-z1")) {
                        body.put("thinking", new JSONObject().put("type", "disabled"));
                        addedThinking = true;
                    }
                } else if ((lowerBase.contains("siliconflow") || lowerBase.contains("dashscope"))
                        && (lowerModel.contains("xing4") || lowerModel.contains("qwen3"))) {
                    body.put("enable_thinking", false);
                    addedThinking = true;
                }
                JSONArray messages = new JSONArray();
                String sysPrompt = (allowCamera ? (PERSONA + CAMERA_PROMPT_HINT) : PERSONA)
                        + "\n当前时间（手机本机时钟，问日期/星期/几点一律以此为准）：" + nowText();
                messages.put(new JSONObject().put("role", "system").put("content", sysPrompt));
                synchronized (history) {
                    for (String[] turn : history) {
                        messages.put(new JSONObject().put("role", "user").put("content", turn[0]));
                        messages.put(new JSONObject().put("role", "assistant").put("content", turn[1]));
                    }
                }
                messages.put(new JSONObject().put("role", "user").put("content", text));
                body.put("messages", messages);

                JSONObject out;
                try {
                    out = post(app, base + "/chat/completions", key, body, 60);
                } catch (Exception e) {
                    if (addedThinking && String.valueOf(e.getMessage()).contains("400")) {
                        body.remove("enable_thinking");
                        body.remove("thinking");
                        out = post(app, base + "/chat/completions", key, body, 60);
                    } else {
                        throw e;
                    }
                }
                if (out == null) {
                    postError(cb, "对话失败：网络错误");
                    return;
                }
                JSONObject msg = null;
                JSONArray choices = out.optJSONArray("choices");
                if (choices != null && choices.optJSONObject(0) != null) {
                    msg = choices.optJSONObject(0).optJSONObject("message");
                }
                if (msg == null) {
                    String err = out.optJSONObject("error") == null
                            ? "模型返回异常"
                            : out.optJSONObject("error").optString("message", "模型返回异常");
                    postError(cb, "对话失败：" + err);
                    return;
                }
                final String reply = optNonNullString(msg, "content")
                        .replaceAll("\\s+", " ").trim();
                if (reply.isEmpty()) {
                    postError(cb, "模型没有返回内容");
                    return;
                }
                if (allowCamera && isCameraMarker(reply)) {
                    ui.post(() -> cb.onText("[CAM]"));
                    return;
                }
                synchronized (history) {
                    history.add(new String[]{text, reply});
                    while (history.size() > CHAT_TURNS) history.remove(0);
                }
                ui.post(() -> cb.onText(reply));
            } catch (Exception e) {
                Log.w(TAG, "phone chat failed", e);
                postError(cb, "对话失败：" + e.getMessage());
            }
        }, "phone-chat").start();
    }

    // ------------------------------------------------------------ 出声

    interface SpeakListener {
        void onStart(int sampleRate);

        void onPcm(byte[] pcm);

        void onDone();

        void onError(String message);
    }

    private static final String TTS_URL =
            "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation";

    interface AudioFileCallback {
        void onFile(java.io.File file);

        void onError(String message);
    }

    /** 标准 OpenAI /audio/speech 接口（支持本地 Kokoro/Piper 或硅基流动等免费 TTS） */
    static void speakSpeechFile(Context c, String text, AudioFileCallback cb) {
        final Context app = c.getApplicationContext();
        final String base = ttsBase(app);
        String key = asrKey(app);
        if (key.isEmpty()) key = chatKey(app);
        if (key.isEmpty() && !isLocalUrl(base)) {
            ui.post(() -> cb.onError("未配置 TTS Key"));
            return;
        }
        final String authKey = key;
        new Thread(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("model", ttsModel(app));
                body.put("input", text);
                body.put("voice", ttsVoice(app));
                body.put("response_format", "mp3");
                Request.Builder rb = new Request.Builder()
                        .url(base + "/audio/speech")
                        .post(RequestBody.create(JSON, body.toString()));
                if (!authKey.isEmpty()) rb.header("authorization", "Bearer " + authKey);
                OkHttpClient client = HTTP.newBuilder().readTimeout(30, TimeUnit.SECONDS).build();
                Response res = client.newCall(rb.build()).execute();
                byte[] bytes;
                try {
                    if (!res.isSuccessful() || res.body() == null) {
                        String msg = res.body() != null ? res.body().string() : ("HTTP " + res.code());
                        throw new Exception("HTTP " + res.code() + " " + extractErr(msg));
                    }
                    bytes = res.body().bytes();
                } finally {
                    res.close();
                }
                if (bytes.length < 128) throw new Exception("TTS 返回音频为空");
                java.io.File f = new java.io.File(app.getCacheDir(), "tts_speech.mp3");
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
                    out.write(bytes);
                }
                ui.post(() -> cb.onFile(f));
            } catch (Exception e) {
                Log.w(TAG, "openai speech tts failed", e);
                ui.post(() -> cb.onError("TTS 失败：" + e.getMessage()));
            }
        }, "phone-speech-tts").start();
    }

    /**
     * 流式 TTS：cosyvoice* 走双向 WebSocket，sambert* 走单向 WebSocket，其余走千问 SSE。
     */
    static void speak(Context c, String text, SpeakListener l) {
        String lower = ttsModel(c).toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("cosyvoice")) {
            speakCosy(c, text, l);
            return;
        }
        if (lower.startsWith("sambert")) {
            speakSambert(c, text, l);
            return;
        }
        speakQwen(c, text, l);
    }

    /** Sambert v1（每月1日重置3万字/音色长期免费）：单向 run-task 一次性发送文本，流式收 16k PCM */
    private static void speakSambert(Context c, String text, SpeakListener l) {
        final Context app = c.getApplicationContext();
        final String key = dashAuthKey(app);
        if (key.isEmpty()) {
            ui.post(() -> l.onError("未配置阿里云 Key"));
            return;
        }
        final String model = ttsModel(app);
        final int rate = 16000;
        final String taskId = java.util.UUID.randomUUID().toString();
        final java.util.concurrent.atomic.AtomicBoolean started =
                new java.util.concurrent.atomic.AtomicBoolean();
        final java.util.concurrent.atomic.AtomicBoolean finished =
                new java.util.concurrent.atomic.AtomicBoolean();

        OkHttpClient client = HTTP.newBuilder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build();
        Request req = new Request.Builder()
                .url("wss://dashscope.aliyuncs.com/api-ws/v1/inference")
                .header("authorization", "Bearer " + key)
                .build();

        final java.util.concurrent.atomic.AtomicReference<okhttp3.WebSocket> wsRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        final Runnable timeoutTask = () -> {
            if (finished.compareAndSet(false, true)) {
                okhttp3.WebSocket active = wsRef.getAndSet(null);
                if (active != null) active.cancel();
                l.onError("流式 TTS 超时");
            }
        };
        ui.postDelayed(timeoutTask, 30000);

        okhttp3.WebSocket ws = client.newWebSocket(req, new okhttp3.WebSocketListener() {
            @Override
            public void onOpen(okhttp3.WebSocket ws, okhttp3.Response response) {
                try {
                    JSONObject params = new JSONObject()
                            .put("text_type", "PlainText")
                            .put("format", "pcm")
                            .put("sample_rate", rate)
                            .put("volume", 60)
                            .put("rate", 1.0)
                            .put("pitch", 1.0);
                    JSONObject payload = new JSONObject()
                            .put("task_group", "audio")
                            .put("task", "tts")
                            .put("function", "SpeechSynthesizer")
                            .put("model", model)
                            .put("parameters", params)
                            .put("input", new JSONObject().put("text", text));
                    JSONObject header = new JSONObject()
                            .put("action", "run-task")
                            .put("task_id", taskId)
                            .put("streaming", "out");
                    ws.send(new JSONObject().put("header", header).put("payload", payload).toString());
                    Log.i(TAG, "sambert run-task model=" + model);
                } catch (Exception e) {
                    if (finished.compareAndSet(false, true)) {
                        ui.removeCallbacks(timeoutTask);
                        ws.cancel();
                        ui.post(() -> l.onError("Sambert 请求构造失败"));
                    }
                }
            }

            @Override
            public void onMessage(okhttp3.WebSocket ws, String message) {
                try {
                    JSONObject m = new JSONObject(message);
                    JSONObject header = m.optJSONObject("header");
                    String ev = header == null ? "" : header.optString("event");
                    if ("task-finished".equals(ev)) {
                        if (!finished.compareAndSet(false, true)) return;
                        ui.removeCallbacks(timeoutTask);
                        ws.close(1000, "done");
                        if (!started.get()) {
                            ui.post(() -> l.onError("Sambert 没有返回音频"));
                        } else {
                            ui.post(l::onDone);
                        }
                    } else if ("task-failed".equals(ev)) {
                        if (!finished.compareAndSet(false, true)) return;
                        ui.removeCallbacks(timeoutTask);
                        String errMsg = header == null ? "" : header.optString("error_message", "Sambert 合成失败");
                        Log.w(TAG, "sambert task-failed: " + message);
                        ws.close(1000, "failed");
                        ui.post(() -> l.onError(errMsg.isEmpty() ? "Sambert 合成失败" : errMsg));
                    }
                } catch (Exception ignored) {
                }
            }

            @Override
            public void onMessage(okhttp3.WebSocket ws, okio.ByteString bytes) {
                if (finished.get()) return;
                final byte[] pcm = bytes.toByteArray();
                if (!started.getAndSet(true)) {
                    Log.i(TAG, "sambert first audio chunk " + pcm.length + "B");
                    ui.post(() -> l.onStart(rate));
                }
                ui.post(() -> l.onPcm(pcm));
            }

            @Override
            public void onFailure(okhttp3.WebSocket ws, Throwable t, okhttp3.Response response) {
                if (!finished.compareAndSet(false, true)) return;
                ui.removeCallbacks(timeoutTask);
                int code = response == null ? 0 : response.code();
                Log.w(TAG, "sambert failed code=" + code, t);
                String msg = code == 401 || code == 403
                        ? "阿里云 Key 无效或额度已停 (HTTP " + code + ")"
                        : (code > 0 ? "Sambert 连接失败 (HTTP " + code + ")" : "Sambert 连接失败");
                ui.post(() -> l.onError(msg));
            }

            @Override
            public void onClosed(okhttp3.WebSocket ws, int code, String reason) {
                if (!finished.compareAndSet(false, true)) return;
                ui.removeCallbacks(timeoutTask);
                if (started.get()) ui.post(l::onDone);
                else ui.post(() -> l.onError("Sambert 已关闭"));
            }
        });
        wsRef.set(ws);
    }

    /** CosyVoice v3：音频是二进制帧，控制信息是 JSON 文本帧 */
    private static void speakCosy(Context c, String text, SpeakListener l) {
        final Context app = c.getApplicationContext();
        final String key = dashAuthKey(app);
        if (key.isEmpty()) {
            ui.post(() -> l.onError("未配置千问 Key"));
            return;
        }
        final String model = ttsModel(app);
        final String voice = ttsVoice(app);
        final int rate = 24000;
        final String taskId = java.util.UUID.randomUUID().toString();
        final java.util.concurrent.atomic.AtomicBoolean started =
                new java.util.concurrent.atomic.AtomicBoolean();
        final java.util.concurrent.atomic.AtomicBoolean finished =
                new java.util.concurrent.atomic.AtomicBoolean();

        OkHttpClient client = HTTP.newBuilder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build();
        Request req = new Request.Builder()
                .url("wss://dashscope.aliyuncs.com/api-ws/v1/inference")
                .header("authorization", "Bearer " + key)
                .build();

        final java.util.concurrent.atomic.AtomicReference<okhttp3.WebSocket> wsRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        final Runnable timeoutTask = () -> {
            if (finished.compareAndSet(false, true)) {
                okhttp3.WebSocket active = wsRef.getAndSet(null);
                if (active != null) active.cancel();
                l.onError("流式 TTS 超时");
            }
        };
        ui.postDelayed(timeoutTask, 30000);

        okhttp3.WebSocket ws = client.newWebSocket(req, new okhttp3.WebSocketListener() {
            @Override
            public void onOpen(okhttp3.WebSocket ws, okhttp3.Response response) {
                try {
                    JSONObject params = new JSONObject()
                            .put("text_type", "PlainText")
                            .put("voice", voice)
                            .put("format", "pcm")
                            .put("sample_rate", rate)
                            .put("volume", 60)
                            .put("rate", 1)
                            .put("pitch", 1);
                    JSONObject payload = new JSONObject()
                            .put("task_group", "audio")
                            .put("task", "tts")
                            .put("function", "SpeechSynthesizer")
                            .put("model", model)
                            .put("parameters", params)
                            .put("input", new JSONObject());
                    JSONObject header = new JSONObject()
                            .put("action", "run-task")
                            .put("task_id", taskId)
                            .put("streaming", "duplex");
                    ws.send(new JSONObject().put("header", header).put("payload", payload).toString());
                    Log.i(TAG, "cosy run-task model=" + model + " voice=" + voice);
                } catch (Exception e) {
                    if (finished.compareAndSet(false, true)) {
                        ui.removeCallbacks(timeoutTask);
                        ws.cancel();
                        ui.post(() -> l.onError("TTS 请求构造失败"));
                    }
                }
            }

            @Override
            public void onMessage(okhttp3.WebSocket ws, String message) {
                try {
                    JSONObject m = new JSONObject(message);
                    JSONObject header = m.optJSONObject("header");
                    String ev = header == null ? "" : header.optString("event");
                    if (!"task-started".equals(ev) && !"result-generated".equals(ev)) {
                        Log.i(TAG, "cosy event=" + ev + " msg=" + message);
                    }
                    if ("task-started".equals(ev)) {
                        JSONObject cont = new JSONObject()
                                .put("header", new JSONObject()
                                        .put("action", "continue-task").put("task_id", taskId))
                                .put("payload", new JSONObject()
                                        .put("input", new JSONObject().put("text", text)));
                        JSONObject fin = new JSONObject()
                                .put("header", new JSONObject()
                                        .put("action", "finish-task").put("task_id", taskId))
                                .put("payload", new JSONObject().put("input", new JSONObject()));
                        ws.send(cont.toString());
                        ws.send(fin.toString());
                    } else if ("task-finished".equals(ev)) {
                        if (!finished.compareAndSet(false, true)) return;
                        ui.removeCallbacks(timeoutTask);
                        ws.close(1000, "done");
                        if (!started.get()) {
                            ui.post(() -> l.onError("CosyVoice 没有返回音频"));
                        } else {
                            ui.post(l::onDone);
                        }
                    } else if ("task-failed".equals(ev)) {
                        if (!finished.compareAndSet(false, true)) return;
                        ui.removeCallbacks(timeoutTask);
                        Log.w(TAG, "cosy task-failed: " + message);
                        ws.close(1000, "failed");
                        ui.post(() -> l.onError("CosyVoice 合成失败"));
                    }
                } catch (Exception ignored) {
                    /* 不是 JSON 就忽略 */
                }
            }

            @Override
            public void onMessage(okhttp3.WebSocket ws, okio.ByteString bytes) {
                if (finished.get()) return;
                final byte[] pcm = bytes.toByteArray();
                if (!started.getAndSet(true)) {
                    Log.i(TAG, "cosy first audio chunk " + pcm.length + "B");
                    ui.post(() -> l.onStart(rate));
                }
                ui.post(() -> l.onPcm(pcm));
            }

            @Override
            public void onFailure(okhttp3.WebSocket ws, Throwable t, okhttp3.Response response) {
                if (!finished.compareAndSet(false, true)) return;
                ui.removeCallbacks(timeoutTask);
                Log.w(TAG, "cosyvoice failed", t);
                ui.post(() -> l.onError("CosyVoice 连接失败"));
            }

            @Override
            public void onClosed(okhttp3.WebSocket ws, int code, String reason) {
                if (!finished.compareAndSet(false, true)) return;
                ui.removeCallbacks(timeoutTask);
                if (started.get()) ui.post(l::onDone);
                else ui.post(() -> l.onError("CosyVoice 已关闭"));
            }
        });
        wsRef.set(ws);
    }

    /**
     * 千问流式 TTS（SSE）：边合成边回调 PCM，和电脑端同一个音色。
     * 第一段数据自带 44 字节 WAV 头，切掉并把采样率回调出去。
     */
    private static void speakQwen(Context c, String text, SpeakListener l) {
        final Context app = c.getApplicationContext();
        final String key = dashAuthKey(app);
        if (key.isEmpty()) {
            ui.post(() -> l.onError("未配置千问 Key"));
            return;
        }
        final String model = ttsModel(app);
        final String voice = ttsVoice(app);
        new Thread(() -> {
            OkHttpClient client = HTTP.newBuilder().readTimeout(60, TimeUnit.SECONDS).build();
            try {
                JSONObject body = new JSONObject();
                body.put("model", model.isEmpty() ? "qwen3-tts-flash" : model);
                body.put("input", new JSONObject()
                        .put("text", text)
                        .put("voice", voice)
                        .put("language_type", "Chinese"));
                Request req = new Request.Builder()
                        .url(TTS_URL)
                        .header("authorization", "Bearer " + key)
                        .header("X-DashScope-SSE", "enable")
                        .header("accept", "text/event-stream")
                        .post(RequestBody.create(JSON, body.toString()))
                        .build();
                Response res = client.newCall(req).execute();
                if (!res.isSuccessful() || res.body() == null) {
                    String code = String.valueOf(res.code());
                    res.close();
                    throw new Exception("HTTP " + code);
                }
                boolean started = false;
                int totalBytes = 0;
                ByteArrayOutputStream head = new ByteArrayOutputStream(128);
                try {
                    okio.BufferedSource src = res.body().source();
                    String line;
                    while ((line = src.readUtf8Line()) != null) {
                        if (!line.startsWith("data:")) continue;
                        String raw = line.substring(5).trim();
                        if (raw.isEmpty() || "[DONE]".equals(raw)) continue;
                        JSONObject evt = new JSONObject(raw);
                        JSONObject out = evt.optJSONObject("output");
                        JSONObject audio = out == null ? null : out.optJSONObject("audio");
                        String data = audio == null ? null : audio.optString("data", "");
                        if (data == null || data.isEmpty()) continue;
                        byte[] bytes = android.util.Base64.decode(data, android.util.Base64.DEFAULT);
                        if (!started) {
                            head.write(bytes);
                            if (head.size() < 44) continue;
                            byte[] all = head.toByteArray();
                            started = true;
                            totalBytes += all.length - 44;
                            final int rate = (all[24] & 0xff) | ((all[25] & 0xff) << 8)
                                    | ((all[26] & 0xff) << 16) | ((all[27] & 0xff) << 24);
                            final int sampleRate = rate > 0 ? rate : 24000;
                            ui.post(() -> l.onStart(sampleRate));
                            if (all.length > 44) {
                                final byte[] pcm = java.util.Arrays.copyOfRange(all, 44, all.length);
                                ui.post(() -> l.onPcm(pcm));
                            }
                        } else {
                            final byte[] pcm = bytes;
                            totalBytes += pcm.length;
                            ui.post(() -> l.onPcm(pcm));
                        }
                    }
                } finally {
                    res.close();
                }
                Log.i(TAG, "phone tts bytes=" + totalBytes + " started=" + started);
                if (!started) {
                    ui.post(() -> l.onError("TTS 没有返回音频"));
                    return;
                }
                ui.post(l::onDone);
            } catch (Exception e) {
                Log.w(TAG, "phone tts failed", e);
                ui.post(() -> l.onError("TTS 失败：" + e.getMessage()));
            }
        }, "phone-tts").start();
    }

    // ------------------------------------------------------------ 工具

    private static JSONObject post(Context c, String url, String key, JSONObject body, int timeoutSec)
            throws Exception {
        OkHttpClient client = HTTP.newBuilder()
                .readTimeout(timeoutSec, TimeUnit.SECONDS)
                .build();
        Request.Builder rb = new Request.Builder()
                .url(url)
                .post(RequestBody.create(JSON, body.toString()));
        if (key != null && !key.isEmpty()) rb.header("authorization", "Bearer " + key);
        Request req = rb.build();
        Response res = client.newCall(req).execute();
        String text;
        try {
            text = res.body() == null ? "" : res.body().string();
        } finally {
            res.close();
        }
        if (!res.isSuccessful()) {
            String msg = text;
            try {
                JSONObject err = new JSONObject(text).optJSONObject("error");
                if (err != null) msg = err.optString("message", text);
            } catch (Exception ignored) {
            }
            throw new Exception("HTTP " + res.code() + " " + msg);
        }
        return new JSONObject(text);
    }

    private static void postError(TextCallback cb, String message) {
        ui.post(() -> cb.onError(message));
    }

    // WAV 头构造抽到了 WavUtil（与 DeviceActivity 落盘版共用），这里只留薄封装
    /** PCM（16 bit 单声道）套 WAV 头，云端 ASR 只认带头的文件 */
    static byte[] wav(byte[] pcm, int sampleRate) {
        return WavUtil.wavBytes(pcm, sampleRate);
    }
}
