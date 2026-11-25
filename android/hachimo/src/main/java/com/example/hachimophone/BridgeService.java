package com.example.hachimophone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

/**
 * 常驻前台服务：维持与 PC Bridge 的 WebSocket 连接、心跳、断线重连。
 * 界面（内屏调试页 / 外屏展示页）只读 {@link AppState}，不直接碰网络。
 */
public final class BridgeService extends Service {

    private static final String TAG = "HachimoPhone";
    private static final String PREFS = "hachimo";
    private static final String KEY_URL = "bridge_url";
    private static final String KEY_AUTO = "auto_start";
    private static final String KEY_FOLDED = "folded";
    private static final String KEY_DISPLAY = "cover_display_id";
    private static final String KEY_SFX = "sfx_enabled";
    private static final String KEY_TOKEN = "bridge_token";
    private static final String KEY_USER_OFF = "user_disconnected";
    private static final String KEY_PC_ASR = "pc_asr";
    private static final String KEY_BRIDGE_FP = "bridge_fp";
    private static final String CHANNEL = "bridge";
    private static final int NOTIF_ID = 41;
    private static final long PING_MS = 5000;
    private static final long RETRY_MAX_MS = 30000;
    /** 与 PC Bridge 的 UDP 广播端口一致（server.mjs 的 BEACON_PORT） */
    private static final int BEACON_PORT = 8767;

    private final BridgeClient client = new BridgeClient();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile int retries;
    private int connectGeneration;
    private String url;
    private String lastConfiguredUrl;
    private volatile java.net.DatagramSocket discoverSocket;
    private boolean stopped;
    private boolean started;
    private volatile boolean connectedNow;
    private volatile boolean connecting;
    private volatile long lastPongAt;
    private Boolean lastFolded;

    /** 供界面直接触发一次「确认」，避免为此引入 bindService。 */
    private static volatile BridgeService instance;

    static void confirmCurrent(String sessionId) {
        BridgeService s = instance;
        if (s != null) s.confirm(sessionId);
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 版本号（外屏和调试页都显示，方便确认手机上装的是哪一版） */
    static String versionLabel(Context c) {
        try {
            android.content.pm.PackageInfo pi =
                    c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            String ver = pi.versionName == null || pi.versionName.isEmpty() ? "1.0.2-lite" : pi.versionName;
            return ver + " (" + pi.versionCode + ")";
        } catch (Exception e) {
            return "1.0.2-lite";
        }
    }

    /** 本地 ML Kit 识别（人脸追踪）：默认关，只保留相机画面和按需取帧 */
    static boolean mlkitEnabled(Context c) {
        return prefs(c).getBoolean("mlkit_enabled", false);
    }

    static void setMlkitEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean("mlkit_enabled", on).apply();
    }

    /** 充电保护：80% 断开、30% 恢复，需要 ESP32 继电器固件配合；接好线再打开 */
    static boolean chargeGuardEnabled(Context c) {
        return prefs(c).getBoolean("charge_guard", false);
    }

    static void setChargeGuardEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean("charge_guard", on).apply();
    }

    /**
     * 唤醒分级策略：
     *   0 = 智能分级（推荐）：KWS 命中直接唤醒；未命中时仅短句（220~1450ms）送 ASR 兜底，长闲聊本地丢弃
     *   1 = 纯离线（0 费）：仅认端侧 KWS，未命中一律本地丢弃
     *   2 = 全量兜底：KWS 未命中无论长短均送 ASR 复核
     */
    static final int WAKE_MODE_SMART = 0;
    static final int WAKE_MODE_STRICT = 1;
    static final int WAKE_MODE_ASR = 2;

    static int wakeMode(Context c) {
        SharedPreferences sp = prefs(c);
        if (sp.contains("wake_mode")) {
            int m = sp.getInt("wake_mode", WAKE_MODE_SMART);
            return (m >= 0 && m <= 2) ? m : WAKE_MODE_SMART;
        }
        if (sp.getBoolean("kws_strict", false)) return WAKE_MODE_STRICT;
        // 走阿里云识别（含免费 paraformer 10h/月）或付费方案默认智能分级省额度；纯免费无限次识别走全量兜底
        return (PhoneVoice.planPaid(c) || PhoneVoice.asrBase(c).contains("dashscope"))
                ? WAKE_MODE_SMART : WAKE_MODE_ASR;
    }

    static void setWakeMode(Context c, int mode) {
        int clamped = (mode >= 0 && mode <= 2) ? mode : WAKE_MODE_SMART;
        prefs(c).edit()
                .putInt("wake_mode", clamped)
                .putBoolean("kws_strict", clamped == WAKE_MODE_STRICT)
                .apply();
    }

    static boolean kwsStrict(Context c) {
        return wakeMode(c) == WAKE_MODE_STRICT;
    }

    static void setKwsStrict(Context c, boolean on) {
        setWakeMode(c, on ? WAKE_MODE_STRICT : WAKE_MODE_SMART);
    }

    static String bridgeUrl(Context c) {
        // 空串表示「自动发现」：连不上时监听 UDP 广播找 PC
        return prefs(c).getString(KEY_URL, "");
    }

    /**
     * 保存 Bridge 地址；非法 URL（例如少写了 ws://）返回 false。
     * 注意：OkHttp 的 HttpUrl 只认 http/https，ws/wss 要先换成 http/https 再校验，
     * 否则手填的 ws:// 地址会被一律判成「格式不对」。
     */
    static boolean setBridgeUrl(Context c, String value) {
        String v = value == null ? "" : value.trim();
        if (!v.isEmpty()) {
            String probe;
            if (v.startsWith("ws://")) probe = "http://" + v.substring(5);
            else if (v.startsWith("wss://")) probe = "https://" + v.substring(6);
            else return false;                       // 只接受 ws / wss
            okhttp3.HttpUrl parsed = okhttp3.HttpUrl.parse(probe);
            if (parsed == null || parsed.host() == null) return false;
        }
        prefs(c).edit().putString(KEY_URL, v).apply();
        return true;
    }

    /** 配对令牌：Bridge 写在 PC 的 bridge/token.txt，手机填一次即可 */
    static String bridgeToken(Context c) {
        return prefs(c).getString(KEY_TOKEN, "");
    }

    static void setBridgeToken(Context c, String token) {
        String next = token == null ? "" : token.trim();
        String prev = bridgeToken(c);
        SharedPreferences.Editor ed = prefs(c).edit().putString(KEY_TOKEN, next);
        // 换新令牌意味着服务端指纹也会变，清掉旧指纹绑定避免后续自动发现被拒
        if (!next.equals(prev)) ed.remove(KEY_BRIDGE_FP);
        ed.apply();
    }

    /** 用户是不是手动点过「断开」：是的话重开 App / 重启手机都不自动连 */
    static boolean userDisconnected(Context c) {
        return prefs(c).getBoolean(KEY_USER_OFF, false);
    }

    static void setUserDisconnected(Context c, boolean value) {
        prefs(c).edit().putBoolean(KEY_USER_OFF, value).apply();
    }

    /** 已配对 Bridge 的指纹（Beacon 里的 fp 字段）：首连成功后绑定，之后只认同一指纹的广播 */
    static String bridgeFingerprint(Context c) {
        return prefs(c).getString(KEY_BRIDGE_FP, "");
    }

    static void setBridgeFingerprint(Context c, String fp) {
        prefs(c).edit().putString(KEY_BRIDGE_FP, fp == null ? "" : fp.trim()).apply();
    }

    /** 外屏逻辑 display id，不同折叠屏可能不是 1（默认 1，可在调试页改） */
    static int coverDisplayId(Context c) {
        return prefs(c).getInt(KEY_DISPLAY, 1);
    }

    static void setCoverDisplayId(Context c, int id) {
        prefs(c).edit().putInt(KEY_DISPLAY, id).apply();
    }

    static boolean autoStart(Context c) {
        return prefs(c).getBoolean(KEY_AUTO, true);
    }

    /** 外屏音效开关（默认开，外屏长按或调试页可切换） */
    static boolean sfxEnabled(Context c) {
        return prefs(c).getBoolean(KEY_SFX, true);
    }

    static void setSfxEnabled(Context c, boolean enabled) {
        prefs(c).edit().putBoolean(KEY_SFX, enabled).apply();
    }

    /**
     * 语音识别走 PC（手机只录音）：手机自带识别连不上服务器时自动切过来，
     * 之后一直用它，直到用户清掉 App 数据。
     */
    static boolean pcAsr(Context c) {
        return prefs(c).getBoolean(KEY_PC_ASR, false);
    }

    static void setPcAsr(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY_PC_ASR, on).apply();
    }

    static void start(Context c) {
        Intent i = new Intent(c, BridgeService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            c.startForegroundService(i);
        } else {
            c.startService(i);
        }
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, BridgeService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createChannel();
        registerHinge();
    }

    // ------------------------------------------------------------ 合盖自动上外屏

    /** 折痕角度传感器（TYPE_HINGE_ANGLE = 36），合到 ≤60° 认定合盖 */
    private SensorEventListener hingeListener;
    private volatile boolean coverShown;
    private long lastCoverLaunchAt;

    private void registerHinge() {
        SensorManager sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sm == null) return;
        Sensor hinge = sm.getDefaultSensor(36);
        if (hinge == null) {
            Log.w(TAG, "no hinge sensor, auto cover launch disabled");
            return;
        }
        hingeListener = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent e) {
                float angle = e.values[0];
                boolean folded = angle <= 60f;
                if (lastFolded == null || lastFolded != folded) {
                    lastFolded = folded;
                    prefs(BridgeService.this).edit().putBoolean(KEY_FOLDED, folded).apply();
                }
                if (folded) {
                    if (!coverShown) {
                        coverShown = true;
                        if (System.currentTimeMillis() - lastCoverLaunchAt > 5000) {
                            lastCoverLaunchAt = System.currentTimeMillis();
                            launchCover();
                        }
                    }
                } else if (angle >= 120f) {
                    coverShown = false;
                }
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {
            }
        };
        sm.registerListener(hingeListener, hinge, SensorManager.SENSOR_DELAY_NORMAL);
        Log.i(TAG, "hinge sensor registered");
    }

    /** 尝试把展示页放到外屏（默认 display 1，可在调试页改）。后台启动可能被系统拦截，失败只记日志。 */
    private void launchCover() {
        int displayId = coverDisplayId(this);
        // 有些折叠屏（如华为 Mate XT）折起来也是 display 0，没有独立外屏：找不到就退回主屏
        if (!hasDisplay(displayId)) {
            Log.i(TAG, "display " + displayId + " not found, fallback to default");
            displayId = android.view.Display.DEFAULT_DISPLAY;
        }
        try {
            Intent i = new Intent(this, DeviceActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("auto_cover", true);
            // setLaunchDisplayId 是 API 26 才有的；老机型（Android 6/7）直接在主屏打开
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.app.ActivityOptions opts = android.app.ActivityOptions.makeBasic();
                opts.setLaunchDisplayId(displayId);
                startActivity(i, opts.toBundle());
            } else {
                startActivity(i);
            }
            Log.i(TAG, "cover launch requested display=" + displayId);
        } catch (Exception e) {
            Log.e(TAG, "cover launch failed: " + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    private boolean hasDisplay(int id) {
        android.hardware.display.DisplayManager dm =
                (android.hardware.display.DisplayManager) getSystemService(DISPLAY_SERVICE);
        if (dm == null) return false;
        for (android.view.Display d : dm.getDisplays()) {
            if (d != null && d.getDisplayId() == id) return true;
        }
        return false;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!startForegroundCompat()) return START_NOT_STICKY;
        boolean wasStarted = started;
        started = true;
        stopped = false;
        retries = 0;

        // 已经在连/已连上且目标没变就不要重复建连（BootReceiver + 界面会各拉起一次服务）
        String desired = bridgeUrl(this);
        boolean sameTarget = wasStarted && desired.equals(lastConfiguredUrl);
        lastConfiguredUrl = desired;
        if (sameTarget && (connectedNow || connecting)) {
            Log.i(TAG, "already connecting/connected, ignore duplicate start");
        } else {
            if (wasStarted) {
                client.close();
                closeDiscoverSocket();
            }
            url = desired.isEmpty() ? null : desired;
            connect();
        }

        // 服务重启（如安装更新）时若手机仍是合盖状态，补一次外屏拉起
        if (prefs(this).getBoolean(KEY_FOLDED, false)) {
            coverShown = true;
            handler.postDelayed(this::launchCover, 1500);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopped = true;
        started = false;
        connectedNow = false;
        connecting = false;
        instance = null;
        if (hingeListener != null) {
            SensorManager sm = (SensorManager) getSystemService(SENSOR_SERVICE);
            if (sm != null) sm.unregisterListener(hingeListener);
            hingeListener = null;
        }
        handler.removeCallbacksAndMessages(null);
        closeDiscoverSocket();
        client.close();
        AppState.get().setConnected(false, url);
        CoverWidgetProvider.refresh(this);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private boolean startForegroundCompat() {
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        Notification n = b.setContentTitle("HachimoPhone")
                .setContentText("正在连接 PC Bridge…")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setOngoing(true)
                .build();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(NOTIF_ID, n);
            }
            return true;
        } catch (Exception e) {
            // 后台启动被限制、或前台服务类型校验失败：不 crash，记日志后自行停止
            Log.e(TAG, "startForeground failed: " + e.getClass().getSimpleName() + " " + e.getMessage());
            stopSelf();
            return false;
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Bridge 连接", NotificationManager.IMPORTANCE_LOW);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(ch);
    }

    private void closeDiscoverSocket() {
        java.net.DatagramSocket ds = discoverSocket;
        discoverSocket = null;
        if (ds != null) {
            try {
                ds.close();
            } catch (Exception ignored) {
            }
        }
    }

    private void connect() {
        if (stopped) return;
        String configured = bridgeUrl(this);
        if (configured != null && !configured.trim().isEmpty()) {
            url = configured;
        } else if (retries >= 2) {
            // 自动发现模式下如果连续重连失败，清掉旧 IP 重新监听 UDP 广播（PC 可能换了 IP）
            url = null;
        }
        if (url == null || url.trim().isEmpty()) {
            discoverThenConnect();
            return;
        }
        connectNow();
    }

    /** 没配地址时，监听 PC 的 UDP 广播自动找 Bridge */
    private void discoverThenConnect() {
        connecting = true;
        final int generation = ++connectGeneration;
        closeDiscoverSocket();
        Log.i(TAG, "no bridge url configured, discovering on udp " + BEACON_PORT);
        new Thread(() -> {
            final DiscoveryResult found = discoverBridge(bridgeFingerprint(BridgeService.this));
            handler.post(() -> {
                if (stopped || generation != connectGeneration) return;
                if (found == null) {
                    connecting = false;
                    Log.i(TAG, "discovery timeout, will retry");
                    scheduleRetry();
                } else {
                    url = found.url;
                    pendingFingerprint = found.fingerprint;
                    Log.i(TAG, "discovered " + found.url);
                    connectNow();
                }
            });
        }, "bridge-discovery").start();
    }

    /** 自动发现结果：url + 该 Beacon 的指纹（首连成功后绑定） */
    private static final class DiscoveryResult {
        final String url;
        final String fingerprint;

        DiscoveryResult(String url, String fingerprint) {
            this.url = url;
            this.fingerprint = fingerprint;
        }
    }

    /** discoverThenConnect 拿到的候选指纹：连接成功（hello 应答）才写入绑定 */
    private String pendingFingerprint;

    /**
     * 监听 UDP 广播找 Bridge。
     * 已绑定过指纹（此前成功配对过）时只接受同一指纹的 Beacon，伪造广播无法劫持既有配对；
     * 未绑定时任何合法 Beacon 都可作为候选，首次 hello 成功后才落库绑定。
     */
    private DiscoveryResult discoverBridge(String boundFingerprint) {
        try (java.net.DatagramSocket socket = new java.net.DatagramSocket(null)) {
            discoverSocket = socket;
            socket.setReuseAddress(true);
            socket.setBroadcast(true);
            socket.bind(new java.net.InetSocketAddress(BEACON_PORT));
            long deadline = System.currentTimeMillis() + 3000;
            byte[] buf = new byte[512];
            while (System.currentTimeMillis() < deadline && !stopped) {
                int remain = (int) Math.max(100, deadline - System.currentTimeMillis());
                socket.setSoTimeout(remain);
                java.net.DatagramPacket packet = new java.net.DatagramPacket(buf, buf.length);
                socket.receive(packet);
                String text = new String(packet.getData(), 0, packet.getLength(), java.nio.charset.StandardCharsets.UTF_8);
                try {
                    JSONObject beacon = new JSONObject(text);
                    if (!"hachimodock-bridge".equals(beacon.optString("app"))) continue;
                    String fp = beacon.optString("fp", "");
                    // 已绑定指纹但 Beacon 不带指纹或对不上：疑似伪造，忽略继续等真广播
                    if (!boundFingerprint.isEmpty() && !fp.equals(boundFingerprint)) continue;
                    int port = beacon.optInt("port", 8765);
                    String path = beacon.optString("path", "/ws");
                    String url = "ws://" + packet.getAddress().getHostAddress() + ":" + port + path;
                    return fp.isEmpty() ? null : new DiscoveryResult(url, fp);
                } catch (Exception ignored) {
                    /* 局域网其它设备的广播包，跳过继续等 */
                }
            }
            return null;
        } catch (Exception e) {
            Log.w(TAG, "discover failed: " + e.getClass().getSimpleName());
            return null;
        } finally {
            discoverSocket = null;
        }
    }

    private void connectNow() {
        if (stopped) return;
        connecting = true;
        final int generation = ++connectGeneration;
        client.close();          // 重连前先关掉旧 socket，避免同时挂多条连接
        final String target = withToken(url, bridgeToken(this));
        Log.i(TAG, "connect " + maskToken(target));
        BridgeClient.Callback callback = new BridgeClient.Callback() {
            @Override
            public void onOpen() {
                retries = 0;
                connecting = false;
                connectedNow = true;
                lastPongAt = System.currentTimeMillis();
                AppState.get().setConnected(true, url);
                CoverWidgetProvider.refresh(BridgeService.this);
                try {
                    JSONObject hello = new JSONObject();
                    hello.put("type", "hello");
                    hello.put("v", 1);
                    hello.put("token", bridgeToken(BridgeService.this));
                    JSONObject device = new JSONObject();
                    device.put("name", Build.MODEL);
                    device.put("android", Build.VERSION.RELEASE);
                    device.put("cover_display", coverDisplayId(BridgeService.this));
                    hello.put("device", device);
                    client.send(hello);
                } catch (Exception e) {
                    Log.w(TAG, "hello failed", e);
                }
                schedulePing();
            }

            @Override
            public void onText(JSONObject packet) {
                handlePacket(packet);
            }

            @Override
            public void onClosed(String reason) {
                if (generation != connectGeneration) return;   // 旧连接的回调，忽略
                connecting = false;
                connectedNow = false;
                // 只清心跳和重试，别把 onStartCommand 刚排的「合盖拉起外屏」一起取消
                handler.removeCallbacks(pingTask);
                handler.removeCallbacks(retryTask);
                AppState.get().setConnected(false, url);
                CoverWidgetProvider.refresh(BridgeService.this);
                ChatBus.dispatchDisconnected();
                scheduleRetry();
            }
        };
        try {
            client.connect(target, callback);
        } catch (Exception e) {
            // 地址非法（用户手填错误）不能让主线程崩
            connecting = false;
            connectedNow = false;
            Log.e(TAG, "connect failed: " + e.getClass().getSimpleName() + " " + e.getMessage());
            AppState.get().setError("Bridge 地址无效：" + target);
            scheduleRetry();
        }
    }

    /** 地址里没带 token 时补上：服务端现在只认带令牌的连接 */
    private static String withToken(String base, String token) {
        if (base == null || base.isEmpty() || token == null || token.isEmpty()) return base;
        if (base.contains("token=")) return base;
        return base + (base.contains("?") ? "&" : "?") + "token=" + android.net.Uri.encode(token);
    }

    /** 日志脱敏：把 URL 里携带的令牌换成星号，避免打进 logcat 泄漏 */
    private static String maskToken(String target) {
        if (target == null) return "";
        int at = target.indexOf("token=");
        if (at < 0) return target;
        int end = target.indexOf('&', at);
        String tail = end < 0 ? "" : target.substring(end);
        return target.substring(0, at) + "token=***" + tail;
    }

    private void handlePacket(JSONObject p) {
        String type = p.optString("type");

        // 服务端 hello 应答：自动发现的连接至此才确认是配对过的 Bridge，落库绑定指纹，
        // 之后的自动发现只认它（伪造 Beacon 因指纹对不上而被拒）
        if ("hello".equals(type)) {
            if (pendingFingerprint != null && !pendingFingerprint.isEmpty()) {
                setBridgeFingerprint(this, pendingFingerprint);
                pendingFingerprint = null;
            }
            return;
        }

        if ("states".equals(type)) {            // 服务端整体快照：直接替换本地多 agent 缓存
            java.util.List<AppState.State> list = new java.util.ArrayList<>();
            org.json.JSONArray items = p.optJSONArray("items");
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject o = items.optJSONObject(i);
                    if (o != null) list.add(toState(o));
                }
            }
            AppState.get().replaceAll(list);
            CoverWidgetProvider.refresh(this);
            return;
        }

        if ("pong".equals(type)) {
            lastPongAt = System.currentTimeMillis();
            return;
        }

        if ("prompt_ack".equals(type)) {
            if (!p.optBoolean("ok", false)) {
                AppState.get().setError("指令发送失败：" + p.optString("error", "未知错误"));
            }
            return;
        }

        // 手机发给 Claude 的指令跑完了：把 Claude 的回答显示到外屏（id=-2 专门标记）
        if ("prompt_reply".equals(type)) {
            ChatBus.dispatch(new ChatBus.Reply("text", -2, true,
                    p.optString("text"), "", "", "", 16000, "", false));
            return;
        }

        if ("chat_text".equals(type)) {
            ChatBus.dispatch(ChatBus.Reply.text(p.optLong("id"), p.optString("text")));
            return;
        }

        if ("chat_audio_start".equals(type)) {
            ChatBus.dispatch(ChatBus.Reply.audioStart(p.optLong("id"), p.optInt("rate", 24000)));
            return;
        }

        if ("chat_audio_chunk".equals(type)) {
            ChatBus.dispatch(ChatBus.Reply.audioChunk(p.optLong("id"), p.optString("pcm")));
            return;
        }

        if ("chat_audio_end".equals(type)) {
            ChatBus.dispatch(ChatBus.Reply.audioEnd(p.optLong("id")));
            return;
        }

        if ("chat_reply".equals(type)) {
            if (p.optBoolean("need_camera", false)) {
                ChatBus.dispatch(ChatBus.Reply.needCamera(p.optLong("id"), p.optString("text")));
                return;
            }
            ChatBus.dispatch(new ChatBus.Reply(
                    "full",
                    p.optLong("id"),
                    p.optBoolean("ok", false),
                    p.optString("text"),
                    p.optString("audio"),
                    p.optString("wav"),
                    "",
                    p.optInt("sample_rate", 16000),
                    p.optString("error"),
                    p.optBoolean("streamed", false)));
            return;
        }

        if ("voice_text".equals(type)) {
            VoiceBus.dispatch(new VoiceBus.Result(
                    p.optLong("id"),
                    p.optBoolean("ok", false),
                    p.optString("text"),
                    p.optString("error")));
            return;
        }

        if ("error".equals(type)) {
            // 服务端鉴权失败或帧异常时会先发这个再断开
            AppState.get().setError("Bridge 拒绝连接：" + p.optString("error", "unauthorized"));
            return;
        }

        if (!"state".equals(type)) return;
        AppState.State s = toState(p);
        AppState.get().apply(s);
        CoverWidgetProvider.refresh(this);
    }

    private static AppState.State toState(JSONObject p) {
        AppState.State s = new AppState.State();
        s.agent = p.optString("agent");
        s.sessionId = p.optString("session_id");
        s.state = p.optString("state", "idle");
        s.label = p.optString("label");
        s.ts = p.optLong("ts");
        s.connected = true;
        return s;
    }

    private final Runnable pingTask = new Runnable() {
        @Override
        public void run() {
            try {
                JSONObject ping = new JSONObject();
                ping.put("type", "ping");
                ping.put("ts", System.currentTimeMillis());
                client.send(ping);
            } catch (Exception ignored) {
            }
            // 服务端 20s 没回 pong 说明连接已经静默死掉，主动走统一的重连路径
            if (System.currentTimeMillis() - lastPongAt > 20000) {
                Log.w(TAG, "pong timeout, reconnect");
                connecting = false;
                connectedNow = false;
                client.close();
                AppState.get().setConnected(false, url);
                CoverWidgetProvider.refresh(BridgeService.this);
                ChatBus.dispatchDisconnected();
                scheduleRetry();
                return;
            }
            schedulePing();
        }
    };

    private void schedulePing() {
        handler.removeCallbacks(pingTask);
        handler.postDelayed(pingTask, PING_MS);
    }

    private final Runnable retryTask = this::connect;

    private void scheduleRetry() {
        if (stopped) return;
        long delay = Math.min(RETRY_MAX_MS, 1000L << Math.min(retries, 5));
        retries++;
        Log.i(TAG, "retry in " + delay + "ms");
        handler.removeCallbacks(retryTask);
        handler.postDelayed(retryTask, delay);
    }

    /** 外屏「确认」：把当前等待项回传给 Bridge */
    void confirm(String sessionId) {
        try {
            JSONObject p = new JSONObject();
            p.put("type", "confirm");
            p.put("session_id", sessionId);
            client.send(p);
        } catch (Exception ignored) {
        }
    }

    /** 手机端下发一句话给 Claude（Bridge 转成 claude --resume ... -p） */
    static boolean sendPrompt(String text) {
        BridgeService s = instance;
        if (s == null || text == null || text.trim().isEmpty()) return false;
        return s.prompt(text.trim());
    }

    private boolean prompt(String text) {
        // 没连上时 client.send 会返回 false，不能再无条件告诉用户「已发送」
        if (!connectedNow) return false;
        try {
            JSONObject p = new JSONObject();
            p.put("type", "prompt");
            p.put("agent", "claude");
            p.put("text", text);
            p.put("session_id", AppState.get().snapshot().sessionId);
            return client.send(p);
        } catch (Exception e) {
            return false;
        }
    }

    /** 实时对话：把识别出来的话发给 Bridge，回复通过 {@link ChatBus} 回来 */
    static boolean sendChat(String text, long id) {
        BridgeService s = instance;
        if (s == null || text == null || text.trim().isEmpty() || !s.connectedNow) return false;
        try {
            JSONObject p = new JSONObject();
            p.put("type", "chat");
            p.put("id", id);
            p.put("text", text.trim());
            p.put("vlm_ready", PhoneVoice.vlmReady(s) && PhoneVoice.looksLikeLookRequest(text));
            return s.client.send(p);
        } catch (Exception e) {
            return false;
        }
    }

    /** 让 Bridge 用小狗音色直接朗读指定短句（欢迎语等） */
    static boolean sendSay(String text, long id) {
        BridgeService s = instance;
        if (s == null || text == null || text.trim().isEmpty() || !s.connectedNow) return false;
        try {
            JSONObject p = new JSONObject();
            p.put("type", "say");
            p.put("id", id);
            p.put("text", text.trim());
            return s.client.send(p);
        } catch (Exception e) {
            return false;
        }
    }

    /** 录音交给 PC 识别（16k 单声道 PCM，base64 传输） */
    static boolean sendVoice(byte[] pcm, int sampleRate, long id) {
        return sendVoice(pcm, sampleRate, id, 0, false);
    }

    /**
     * @param probeId 非 0 时表示「这段录音的预识别结果」（stop 时先发的那份），
     *                Bridge 有缓存就直接复用，省掉一次识别
     * @param probe   本次请求本身是不是预识别
     */
    static boolean sendVoice(byte[] pcm, int sampleRate, long id, long probeId, boolean probe) {
        BridgeService s = instance;
        if (s == null || pcm == null || pcm.length == 0 || !s.connectedNow) return false;
        try {
            JSONObject p = new JSONObject();
            p.put("type", "voice");
            p.put("id", id);
            p.put("rate", sampleRate);
            p.put("pcm", android.util.Base64.encodeToString(pcm, android.util.Base64.NO_WRAP));
            if (probeId != 0) p.put("probeId", probeId);
            if (probe) p.put("probe", true);
            return s.client.send(p);
        } catch (Exception e) {
            return false;
        }
    }

    /** 退出对话时清掉 Bridge 侧的对话上下文，下次重新开始 */
    static void resetChat() {
        BridgeService s = instance;
        if (s == null || !s.connectedNow) return;
        try {
            JSONObject p = new JSONObject();
            p.put("type", "chat_reset");
            s.client.send(p);
        } catch (Exception ignored) {
        }
    }

    /**
     * 对话中的人体目标：x/y 是画面归一化坐标（0~1），后面 ESP32-C3 云台按它转。
     * 没检测到人时 found=false，云台可以回中。
     */
    static boolean sendVision(boolean found, float x, float y, String labels, String face) {
        BridgeService s = instance;
        if (s == null || !s.connectedNow) return false;
        try {
            JSONObject p = new JSONObject();
            p.put("type", "vision");
            p.put("found", found);
            p.put("x", Math.round(x * 1000) / 1000.0);
            p.put("y", Math.round(y * 1000) / 1000.0);
            if (labels != null && !labels.isEmpty()) p.put("labels", labels);
            if (face != null && !face.isEmpty()) p.put("face", face);
            return s.client.send(p);
        } catch (Exception e) {
            return false;
        }
    }
}
