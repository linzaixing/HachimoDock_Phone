package com.example.hachimophone;

import android.app.Activity;
import android.content.res.AssetFileDescriptor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 外屏展示页，版式对齐 HachimoDock 设备端 UI：
 *
 *   左上角小号大写状态（Working · 执行中）
 *   中间是西高地小狗的视频（原素材，按状态切换片段）
 *   左下角是 Agent 名 + 一行状态文案
 *
 * 语音：常驻监听，喊「哈基米」进入连续对话；「语音输入」按钮把一句话直接发给电脑上的 Agent。
 *
 * 素材说明：mp4/wav 来自 HachimoDock 的 terrier-clips（非商业许可，
 * 见 assets/terrier/UPSTREAM-LICENSE.txt），仅本机自用。
 */
public final class DeviceActivity extends Activity implements AppState.Listener, ChatBus.Listener, VoiceBus.Listener {

    private static final String TAG = "HachimoPhone";
    private static final String DIR = "terrier/";
    /** 素材统一 854×480，按屏幕高度铺满后左右裁切，和原设备观感一致 */
    private static final float ASPECT = 854f / 480f;

    /** 唤醒词的各种听错写法：识别引擎对「哈基米」的常见误识也一并当唤醒 */
    private static final String[] WAKE_WORDS = {"哈基米", "哈吉米", "哈基咪", "哈机密", "哈姬米", "蛤基米", "哈吉咪", "哈集美", "哈吉美"};

    /** 更宽松的兜底：「阿基米 / 卡机米 / 大吉米 / 哈几米」都会命中这些片段 */
    private static final String[] WAKE_LOOSE = {"基米", "几米", "吉米", "机米", "姬米", "基咪"};

    /** 唤醒后的客套话（随机一句） */
    private static final String[] WARM_GREETINGS = {
            "我在呢主人，有啥吩咐？",
            "来啦主人，说吧～",
            "主人叫我？我在这儿呢！",
    };

    /** 用户主动让它退出时说的话（随机一句） */
    private static final String[] FAREWELLS = {
            "好嘞，我先趴会儿，想我了就喊哈基米",
            "那我先歇着啦，主人有事再叫我",
            "得令，我先回窝里啦",
    };

    /** 长时间没人理自动退出：和「主人主动赶我走」区分开 */
    private static final String[] IDLE_FAREWELLS = {
            "主人不理我，我就先回被窝啦，想我了喊哈基米",
            "没人陪我玩，那我先趴着睡一会儿咯",
            "那我就先歇着啦，主人忙完再喊我",
    };

    /** TTS 音调：龙泡泡本身已是童声，保持原声（1.0） */
    private static final float TTS_PITCH = 1.0f;
    /** 外屏喇叭很小：把 PCM 数字增益拉到 2 倍（配合把媒体音量顶到最大） */
    private static final float PLAY_GAIN = 2.0f;
    /** 进页面时媒体音量设成满档的多少：0.5 = 中档 */
    private static final float DEFAULT_VOLUME_RATIO = 0.5f;
    private static final java.util.Random RND = new java.util.Random();

    private static final String[] IDLE_CLIPS = {
            "idle.playing", "idle.wandering", "idle.begging",
            "idle.daydreaming", "idle.eating", "idle.reading", "idle.traveling",
    };
    private static final String[] WORK_CLIPS = {"working.thinking", "working.typing", "working.browsing"};
    private static final String[] TOOL_CLIPS = {"working.typing", "working.browsing"};
    private static final String[] TOUCH_CLIPS = {"touch.lick", "touch.what"};

    private FrameLayout stage;
    private android.view.TextureView texture;
    private android.view.Surface videoSurface;
    private TextView chip;
    private TextView agentTag;
    private TextView bubble;
    /** 时间右侧的小红点：摄像头开着时亮起 */
    private android.view.View camDot;
    private TextView hint;
    private TextView othersView;
    private TextView micButton;
    private LinearLayout actionRow;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** 端侧关键词检测放在后台线程，避免阻塞麦克风读取 */
    private final ExecutorService kwsExec = Executors.newSingleThreadExecutor();
    private MediaPlayer player;
    private MediaPlayer cue;
    private MediaPlayer replyPlayer;
    private android.media.AudioTrack chatTrack;
    private java.util.concurrent.ExecutorService audioWriter;
    private volatile long streamFrames;
    private int streamRate = 24000;
    private boolean streamEnded;
    private long streamEndedAt;
    /** 最近一次收到音频分片的时刻：用来判断「音频流已经断了，audio_end 可能丢了」 */
    private volatile long streamLastChunkAt;
    /** 开始播放的时刻：播放头可能跑得比实际快，用它兜住「还没播完就结束」 */
    private volatile long streamStartedAt;
    /** 播放头最近推进的帧数与时刻：用于识别 HAL 尾帧对齐停滞，避免播完后空等数秒 */
    private long lastPlayedHead;
    private long lastPlayedAdvanceAt;
    /** 还没写完的音频块数量：写完才算播完 */
    private java.util.concurrent.atomic.AtomicInteger pendingWrites =
            new java.util.concurrent.atomic.AtomicInteger();
    private boolean systemSpeaking;
    private boolean ttsPending;
    private volatile long ttsPendingAt;
    private long ttsSeq;
    private volatile boolean ttsReady;
    /** 最近一句回复：15 秒内优先显示，别被 Agent 状态刷新盖掉 */
    private String replyHold;
    private long replyHoldAt;
    private final Runnable streamDrainTask = this::checkStreamDrained;
    private TextToSpeech tts;
    private boolean surfaceReady;
    private boolean videoSized;
    private boolean playerPrepared;

    // ------------------------------------------------------------ 语音
    private final VoiceInput voice = new VoiceInput();
    /** PC 识别模式：手机只录音，文字由 PC 的语音引擎识别 */
    private final VoiceRecorder recorder = new VoiceRecorder();
    /** 常驻监听：本地 VAD 切句，唤醒词「哈基米」走识别文本匹配 */
    private final WakeListener wake = new WakeListener();
    private boolean wakeOn;
    /** 这批录音来自常驻监听，识别结果要过唤醒词判断 */
    private boolean wakePending;
    /** 上一段唤醒片段还在等识别时，新说的话先缓存 1 份，不直接丢弃 */
    private byte[] pendingWakePcm;
    private int pendingWakeMs;
    /** 端侧 KWS 已命中但后半句还有追问，识别回来后剥离前缀直接发对话 */
    private boolean kwsWakeFollowup;
    /** 这段录音是要发给 Agent 的指令（不是和小狗聊天） */
    private boolean agentVoice;
    /** 识别完、等用户点「发送」的 Agent 指令草稿 */
    private String draft;
    private long wakeSentAt;
    private long voiceId;
    /** 实时对话模式：说一句话 → Bridge（或手机云端）→ 回复 → 继续听 */
    private boolean chatMode;
    private boolean voiceBusy;
    private boolean chatWaiting;
    /** 已经把录音发出去、还没收到识别结果 */
    private boolean awaitingText;
    /** 当前 awaitingText 是否正在等 PC Bridge 返回 voice_text */
    private boolean waitingBridgeVoice;
    private boolean pendingMicRequest;
    private boolean pendingMicForAgent;
    private long chatId;
    private long chatSentAt;
    private long voiceSentAt;
    private Runnable chatIdleTask;
    private Runnable chatRestartTask;
    /** 看门狗：对话模式下任何一环丢了消息（识别没回、回复没回、麦没开），都能自己爬起来 */
    private final Runnable chatWatchdog = new Runnable() {
        @Override
        public void run() {
            if (!chatMode && !wakeOn) return;
            long now = System.currentTimeMillis();
            // 音频早该播完了（audio_end 丢了 / AudioTrack 异常）：两种模式都要强制收尾，
            // 否则会一直以为自己还在说话，麦克风再也不打开
            // 音频确实播完了，或者音频流 15 秒没有任何新数据（audio_end 已经丢了）才收尾。
            // 不能用「audio_end 之后 5 秒」这种判断：长回复最后一句可能还在播
            boolean streamDead = !streamEnded && now - streamLastChunkAt > 15000;
            if (chatTrack != null && !chatWaiting && (drainedNow() || streamDead)) {
                Log.w(TAG, "watchdog: 播放卡住，强制收尾");
                stopReply();
                onReplySpoken();
                handler.postDelayed(this, 2000);
                return;
            }
            if (!chatMode) {
                // 常驻监听：只负责把「等识别」这种卡死的状态解开，其余交给 VAD
                if (awaitingText && (wakePending ? now - wakeSentAt : now - voiceSentAt) > 35000) {
                    Log.w(TAG, "wake watchdog: 35s 没等到识别结果");
                    wakePending = false;
                    awaitingText = false;
                    waitingBridgeVoice = false;
                    wake.unmute();
                }
                if (!wake.isRunning() && !isReplyPlaying() && !awaitingText
                        && !voiceBusy && !recorder.isActive()) {
                    wake.start(wakeCallback);
                }
                handler.postDelayed(this, 2000);
                return;
            }
            if (ttsPending && now - ttsPendingAt > 20000) {
                Log.w(TAG, "watchdog: ttsPending 超时 20s，强制解锁");
                stopReply();
                onReplySpoken();
                handler.postDelayed(this, 2000);
                return;
            }
            if (chatWaiting && now - chatSentAt > 45000
                    && !(chatTrack != null && now - streamLastChunkAt < 10000)) {
                Log.w(TAG, "chat watchdog: 45s 没等到回复，重试");
                chatId++;
                stopReply();
                chatWaiting = false;
                awaitingText = false;
                waitingBridgeVoice = false;
                bubble.setText("刚才卡住了，再说一次");
                scheduleChatRestart(250);
            } else if (awaitingText && now - voiceSentAt > 35000) {
                Log.w(TAG, "chat watchdog: 35s 没等到识别结果，重试");
                awaitingText = false;
                waitingBridgeVoice = false;
                bubble.setText("识别没回来，再说一次");
                scheduleChatRestart(250);
            } else if (!voiceBusy && !awaitingText && !chatWaiting
                    && !recorder.isActive() && !isReplyPlaying()) {
                startVoice();
            }
            handler.postDelayed(this, 2000);
        }
    };

    /** 倒立检测：低通滤波后的重力 + 翻转去抖 */
    private android.hardware.SensorManager sensorManager;
    private android.hardware.SensorEventListener accelListener;
    private final float[] gravity = new float[3];
    private boolean flipped;
    private long lastFlipAt;
    /** 以启动时的握持方向为基准，翻转 180° 才倒立（不同折叠屏的外屏朝向不一样） */
    private Double baseAngle;

    private String state = "idle";
    /** 用户点过「确认」的时间：30 秒内不再顶着「点一下屏幕确认」 */
    private long waitingAckAt;
    /** 对话期间错过的「任务完成」：退出对话后补一次动画和叫声 */
    private boolean pendingDone;
    private String playing = "";
    private String wanted = "welcome";
    /** 页面是否在前台：在后台时不播提示音 / 视频 */
    private boolean resumed;
    /** welcome / touch / done / error 只播一遍，播完回到当前状态 */
    private boolean oneShot;
    private int idleIndex;
    private int workIndex;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setBackgroundDrawableResource(android.R.color.black);
        // 外屏扬声器音量偏小：进页面就把媒体音量顶到最大
        try {
            android.media.AudioManager am =
                    (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
            if (am != null) {
                // 默认音量调到中档（原来是拉满，太吵）；PCM 还有 2 倍数字增益兜底
                int max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
                am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC,
                        Math.max(1, Math.round(max * DEFAULT_VOLUME_RATIO)), 0);
            }
        } catch (Exception ignored) {
            /* 某些系统不允许直接改音量，忽略即可（还有 2 倍数字增益） */
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            // Android 6/7 没有这两个方法，用窗口 flag 顶上
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }

        stage = new FrameLayout(this);
        stage.setBackgroundColor(Color.BLACK);
        stage.setClipChildren(true);

        // 用 TextureView 而不是 SurfaceView：倒立时要把整个画面（含视频）一起转 180°
        texture = new android.view.TextureView(this);
        FrameLayout.LayoutParams videoLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        videoLp.gravity = Gravity.CENTER;
        stage.addView(texture, videoLp);
        texture.setSurfaceTextureListener(new android.view.TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture st, int width, int height) {
                videoSurface = new android.view.Surface(st);
                surfaceReady = true;
                playing = "";
                start(wanted);
            }

            @Override
            public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture st, int width, int height) {
            }

            @Override
            public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture st) {
                surfaceReady = false;
                releasePlayer();
                if (videoSurface != null) {
                    videoSurface.release();
                    videoSurface = null;
                }
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture st) {
            }
        });

        // 上下渐隐遮罩：小狗是白毛，文字压在画面上需要压暗才稳
        android.view.View topScrim = new android.view.View(this);
        topScrim.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xB3000000, 0x00000000}));
        stage.addView(topScrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(90), Gravity.TOP));

        android.view.View bottomScrim = new android.view.View(this);
        bottomScrim.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0xD9000000}));
        stage.addView(bottomScrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(230), Gravity.BOTTOM));

        LinearLayout overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setPadding(dp(16), dp(12), dp(16), dp(14));
        stage.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        chip = new TextView(this);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        chip.setTypeface(Typeface.DEFAULT_BOLD);
        chip.setLetterSpacing(0.12f);
        chip.setTextColor(0xE6FFFFFF);

        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        overlay.addView(topRow);

        // 左上角：系统时间 + 「摄像头开着」的小红点，右边是「语音输入」按钮
        LinearLayout leftBox = new LinearLayout(this);
        leftBox.setOrientation(LinearLayout.HORIZONTAL);
        leftBox.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.TextClock clock = new android.widget.TextClock(this);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        clock.setTypeface(Typeface.DEFAULT_BOLD);
        clock.setTextColor(0xF2FFFFFF);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("H:mm");
        leftBox.addView(clock, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        camDot = new android.view.View(this);
        android.graphics.drawable.GradientDrawable dotBg = new android.graphics.drawable.GradientDrawable();
        dotBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        dotBg.setColor(0xFFEF4444);
        camDot.setBackground(dotBg);
        camDot.setVisibility(android.view.View.GONE);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(8), dp(8));
        dotLp.leftMargin = dp(8);
        leftBox.addView(camDot, dotLp);

        FrameLayout rightBox = new FrameLayout(this);

        topRow.addView(leftBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        topRow.addView(rightBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        micButton = new TextView(this);
        micButton.setText("语音输入");
        micButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        micButton.setTypeface(Typeface.DEFAULT_BOLD);
        micButton.setTextColor(0xFF0F172A);
        micButton.setGravity(Gravity.CENTER);
        micButton.setPadding(dp(11), dp(6), dp(11), dp(6));
        android.graphics.drawable.GradientDrawable micBg = new android.graphics.drawable.GradientDrawable();
        micBg.setColor(0xFF67E8F9);
        micBg.setCornerRadius(dp(14));
        micButton.setBackground(micBg);
        micButton.setOnClickListener(v -> onMicTap());
        rightBox.addView(micButton, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END | Gravity.CENTER_VERTICAL));

        overlay.addView(new android.view.View(this), new LinearLayout.LayoutParams(0, 0, 1f));

        agentTag = new TextView(this);
        agentTag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        agentTag.setTypeface(Typeface.MONOSPACE);
        agentTag.setLetterSpacing(0.18f);
        agentTag.setTextColor(0xB3FFFFFF);
        overlay.addView(agentTag);

        bubble = new TextView(this);
        bubble.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        bubble.setTextColor(Color.WHITE);
        bubble.setMaxLines(2);
        bubble.setLineSpacing(dp(3), 1f);
        bubble.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams bubbleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bubbleLp.topMargin = dp(4);
        overlay.addView(bubble, bubbleLp);

        hint = new TextView(this);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hint.setTextColor(Color.parseColor("#67E8F9"));
        hint.setVisibility(TextView.GONE);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.topMargin = dp(4);
        overlay.addView(hint, hintLp);

        // Agent 指令草稿的「发送 / 取消」：识别完先确认，别自动发出去
        actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setVisibility(TextView.GONE);
        LinearLayout.LayoutParams actionLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionLp.topMargin = dp(8);
        overlay.addView(actionRow, actionLp);

        TextView sendBtn = pillButton("发送给 Agent", 0xFF67E8F9, 0xFF0F172A);
        sendBtn.setOnClickListener(v -> sendDraft());
        LinearLayout.LayoutParams sendLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        sendLp.rightMargin = dp(8);
        actionRow.addView(sendBtn, sendLp);

        TextView cancelBtn = pillButton("取消", 0x33FFFFFF, 0xFFFFFFFF);
        cancelBtn.setOnClickListener(v -> clearDraft());
        actionRow.addView(cancelBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        othersView = new TextView(this);
        othersView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        othersView.setTextColor(Color.parseColor("#94A3B8"));
        othersView.setMaxLines(1);
        othersView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        othersView.setVisibility(TextView.GONE);
        LinearLayout.LayoutParams othersLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        othersLp.topMargin = dp(4);
        overlay.addView(othersView, othersLp);

        stage.setOnClickListener(v -> onTap());
        stage.setOnLongClickListener(v -> toggleSfx());
        setContentView(stage);
        stage.post(this::hideSystemBars);
        stage.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (videoSized || stage.getHeight() <= 0) return;
            videoSized = true;
            int h = stage.getHeight();
            int w = Math.round(h * ASPECT);
            texture.setLayoutParams(new FrameLayout.LayoutParams(w, h, Gravity.CENTER));
        });

        AppState.get().add(this);
        ChatBus.add(this);
        VoiceBus.add(this);
        initTts();
        // 调试用：--ez kws_self true 用本机 TTS 合成「哈基米」再跑端侧 KWS，隔离麦克风问题
        if (getIntent() != null && getIntent().getBooleanExtra("kws_self", false)) {
            String t = getIntent().getStringExtra("kws_text");
            final String txt = t == null || t.isEmpty() ? "哈基米" : t;
            handler.postDelayed(() -> runKwsSelfTest(txt), 1500);
        }
        // 调试用：--ez kws_replay true 用上次保存的真实唤醒音频复跑 KWS
        if (getIntent() != null && getIntent().getBooleanExtra("kws_replay", false)) {
            handler.postDelayed(this::runKwsReplay, 300);
        }
        // 调试用：adb ... --ez force_flip true 强制倒立渲染（同时关掉传感器，方便截图核对）
        boolean forceFlip = getIntent() != null && getIntent().getBooleanExtra("force_flip", false);
        if (forceFlip) {
            flipped = true;
            stage.post(() -> stage.setRotation(180f));
        } else {
            registerOrientation();
        }
        render(AppState.get().snapshot());
        cue("sfx_welcome");
        // 调试用：adb ... --ez vision_test true 不进对话也开摄像头，方便单独验证姿态跟踪
        if (getIntent() != null && getIntent().getBooleanExtra("vision_test", false)) {
            forceVision = true;
            stage.postDelayed(this::startVision, 800);
        }
        // 调试用：adb ... --ez servo_test true 不开摄像头，直接来回扫水平舵机，验证接线/供电
        if (getIntent() != null && getIntent().getBooleanExtra("servo_test", false)) {
            startServoTest();
        }
        // 调试用：adb ... --ez doa_test true 强制开声源方向估计（双 mic 互相关）
        if (getIntent() != null && getIntent().getBooleanExtra("doa_test", false)) {
            forceDoa = true;
            doaProbeSrc = getIntent().getIntExtra("doa_src", -1);
            doaProbeRate = getIntent().getIntExtra("doa_rate", 0);
        }
        // 调试用：adb ... --es say "你好" 直接朗读一句，验证 TTS 音色
        String sayNow = getIntent() == null ? null : getIntent().getStringExtra("say");
        if (sayNow != null && !sayNow.isEmpty()) {
            stage.postDelayed(() -> speakCloud(sayNow), 1500);
        }
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // 调试入口：已经在前台时再 --ez vision_test true 也能开摄像头
        if (intent != null && intent.getBooleanExtra("vision_test", false)) {
            forceVision = true;
            startVision();
        }
        // 调试入口：已经在前台时再 --ez servo_test true 也能开始扫舵机
        if (intent != null && intent.getBooleanExtra("servo_test", false)) {
            startServoTest();
        }
        // 调试入口：已经在前台时再 --ez doa_test true 也能开声源方向估计
        if (intent != null && intent.getBooleanExtra("doa_test", false)) {
            forceDoa = true;
            doaProbeSrc = intent.getIntExtra("doa_src", -1);
            doaProbeRate = intent.getIntExtra("doa_rate", 0);
            stopDoa();
            startDoa();
        }
        // 调试入口：已经在前台时再 --ez kws_self true 也能跑端侧 KWS 自检
        if (intent != null && intent.getBooleanExtra("kws_self", false)) {
            String kt = intent.getStringExtra("kws_text");
            final String ktxt = kt == null || kt.isEmpty() ? "哈基米" : kt;
            handler.postDelayed(() -> runKwsSelfTest(ktxt), 300);
        }
        if (intent != null && intent.getBooleanExtra("kws_replay", false)) {
            handler.postDelayed(this::runKwsReplay, 300);
        }
        // 调试入口：已经在前台时再 --es say "你好" 也能直接朗读
        String say = intent == null ? null : intent.getStringExtra("say");
        if (say != null && !say.isEmpty()) {
            handler.postDelayed(() -> speakCloud(say), 300);
        }
    }

    /**
     * 倒立跟随：用重力在屏幕平面的投影判断「屏幕是否被倒过来拿」，
     * 倒过来就把整块内容（含视频）转 180°，转回来再转回去。
     */
    private void registerOrientation() {
        sensorManager = (android.hardware.SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager == null) return;
        // 优先用系统融合后的重力传感器，比裸加速度计少抖动
        android.hardware.Sensor gravitySensor =
                sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_GRAVITY);
        android.hardware.Sensor sensor = gravitySensor != null ? gravitySensor
                : sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER);
        if (sensor == null) return;
        accelListener = new android.hardware.SensorEventListener() {
            private Boolean pendingFlip;
            private long pendingAt;
            private int lastRotation = -1;

            @Override
            public void onSensorChanged(android.hardware.SensorEvent e) {
                for (int i = 0; i < 3; i++) {
                    gravity[i] = gravity[i] * 0.85f + e.values[i] * 0.15f;
                }
                float planar = (float) Math.hypot(gravity[0], gravity[1]);
                if (planar < 4f) return;                 // 平放时不做判断

                android.view.Display disp = currentDisplay();
                int rot = disp == null ? android.view.Surface.ROTATION_0
                        : disp.getRotation();
                if (rot != lastRotation) {
                    // 显示方向变了（例如外屏被系统重置），旧基准作废
                    lastRotation = rot;
                    baseAngle = null;
                    pendingFlip = null;
                }

                boolean want;
                float up = 0;
                String how;
                if (rot == android.view.Surface.ROTATION_0
                        || rot == android.view.Surface.ROTATION_180) {
                    // 竖屏自然方向：用重力在屏幕「上方向」的投影做绝对判断，
                    // 不再依赖启动时的握姿，避免开机时正好倒着拿就永久颠倒
                    up = rot == android.view.Surface.ROTATION_0 ? gravity[1] : -gravity[1];
                    if (Math.abs(up) < 3f) return;       // 接近横放，保持当前状态
                    want = up < 0;
                    how = "abs";
                } else {
                    // 横屏自然方向的机型：退回「以启动姿态为基准」
                    double angle = Math.toDegrees(Math.atan2(gravity[0], -gravity[1]));
                    if (baseAngle == null) {
                        baseAngle = angle;
                        return;
                    }
                    double delta = angle - baseAngle;
                    while (delta > 180) delta -= 360;
                    while (delta < -180) delta += 360;
                    if (Math.abs(delta) < 45) return;
                    want = Math.abs(delta) > 135;
                    how = "rel";
                }

                long now = System.currentTimeMillis();
                if (pendingFlip == null || pendingFlip != want) {
                    pendingFlip = want;
                    pendingAt = now;
                    return;
                }
                // 连续 600ms 判断一致才翻转，防止转动过程中来回抖
                if (want != flipped && now - pendingAt >= 600 && now - lastFlipAt > 1000) {
                    flipped = want;
                    lastFlipAt = now;
                    Log.i(TAG, "flip " + (flipped ? 180 : 0) + " " + how
                            + " rot=" + rot + " up=" + Math.round(up));
                    stage.animate().rotation(flipped ? 180f : 0f).setDuration(220).start();
                    // 倒过来以后人体在画面里的方向也反了，云台坐标要跟着换
                    if (vision != null) vision.setFlipped(flipped);
                }
            }

            @Override
            public void onAccuracyChanged(android.hardware.Sensor sensor, int accuracy) {
            }
        };
        sensorManager.registerListener(accelListener, sensor,
                android.hardware.SensorManager.SENSOR_DELAY_NORMAL);
    }

    private static byte[] readFileBytes(java.io.File file) throws java.io.IOException {
        try (java.io.FileInputStream in = new java.io.FileInputStream(file);
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream((int) file.length())) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    /** 调试：本机 TTS 合成「哈基米」→ 16k PCM → 端侧 KWS，用来区分「模型不认识」还是「麦克风太小声」 */
    private void runKwsSelfTest(String say) {
        LocalTts.speak(this, say, new LocalTts.Callback() {
            @Override
            public void onFile(java.io.File file) {
                new Thread(() -> {
                    try {
                        byte[] wav = readFileBytes(file);
                        if (wav.length < 44) return;
                        int rate = (wav[24] & 0xff) | ((wav[25] & 0xff) << 8)
                                | ((wav[26] & 0xff) << 16) | ((wav[27] & 0xff) << 24);
                        if (rate <= 0) rate = 16000;
                        byte[] pcm = java.util.Arrays.copyOfRange(wav, 44, wav.length);
                        if (rate != 16000) pcm = resample16k(pcm, rate);
                        String hit = KwsWake.detect(pcm);
                        Log.i(TAG, "kws self test text=" + say + " rate=" + rate + " ms=" + (pcm.length / 32)
                                + " hit=" + hit);
                    } catch (Throwable e) {
                        Log.w(TAG, "kws self test failed", e);
                    }
                }, "kws-self-test").start();
            }

            @Override
            public void onError(String message) {
                Log.w(TAG, "kws self test tts: " + message);
            }
        }, msg -> {
        });
    }

    /** 调试：用上次真实唤醒音频复跑 KWS，避免每次都要人再喊一遍 */
    private void runKwsReplay() {
        new Thread(() -> {
            try {
                byte[] pcm = readFileBytes(new java.io.File(getFilesDir(), "last_wake.pcm"));
                Log.i(TAG, "kws replay ms=" + (pcm.length / 32));
                String hit = KwsWake.detect(pcm);
                Log.i(TAG, "kws replay hit=" + hit);
            } catch (Throwable e) {
                Log.w(TAG, "kws replay failed", e);
            }
        }, "kws-replay").start();
    }

    private static byte[] resample16k(byte[] pcm, int rate) {
        int n = pcm.length / 2;
        int m = (int) ((long) n * 16000 / rate);
        byte[] out = new byte[m * 2];
        for (int i = 0; i < m; i++) {
            double src = i * (double) rate / 16000;
            int i0 = (int) src;
            int i1 = Math.min(i0 + 1, n - 1);
            float f = (float) (src - i0);
            short s0 = (short) ((pcm[2 * i0] & 0xff) | (pcm[2 * i0 + 1] << 8));
            short s1 = (short) ((pcm[2 * i1] & 0xff) | (pcm[2 * i1 + 1] << 8));
            short v = (short) (s0 + (s1 - s0) * f);
            out[2 * i] = (byte) (v & 0xff);
            out[2 * i + 1] = (byte) (v >> 8);
        }
        return out;
    }

    /** 外屏一般是 748×720 这种小尺寸，系统栏必须藏掉 */
    private void hideSystemBars() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.view.WindowInsetsController c = getWindow().getInsetsController();
                if (c != null) {
                    c.hide(android.view.WindowInsets.Type.statusBars()
                            | android.view.WindowInsets.Type.navigationBars());
                    c.setSystemBarsBehavior(
                            android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } else {
                getWindow().getDecorView().setSystemUiVisibility(
                        android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                | android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                                | android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            }
        } catch (Exception ignored) {
            // decor 未挂载时会 NPE，等 onWindowFocusChanged 再来
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @Override
    protected void onDestroy() {
        AppState.get().remove(this);
        ChatBus.remove(this);
        VoiceBus.remove(this);
        voice.cancel();
        recorder.cancel();
        stopWake();
        stopDoa();
        stopVisionForGood();
        if (faceMemory != null) {
            faceMemory.close();
            faceMemory = null;
        }
        if (servo != null) {
            servo.stop();
            servo = null;
        }
        stopReply();
        if (tts != null) {
            try {
                tts.shutdown();
            } catch (Exception ignored) {
            }
            tts = null;
        }
        if (sensorManager != null && accelListener != null) {
            sensorManager.unregisterListener(accelListener);
            accelListener = null;
        }
        handler.removeCallbacksAndMessages(null);
        kwsExec.shutdownNow();
        releaseCue();
        releasePlayer();
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.i(TAG, "onPause chat=" + chatMode + " vision=" + (vision != null));
        resumed = false;
        voice.cancel();
        recorder.cancel();
        voiceBusy = false;
        awaitingText = false;
        waitingBridgeVoice = false;
        wakePending = false;
        kwsWakeFollowup = false;
        pendingWakePcm = null;
        if (checkSelfPermission(android.Manifest.permission.CAMERA)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendingVisionQuestion = null;
        }
        agentVoice = false;
        draft = null;
        actionRow.setVisibility(TextView.GONE);
        stopWake();
        handler.removeCallbacks(servoRetry);
        stopDoa();
        handler.removeCallbacks(stopVisionTask);
        handler.postDelayed(stopVisionTask, 1200);
        stopReply();
        // 回到后台就停掉声音和视频，避免锁屏/切走后还在响
        releaseCue();
        if (player != null && playerPrepared && player.isPlaying()) player.pause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.i(TAG, "onResume chat=" + chatMode + " visionWanted=" + visionWanted);
        resumed = true;
        if (player != null && playerPrepared && !player.isPlaying()) player.start();
        if (pendingMicRequest
                && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            boolean forAgent = pendingMicForAgent;
            pendingMicRequest = false;
            pendingMicForAgent = false;
            startVoice(forAgent);
            return;
        }
        // 对话模式从后台回来继续听；否则回到「哈基米」常驻监听
        if (chatMode) startVoice();
        else startWake();
        // 摄像头在 onPause 里会延迟释放，回到前台按需重开（对话中/调试测试）
        handler.removeCallbacks(stopVisionTask);
        if (visionWanted) startVision();
        // 云台是独立外设：不管摄像头开没开，都试着连一下
        handler.removeCallbacks(servoRetry);
        handler.postDelayed(servoRetry, 500);
        // 声源方向跟踪（默认关，调试页里可开）
        startDoa();
        // 本机离线音色：首次在后台把模型下下来
        if (PhoneVoice.TTS_MODE_LOCAL.equals(PhoneVoice.ttsMode(this))) {
            LocalTts.preload(this, msg -> handler.post(() -> {
                if (bubble != null) bubble.setText(msg);
            }));
        }
    }

    // ------------------------------------------------------------ 状态映射

    @Override
    public void onState(AppState.State s) {
        boolean stateChanged = !s.state.equals(state);
        state = s.state == null || s.state.isEmpty() ? "idle" : s.state;
        if (!s.connected && awaitingText && waitingBridgeVoice) {
            awaitingText = false;
            waitingBridgeVoice = false;
            if (wakePending) {
                wakePending = false;
                if (wakeOn && !chatMode && !wake.isRunning()) wake.start(wakeCallback);
                wake.unmute();
            } else {
                bubble.setText("识别失败：Bridge 已断开");
                voiceFailed();
            }
        }
        render(s);
        Log.i(TAG, "state " + state + " changed=" + stateChanged + " resumed=" + resumed
                + " chat=" + chatMode);
        // Activity 还在栈里但已经不可见（按了 Home / 合盖）时只更新文案，不播媒体
        if (!resumed || !stateChanged) return;
        // 对话中不打断；但「任务完成」记下来，退出对话时补一次汪汪叫 + 完成动画
        if (chatMode) {
            if ("done".equals(state)) pendingDone = true;
            return;
        }
        switch (state) {
            case "waiting":
                cue("waiting_user");
                playLoop("waiting_user");
                break;
            case "done":
                pendingDone = false;
                cue("sfx_done", true);      // 汪汪叫不跟随「音效开关」，任务完成一定要出声
                playOnce("done");
                break;
            case "error":
                cue("error");
                playOnce("error");
                break;
            case "thinking":
            case "tool":
                cue("thinking".equals(state) ? "sfx_thinking" : "sfx_tool");
                playLoop(nextWorkClip());
                break;
            default:
                playLoop(nextIdleClip());
        }
    }

    private void render(AppState.State s) {
        if (chatMode || draft != null) return;   // 语音文案优先，别被状态刷新覆盖
        bubble.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        bubble.setMaxLines(2);
        String st = s.state == null || s.state.isEmpty() ? "idle" : s.state;
        // 每次状态更新都带上 agent 名字，多 agent 并行时不会混淆
        chip.setText(agentName(s.agent) + " · " + chinese(st));
        // 会话行前面带上最近在用的 agent 名字
        agentTag.setText(agentName(s.agent) + " · " + sessionText(s));
        bubble.setText(s.label == null || s.label.isEmpty() ? defaultLabel(st) : s.label);
        // 刚点过「我知道了」的 30 秒内不再催确认，避免点完又弹回来
        boolean waiting = "waiting".equals(st)
                && System.currentTimeMillis() - waitingAckAt > 30000;
        hint.setVisibility(waiting ? TextView.VISIBLE : TextView.GONE);
        hint.setText("点一下屏幕发送确认");
        String others = othersLine();
        othersView.setText(others);
        othersView.setVisibility(others.isEmpty() ? TextView.GONE : TextView.VISIBLE);
        if (!s.connected) {
            // 断线优先显示，别让上一句回复盖住「没连上」这件事
            bubble.setText("PC Bridge 未连接…");
            hint.setVisibility(TextView.VISIBLE);
            hint.setText("电脑端运行：node bridge/src/server.mjs");
            return;
        }
        // 任务结束后 agent 的回复一直留着，直到下一轮任务开始才让位给状态文案
        boolean newTask = "thinking".equals(st) || "tool".equals(st)
                || "waiting".equals(st) || "error".equals(st);
        if (replyHold != null) {
            if (newTask) {
                replyHold = null;
            } else {
                applyReplyStyle(replyHold);
                bubble.setText(replyHold);
            }
        }
    }

    /** 把一句话固定显示在气泡里（保留到下一轮任务开始） */
    private void holdReply(String text) {
        if (text == null || text.isEmpty()) return;
        // 保留换行便于阅读，只把多余的空格压掉
        String one = text.replace("\r\n", "\n").replaceAll("[ \\t]+", " ").trim();
        // 同一条回复可能被 Stop hook 和命令行 stdout 各推一次，别重复刷
        if (one.equals(replyHold) && System.currentTimeMillis() - replyHoldAt < 30000) return;
        replyHold = one;
        replyHoldAt = System.currentTimeMillis();
        Log.i(TAG, "hold reply len=" + one.length());
        applyReplyStyle(one);
        bubble.setText(one);
        hint.setVisibility(TextView.GONE);
    }

    /** 回复越长字号越小、行数越多：任务结束时 agent 的回复尽量整段显示出来 */
    private void applyReplyStyle(String text) {
        int len = text == null ? 0 : text.length();
        int sp;
        int lines;
        if (len <= 40) {
            sp = 19;
            lines = 4;
        } else if (len <= 160) {
            sp = 17;
            lines = 9;
        } else if (len <= 400) {
            sp = 15;
            lines = 16;
        } else {
            sp = 14;
            lines = 24;
        }
        bubble.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        bubble.setMaxLines(lines);
        bubble.setEllipsize(android.text.TextUtils.TruncateAt.END);
    }

    /** 同时还在跑的其他 agent，最多列两个 */
    private String othersLine() {
        java.util.List<AppState.State> rest = AppState.get().others();
        if (rest.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("同时：");
        int n = Math.min(2, rest.size());
        for (int i = 0; i < n; i++) {
            AppState.State o = rest.get(i);
            if (i > 0) sb.append(" · ");
            sb.append(agentName(o.agent)).append(' ').append(chinese(o.state));
        }
        if (rest.size() > n) sb.append(" …");
        return sb.toString();
    }

    private void onTap() {
        if (chatMode || draft != null) return;   // 语音交互期间别触发逗狗
        if ("waiting".equals(state)) {
            // 只是把「我知道了」这张信号发回 PC，真正的权限批准仍要在 PC 上完成
            BridgeService.confirmCurrent(AppState.get().snapshot().sessionId);
            waitingAckAt = System.currentTimeMillis();
            bubble.setText("已发送确认信号");
            hint.setVisibility(TextView.GONE);
            playOnce("done");
            return;
        }
        cue("sfx_touch");
        playOnce(TOUCH_CLIPS[(idleIndex + workIndex) % TOUCH_CLIPS.length]);
    }

    /** 长按外屏切换音效开关（外屏没有设置页，长按最快） */
    private boolean toggleSfx() {
        boolean on = !BridgeService.sfxEnabled(this);
        BridgeService.setSfxEnabled(this, on);
        hint.setVisibility(TextView.VISIBLE);
        hint.setText(on ? "音效已开启" : "音效已关闭");
        handler.postDelayed(() -> {
            if (!"waiting".equals(state)) hint.setVisibility(TextView.GONE);
            else hint.setText("点一下屏幕确认");
        }, 1500);
        return true;
    }

    // ------------------------------------------------------------ 语音输入（给 Agent）/ 常驻对话

    /**
     * 「语音输入」按钮：录一句直接当指令发给电脑上的 Claude Agent。
     * 没连电脑时明确提示，不再假装发送。
     */
    private void onMicTap() {
        if (draft != null) return;           // 等用户点「发送 / 取消」
        if (chatMode) exitChat();            // 正在和小狗聊，先退出再下发指令
        if (voiceBusy) {
            recorder.stop();                 // 说完了：提前结束
            return;
        }
        if (awaitingText) return;            // 上一句还在识别
        if (!AppState.get().snapshot().connected) {
            bubble.setText("Agent 未连接");
            hint.setVisibility(TextView.VISIBLE);
            hint.setText("先启动电脑端 Bridge，再点语音输入");
            wake.unmute();
            return;
        }
        // agentVoice 由 startVoice(true) 在通过所有守卫后再置位，
        // 免得这里提前置 true、结果这一轮没录上，下一句闲聊被误发给 Agent
        startVoice(true);
    }

    private void startVoice() {
        startVoice(false);
    }

    /** 录音 + 识别；识别结果按当前用途分流（对话 / 唤醒词 / Agent 指令） */
    private void startVoice(boolean forAgent) {
        // awaitingText：上一段录音已经发出去、还在等识别结果，这时再开麦会把 voiceId 顶掉，
        // 回来的结果被当成「迟到结果」丢掉，于是一轮都接不上
        if (voiceBusy || awaitingText || !resumed) return;
        // 手动录音期间常驻监听必须闭嘴：两边同时切片段会互相顶掉 voiceId，
        // 结果就是「一直显示识别中」
        wake.mute();
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendingMicRequest = true;
            pendingMicForAgent = forAgent;
            bubble.setText("需要麦克风权限");
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 9);
            return;
        }
        agentVoice = forAgent;
        // 对话模式下 leadSilenceMs 设为 16s（略长于 15s 空闲退出的阈值），避免每隔 6 秒重启麦克风出现短暂「聋窗」
        // 最多录 15s / 30s：环境吵时 VAD 可能一直判不出「说完」，不设上限会录满 45s，
        // 上传 1.4MB 音频把 WS 撑断，表现就是「问了没反应」
        // 说完 0.9 秒没声音就收尾：比 1.4 秒更快给出回复，又不会把句中停顿切断
        recorder.configure(forAgent ? 2000 : 900, forAgent ? 8000 : 16000,
                forAgent ? 30000 : 15000);
        startPcVoice();
    }

    /** 本机识别失败后的收尾：对话模式下立即重新听，减少「聋窗」 */
    private void voiceFailed() {
        agentVoice = false;
        if (chatMode) {
            hint.setVisibility(TextView.VISIBLE);
            hint.setText("说「退出」结束");
            scheduleChatRestart(250);
        } else {
            hint.setVisibility(TextView.GONE);
            if (wakeOn) {
                if (!wake.isRunning()) wake.start(wakeCallback);
                wake.unmute();
                hint.setVisibility(TextView.VISIBLE);
                hint.setText(wakeHint());
            }
        }
    }

    /** PC 识别：录一段 16k PCM 发给 Bridge，文字回来后再发草稿或继续对话 */
    private void startPcVoice() {
        if (voiceBusy || awaitingText || !resumed) return;
        pendingWakePcm = null;
        // 先释放常驻监听的 AudioRecord 再开 VoiceRecorder，避免部分机型双开 VOICE_RECOGNITION 录出全零
        if (wake.isRunning()) wake.stop();
        else wake.mute();
        voiceBusy = true;
        bubble.setText("我在听…");
        hint.setVisibility(TextView.VISIBLE);
        hint.setText(chatMode ? "说「退出」结束" : "说完停顿一下就结束");
        final long vId = ++voiceId;
        boolean ok = recorder.start(new VoiceRecorder.Listener() {
            @Override
            public void onProbe(byte[] pcm) {
                // 省流模式默认关闭预识别；开启时才在停顿 0.3s 提前送去识别
                if (!PhoneVoice.probeEnabled(DeviceActivity.this)) return;
                if (!AppState.get().snapshot().connected) return;
                BridgeService.sendVoice(pcm, 16000, -vId, 0, true);
            }

            @Override
            public void onDone(byte[] pcm, boolean probeUsable) {
                voiceBusy = false;
                voiceSentAt = System.currentTimeMillis();
                // 没连电脑：手机自己调云端识别，识别完照常走 onVoiceText 的分支
                if (!AppState.get().snapshot().connected) {
                    if (!PhoneVoice.asrReady(DeviceActivity.this)) {
                        bubble.setText("没连电脑：请在设置页填识别 Key");
                        voiceFailed();
                        return;
                    }
                    awaitingText = true;
                    waitingBridgeVoice = false;
                    bubble.setText("识别中…");
                    hint.setVisibility(TextView.GONE);
                    Log.i(TAG, "voice done(cloud) id=" + vId + " bytes=" + pcm.length);
                    PhoneVoice.recognize(DeviceActivity.this, pcm, 16000,
                            new PhoneVoice.TextCallback() {
                                @Override
                                public void onText(String text) {
                                    onVoiceText(new VoiceBus.Result(vId, true, text, ""));
                                }

                                @Override
                                public void onError(String message) {
                                    onVoiceText(new VoiceBus.Result(vId, false, "", message));
                                }
                            });
                    return;
                }
                boolean sent = BridgeService.sendVoice(pcm, 16000, vId, probeUsable ? -vId : 0, false);
                Log.i(TAG, "voice done id=" + vId + " bytes=" + pcm.length
                        + " probe=" + probeUsable + " sent=" + sent);
                if (!sent) {
                    bubble.setText("发送失败：Bridge 未连接");
                    voiceFailed();
                    return;
                }
                awaitingText = true;
                waitingBridgeVoice = true;
                bubble.setText("识别中…");
                hint.setVisibility(TextView.GONE);
            }

            @Override
            public void onError(String message) {
                voiceBusy = false;
                awaitingText = false;
                waitingBridgeVoice = false;
                // 对话模式下没人说话是常态，别一直弹「没听清」
                if (chatMode && "没听清，再说一次".equals(message)) {
                    bubble.setText("我在听…");
                    scheduleChatRestart(600);
                    return;
                }
                bubble.setText(message);
                voiceFailed();
            }
        });
        if (!ok) {
            voiceBusy = false;
            bubble.setText("麦克风打开失败");
            voiceFailed();
        }
    }

    // ------------------------------------------------------------ 常驻监听（唤醒词「哈基米」）

    /** 空调声、键盘声在本地就被 VAD 挡掉，只有像人说话的一段才会走到这里 */
    private final WakeListener.Callback wakeCallback = new WakeListener.Callback() {
        @Override
        public void onSegment(byte[] pcm, int durationMs) {
            onWakeSegment(pcm, durationMs);
        }

        @Override
        public void onError(String message) {
            Log.w(TAG, "wake mic: " + message);
            wake.stop();
            handler.postDelayed(() -> {
                if (wakeOn && !chatMode) wake.start(wakeCallback);
            }, 3000);
        }
    };

    /** 外屏页可见时开启常驻监听 */
    private void startWake() {
        if (!resumed || chatMode) return;
        wakeOn = true;
        wakePending = false;
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            hint.setVisibility(TextView.VISIBLE);
            hint.setText("需要麦克风权限");
            return;
        }
        wake.start(wakeCallback);
        KwsWake.preload(this);
        if (cue == null) wake.unmute();
        else wake.mute();
        hint.setVisibility(TextView.VISIBLE);
        hint.setText(wakeHint());
        handler.removeCallbacks(chatWatchdog);
        handler.postDelayed(chatWatchdog, 3000);
    }

    private void stopWake() {
        wakeOn = false;
        wakePending = false;
        pendingWakePcm = null;
        wake.stop();
    }

    // ------------------------------------------------------------ 视觉（只在对话中开）

    /** 对话期间的人体跟踪：本地姿态 + 图像标签，坐标推给 Bridge（以后给舵机用） */
    private VisionTracker vision;
    /** 本地人脸记忆（模型首次使用时后台下载） */
    private FaceMemory faceMemory;
    /** ESP32-C3 云台（Type-C OTG 串口），没插就不发 */
    private SerialServo servo;
    private Runnable visionPushTask;
    /** 是否希望开着摄像头：页面短暂 onPause（锁屏/切前台）后回到前台要能自动恢复 */
    private boolean visionWanted;
    /** 这次相机是为「看一眼」临时打开的：取到帧答完就关，不在对话里常开 */
    private boolean visionOnDemand;
    /** 申请相机权限期间暂存的看图问题，授权后自动续上 */
    private String pendingVisionQuestion;
    /** 调试用：忽略「摄像头默认关」的开关，强制开 */
    private boolean forceVision;
    /** 调试用：屏幕右下角的相机预览小窗 */
    private androidx.camera.view.PreviewView camPreview;
    private android.widget.FrameLayout camPreviewBox;

    // ------------------------------------------------------------ 声源方向（双 mic DOA）

    /** 声源方向估计：默认关，调试页打开「声源跟踪」后随页面启停 */
    private DoaTracker doa;
    private boolean forceDoa;
    private Runnable doaServoTask;
    /** 调试参数：指定音源/采样率，-1 表示自动尝试 */
    private int doaProbeSrc = -1;
    private int doaProbeRate;
    /** 方向 → 云台水平的符号：实机试出来反了就把这里改成 1 */
    private static final float DOA_PAN_SIGN = -1f;

    private void startDoa() {
        // 只有调试入口会开：本机系统层给的是处理过的双声道，测不出时差，暂不对外提供开关
        if (!resumed || !forceDoa) return;
        ensureServo();
        if (doa == null) {
            doa = new DoaTracker(this);
            if (doaProbeSrc > 0) doa.setProbe(doaProbeSrc, doaProbeRate);
        }
        if (!doa.start()) {
            doa = null;
            return;
        }
        if (doaServoTask == null) {
            doaServoTask = new Runnable() {
                @Override
                public void run() {
                    DoaTracker d = doa;
                    // 摄像头在跟人时以视觉为准；回复播放期间别让云台跟着自己的喇叭转
                    if (d != null && vision == null && d.valid() && !isReplyPlaying()) {
                        float sin = (float) Math.sin(Math.toRadians(d.azimuth()));
                        float x = 0.5f - DOA_PAN_SIGN * 0.45f * sin;
                        if (x < 0.05f) x = 0.05f;
                        if (x > 0.95f) x = 0.95f;
                        servo.update(true, x, 0.5f);
                    }
                    handler.postDelayed(this, 200);
                }
            };
        }
        handler.removeCallbacks(doaServoTask);
        handler.postDelayed(doaServoTask, 300);
    }

    private void stopDoa() {
        if (doaServoTask != null) handler.removeCallbacks(doaServoTask);
        if (doa != null) {
            doa.stop();
            doa = null;
        }
    }

    @SuppressWarnings("deprecation")
    private android.view.Display currentDisplay() {
        return android.os.Build.VERSION.SDK_INT >= 30
                ? getDisplay()
                : getWindowManager().getDefaultDisplay();
    }

    /** 副屏/外屏打开时用后置（外置）摄像头；主屏/普通手机打开时用前置自拍摄像头（兼容 API 24~36） */
    private boolean useFrontCamera() {
        android.view.Display d = currentDisplay();
        return d == null || d.getDisplayId() == android.view.Display.DEFAULT_DISPLAY;
    }

    private void startVision() {
        // 摄像头默认关：只有「看一眼」临时拉起的视觉或调试页手动打开才启动
        if (!forceVision && !visionOnDemand && !BridgeService.cameraEnabled(this)) {
            visionWanted = false;
            return;
        }
        visionWanted = true;
        if (vision != null || !resumed) return;
        if (checkSelfPermission(android.Manifest.permission.CAMERA)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "vision: 还没有相机权限，先申请");
            requestPermissions(new String[]{android.Manifest.permission.CAMERA}, 10);
            return;
        }
        try {
            boolean mlkit = BridgeService.mlkitEnabled(this);
            if (mlkit && faceMemory == null) faceMemory = new FaceMemory(this);
            ensureServo();
            // 临时看图不显示预览小窗，避免对话里突然冒出一块画面
            androidx.camera.view.PreviewView pv = visionOnDemand ? null : ensurePreview();
            vision = new VisionTracker(mlkit ? faceMemory : null, mlkit);
            vision.setFlipped(flipped);
            vision.start(this, useFrontCamera(), pv);
            if (camDot != null) camDot.setVisibility(android.view.View.VISIBLE);
        } catch (Exception e) {
            Log.e(TAG, "vision start failed", e);
            vision = null;
            return;
        }
        if (visionPushTask == null) {
            visionPushTask = new Runnable() {
                @Override
                public void run() {
                    VisionTracker v = vision;
                    if (v == null) return;
                    VisionTracker.Target t = v.target();
                    BridgeService.sendVision(t.found, t.x, t.y, v.labels(), v.faceName());
                    if (servo != null) servo.update(t.found, t.x, t.y);
                    handler.postDelayed(this, 300);
                }
            };
        }
        handler.removeCallbacks(visionPushTask);
        handler.postDelayed(visionPushTask, 300);
    }

    private void stopVision() {
        if (visionPushTask != null) handler.removeCallbacks(visionPushTask);
        if (vision != null) {
            vision.stop();
            vision = null;
        }
        if (camPreviewBox != null) {
            stage.removeView(camPreviewBox);
            camPreviewBox = null;
            camPreview = null;
        }
        if (camDot != null) camDot.setVisibility(android.view.View.GONE);
    }

    /** 调试预览：右下角 132×99dp 的小窗，确认相机实际看到的画面/朝向 */
    private androidx.camera.view.PreviewView ensurePreview() {
        if (camPreview != null) return camPreview;
        camPreviewBox = new android.widget.FrameLayout(this);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp(8));
        bg.setColor(0xCC000000);
        bg.setStroke(dp(1), 0x66FFFFFF);
        camPreviewBox.setBackground(bg);
        int pad = dp(2);
        camPreviewBox.setPadding(pad, pad, pad, pad);
        camPreview = new androidx.camera.view.PreviewView(this);
        camPreview.setScaleType(androidx.camera.view.PreviewView.ScaleType.FILL_CENTER);
        camPreviewBox.addView(camPreview, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        android.widget.FrameLayout.LayoutParams lp =
                new android.widget.FrameLayout.LayoutParams(dp(132), dp(99));
        lp.gravity = Gravity.BOTTOM | Gravity.END;
        lp.rightMargin = dp(10);
        lp.bottomMargin = dp(10);
        stage.addView(camPreviewBox, lp);
        return camPreview;
    }

    /** 真正不要再看了（退出对话）：把「希望开着」也清掉，回前台不自动重开 */
    private void stopVisionForGood() {
        visionWanted = false;
        visionOnDemand = false;
        handler.removeCallbacks(stopVisionTask);
        stopVision();
    }

    /** onPause 里延迟 1.2 秒再关摄像头：折叠屏偶尔会瞬间 pause→resume，别每次都重开相机 */
    private final Runnable stopVisionTask = this::stopVision;

    /** ESP32 云台和摄像头解耦：页面在前台就每 5 秒试一次，插拔都能自愈 */
    private void ensureServo() {
        if (servo == null) servo = new SerialServo(this);
        servo.start();
    }

    private final Runnable servoRetry = new Runnable() {
        @Override
        public void run() {
            if (!resumed) return;
            ensureServo();
            handler.postDelayed(this, 5000);
        }
    };

    /** 调试用：不给视觉输入，水平/俯仰轮流 0→1000 来回扫，确认舵机接线/供电/固件正常 */
    private Runnable servoTestTask;

    private void startServoTest() {
        if (servoTestTask != null) return;
        final long t0 = System.currentTimeMillis();
        servoTestTask = new Runnable() {
            @Override
            public void run() {
                if (!resumed) {
                    handler.postDelayed(this, 500);
                    return;
                }
                ensureServo();
                long t = (System.currentTimeMillis() - t0) % 12000;
                float x = 0.5f;
                float y = 0.5f;
                if (t < 6000) {                        // 前 6 秒扫水平（GPIO4）
                    float phase = t / 6000f;
                    x = phase < 0.5f ? phase * 2f : (1f - phase) * 2f;
                } else {                               // 后 6 秒扫俯仰（GPIO5）
                    float phase = (t - 6000) / 6000f;
                    y = phase < 0.5f ? phase * 2f : (1f - phase) * 2f;
                }
                servo.update(true, x, y);
                Log.i(TAG, "servo test x=" + Math.round(x * 1000)
                        + " y=" + Math.round(y * 1000)
                        + (servo != null && servo.isOpen() ? "" : " (串口未连接)"));
                handler.postDelayed(this, 300);
            }
        };
        handler.post(servoTestTask);
    }

    private String wakeHint() {
        boolean kws = KwsWake.ready();
        int mode = BridgeService.wakeMode(this);
        boolean ready = AppState.get().snapshot().connected || PhoneVoice.asrReady(this);
        if (kws && mode == BridgeService.WAKE_MODE_STRICT) {
            return "端侧离线唤醒已开启，叫「哈基米」就能聊";
        }
        return (ready || kws ? "叫「哈基米」就能对话" : "请先在设置页填免费识别 Key")
                + "（右上角「语音输入」发给 Agent）";
    }

    /** VAD 切出的一段人声 */
    private void onWakeSegment(byte[] pcm, int durationMs) {
        if (!wakeOn || chatMode || isReplyPlaying() || !resumed
                || wake.isMuted() || cue != null) return;
        // 手动录音正在进行：这段人声属于那一次，别再抢 voiceId
        if (recorder.isActive() || voiceBusy) return;
        if (awaitingText) {
            // 上一句唤醒词还在识别时，先把刚说出来的下一句暂存，避免「叫完哈基米紧接着说的话」被丢掉
            if (wakePending) {
                pendingWakePcm = pcm;
                pendingWakeMs = durationMs;
            }
            return;
        }
        // 端侧 KWS 先听（离线、免录词）；命中直接唤醒，没命中再交给云端识别兜底
        dispatchWake(pcm, durationMs);
    }

    /** 本地 KWS 已经识别出唤醒词时，根据活跃语音跨度判断是否紧跟着后半句提问 */
    private static int activeSpeechMs(byte[] pcm) {
        if (pcm == null || pcm.length < 640) return 0;
        final int frame = 320; // 20ms @ 16k
        int totalFrames = (pcm.length / 2) / frame;
        int first = -1;
        int last = -1;
        for (int f = 0; f < totalFrames; f++) {
            double sum = 0;
            int off = f * frame;
            for (int i = 0; i < frame; i++) {
                short s = (short) ((pcm[(off + i) * 2] & 0xff) | (pcm[(off + i) * 2 + 1] << 8));
                sum += (double) s * s;
            }
            if (Math.sqrt(sum / frame) > 240) {
                if (first < 0) first = f;
                last = f;
            }
        }
        return first < 0 ? 0 : (last - first + 1) * 20;
    }

    private void dispatchWake(byte[] pcm, int durationMs) {
        // 同一时刻只处理一段人声：播放回复、正在识别、正在录音时都不再起新的识别
        if (!wakeOn || chatMode || isReplyPlaying() || !resumed
                || wake.isMuted() || cue != null) return;
        if (recorder.isActive() || voiceBusy || awaitingText) return;
        int mode = BridgeService.wakeMode(this);
        // 付费方案下即使用户选过全量兜底，也走智能分级防止长闲聊白烧付费 ASR
        if (PhoneVoice.planPaid(this) && mode == BridgeService.WAKE_MODE_ASR) {
            mode = BridgeService.WAKE_MODE_SMART;
        }
        final int effectiveMode = mode;
        final int activeMs = activeSpeechMs(pcm);
        if (!KwsWake.ready()) {
            if (effectiveMode == BridgeService.WAKE_MODE_STRICT) return;
            if (effectiveMode == BridgeService.WAKE_MODE_SMART
                    && (activeMs < 300 || activeMs > 4000)) {
                Log.i(TAG, "kws not ready, smart drop activeMs=" + activeMs);
                return;
            }
            if (activeMs < 300) return;
            dispatchWakeToAsr(pcm, durationMs);
            return;
        }
        kwsExec.execute(() -> {
            final String hit = KwsWake.detect(pcm);
            handler.post(() -> {
                if (!wakeOn || chatMode || isReplyPlaying() || !resumed
                        || wake.isMuted() || cue != null) return;
                if (recorder.isActive() || voiceBusy || awaitingText) return;
                if (hit != null) {
                    Log.i(TAG, "kws hit: " + hit + " activeMs=" + activeMs);
                    if (activeMs > 1050 && (AppState.get().snapshot().connected || PhoneVoice.asrReady(this))) {
                        // 一口气说了「哈基米 + 问题」：直接进对话并把整段送 ASR 提取后半句，不吞提问
                        enterVoiceChat(true);
                        kwsWakeFollowup = true;
                        sendWakeFollowup(pcm);
                    } else {
                        onWakeText("哈基米");
                    }
                    return;
                }
                if (effectiveMode == BridgeService.WAKE_MODE_STRICT) {
                    Log.i(TAG, "kws miss, strict local mode drop");
                } else if (effectiveMode == BridgeService.WAKE_MODE_SMART) {
                    // 折中分级：像唤醒词长度的短句（220~1450ms）送 ASR 复核防漏检；过短杂音或长段闲聊本地丢弃
                    if (activeMs >= 300 && activeMs <= 4000) {
                        Log.i(TAG, "kws miss short=" + activeMs + "ms, smart fallback asr");
                        dispatchWakeToAsr(pcm, durationMs);
                    } else {
                        Log.i(TAG, "kws miss activeMs=" + activeMs + "ms, smart drop");
                    }
                } else {
                    // 免费方案激进全兜底：只要不是 <180ms 的瞬时咔嗒声，未命中 KWS 也全部送免费 ASR 保唤醒率
                    if (activeMs < 320) {
                        Log.i(TAG, "kws miss click=" + activeMs + "ms, drop");
                    } else {
                        Log.i(TAG, "kws miss, full fallback asr activeMs=" + activeMs);
                        dispatchWakeToAsr(pcm, durationMs);
                    }
                }
            });
        });
    }

    private void dispatchWakeToAsr(byte[] pcm, int durationMs) {
        // 调试：只保存音量足够大（像近场人声）的片段，避免被环境噪声反复覆盖
        int peak = 0;
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            int v = Math.abs((short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8)));
            if (v > peak) peak = v;
        }
        if (peak > 32768 * 15 / 100) {
            try (java.io.FileOutputStream fo =
                         new java.io.FileOutputStream(new java.io.File(getFilesDir(), "last_wake.pcm"))) {
                fo.write(pcm);
            } catch (Exception ignored) {
            }
        }
        final long vId = ++voiceId;
        wakePending = true;
        awaitingText = true;
        wakeSentAt = System.currentTimeMillis();
        Log.i(TAG, "wake segment " + durationMs + "ms id=" + vId);
        if (AppState.get().snapshot().connected
                && BridgeService.sendVoice(pcm, 16000, vId, 0, false)) {
            waitingBridgeVoice = true;
            return;
        }
        waitingBridgeVoice = false;
        if (!PhoneVoice.asrReady(this)) {
            wakePending = false;
            awaitingText = false;
            bubble.setText("没连电脑：请在设置页填识别 Key");
            hint.setText("叫「哈基米」开始对话");
            return;
        }
        PhoneVoice.recognize(this, pcm, 16000, new PhoneVoice.TextCallback() {
            @Override
            public void onText(String text) {
                onVoiceText(new VoiceBus.Result(vId, true, text, ""));
            }

            @Override
            public void onError(String message) {
                onVoiceText(new VoiceBus.Result(vId, false, "", message));
            }
        });
    }

    /** 把刚刚在唤醒识别期间说出来的追问直接送进对话识别 */
    private void sendWakeFollowup(byte[] pcm) {
        final long vId = ++voiceId;
        voiceSentAt = System.currentTimeMillis();
        awaitingText = true;
        bubble.setText("识别中…");
        hint.setVisibility(TextView.GONE);
        if (AppState.get().snapshot().connected
                && BridgeService.sendVoice(pcm, 16000, vId, 0, false)) {
            waitingBridgeVoice = true;
            return;
        }
        waitingBridgeVoice = false;
        if (!PhoneVoice.asrReady(this)) {
            awaitingText = false;
            voiceFailed();
            return;
        }
        PhoneVoice.recognize(this, pcm, 16000, new PhoneVoice.TextCallback() {
            @Override
            public void onText(String text) {
                onVoiceText(new VoiceBus.Result(vId, true, text, ""));
            }

            @Override
            public void onError(String message) {
                onVoiceText(new VoiceBus.Result(vId, false, "", message));
            }
        });
    }

    /** 识别文本里出现唤醒词：返回 true 表示命中唤醒词并已进入对话 */
    private boolean onWakeText(String text) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) return false;
        int[] hit = locateWake(t);
        if (hit != null) {
            String rest = t.substring(hit[1])
                    .replaceAll("^[\\s，。！？、,.!?~…：:；;]+", "").trim();
            byte[] queued = pendingWakePcm;
            pendingWakePcm = null;
            Log.i(TAG, "wake hit rest=" + rest + " queued=" + (queued != null) + " from=" + t);
            // 唤醒词=进入连续对话：后面的内容直接当第一句；若紧接着又说了一句则直接送去识别，不再让用户重说
            boolean hasQuestion = rest.length() >= 2 || queued != null;
            enterVoiceChat(hasQuestion);
            if (rest.length() >= 2) {
                bubble.setText("「" + rest + "」");
                sendChat(rest);
            } else if (queued != null) {
                sendWakeFollowup(queued);
            } else {
                String greet = WARM_GREETINGS[RND.nextInt(WARM_GREETINGS.length)];
                bubble.setText(greet);
                speakGreeting(greet);
            }
            return true;
        }
        // 没叫名字的闲聊当杂音，别乱答
        Log.i(TAG, "wake ignore: " + t);
        if (!isReplyPlaying()) hint.setText(wakeHint());
        return false;
    }

    /**
     * 找唤醒词在文本里的范围 [起, 止)。
     * 识别对「哈基米」的误识特别多（哈吉尼 / 阿基米 / 卡机米 / 哈集美 / 大吉米 / hakimi），
     * 按精确写法 → 宽松片段 → 拼音/拉丁 → 同音三字拆字四级兜底。
     */
    private int[] locateWake(String t) {
        for (String w : WAKE_WORDS) {
            int i = t.indexOf(w);
            if (i >= 0) return new int[]{i, i + w.length()};
        }
        for (String w : WAKE_LOOSE) {
            int from = 0;
            while (true) {
                int i = t.indexOf(w, from);
                if (i < 0) break;
                if (looseWakeAllowed(t, i, w.length())) return new int[]{i, i + w.length()};
                from = i + 1;
            }
        }
        java.util.regex.Matcher latin = java.util.regex.Pattern
                .compile("(?i)(?:ha\\s*ji\\s*mi|ha\\s*ki\\s*mi|hajimi|hakimi)").matcher(t);
        if (latin.find()) return new int[]{latin.start(), latin.end()};
        // 「哈金」「哈吉尼」「阿基米」「卡机米」「他吉美」这类拆字误识：首字 + 腭音字 + 可选尾字
        for (int i = 0; i < t.length(); i++) {
            if (!isWakeHead(t.charAt(i))) continue;
            int end = Math.min(t.length(), i + 3);
            int second = -1;
            for (int j = i + 1; j < end; j++) {
                if (isWakeMid(t.charAt(j))) {
                    second = j;
                    break;
                }
            }
            if (second < 0) continue;
            int stop = second + 1;
            boolean hasTail = stop < t.length() && isWakeTail(t.charAt(stop));
            if (hasTail) {
                stop++;
            } else if (t.charAt(i) != '哈' && t.charAt(i) != '蛤') {
                // 非「哈/蛤」开头的两字组合（他急、大约、嘿几…）太容易误撞，必须带尾字
                continue;
            }
            return new int[]{i, stop};
        }
        return null;
    }

    /**
     * 宽松片段（「基米 / 几米 / 吉米」）容易误唤醒，例如「几米长」：
     * 只有前面紧跟唤醒首字（「阿基米」），或整句基本就是这个片段时才认。
     */
    private static boolean looseWakeAllowed(String t, int start, int len) {
        if (start > 0 && isWakeHead(t.charAt(start - 1))) return true;
        for (int i = 0; i < start; i++) {
            char c = t.charAt(i);
            if (!isFiller(c) && !isPunct(c)) return false;
        }
        int after = start + len;
        if (after >= t.length()) return true;
        char c = t.charAt(after);
        return isPunct(c) || isFiller(c);
    }

    private static boolean isFiller(char c) {
        return c == '喂' || c == '嗯' || c == '哎' || c == '诶' || c == '呃'
                || c == '哦' || c == '额' || c == '啊' || c == '呀';
    }

    private static boolean isPunct(char c) {
        return "，。！？、,.!?~…：:；; ".indexOf(c) >= 0;
    }

    private static boolean isWakeHead(char c) {
        return c == '哈' || c == '蛤' || c == '阿' || c == '啊' || c == '卡'
                || c == '他' || c == '大' || c == '发' || c == '八' || c == '吧'
                || c == '嘿' || c == '嗨';
    }

    private static boolean isWakeMid(char c) {
        return c == '基' || c == '吉' || c == '机' || c == '几' || c == '金'
                || c == '奇' || c == '肌' || c == '姬' || c == '积' || c == '叽'
                || c == '集' || c == '记' || c == '及' || c == '急' || c == '技'
                || c == '纪' || c == '极' || c == '继' || c == '鸡' || c == '挤'
                || c == '济' || c == '寄' || c == '寂';
    }

    private static boolean isWakeTail(char c) {
        return c == '米' || c == '尼' || c == '咪' || c == '迷' || c == '密'
                || c == '泥' || c == '美' || c == '蜜' || c == '秘' || c == '弥'
                || c == '梅';
    }

    /** 语音回复只留第一句、最多 40 字，避免 TTS 念一长串 */
    private static String shortReply(String text) {
        if (text == null) return "";
        String t = text.trim().replaceAll("\\s+", " ");
        if (t.length() <= 40) return t;
        for (int i = 0; i < t.length() && i < 45; i++) {
            char c = t.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '!' || c == '?') {
                return t.substring(0, i + 1);
            }
        }
        return t.substring(0, 40) + "…";
    }

    /**
     * 本地直通视觉意图：仅对无歧义的强视觉指向立即开相机（省一次 LLM 往返）；
     * 诸如「帮我看看天气」「看看这个东西」等模糊表述交给对话模型做语义判断（返回 [CAM] 再开相机）。
     */
    private static boolean hasDirectVisualIntent(String text) {
        if (text == null || text.isEmpty()) return false;
        String[] strong = {"看看我", "看一眼我", "看一下我", "看下我", "手势", "我比了",
                "能看到我", "看见我", "面前这个", "手里拿", "桌上的", "镜头里"};
        for (String k : strong) {
            if (text.contains(k)) return true;
        }
        return false;
    }

    /** 取当前帧交给视觉模型，回答直接朗读出来 */
    private void askVision(String text) {
        VisionTracker v = vision;
        byte[] jpeg = v == null ? null : v.latestJpeg();
        if (jpeg != null) {
            describeFrame(jpeg, text);
            return;
        }
        // 平时摄像头是关的：只有问「看看…」这类问题时才临时打开，取一帧就够
        if (vision == null) {
            pendingVisionQuestion = text;
            visionOnDemand = true;
            startVision();
        }
        if (vision == null) {
            // 正在弹系统相机权限窗，等用户点允许后由 onRequestPermissionsResult 续上
            if (checkSelfPermission(android.Manifest.permission.CAMERA)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                bubble.setText("请允许相机权限…");
                return;
            }
            pendingVisionQuestion = null;
            bubble.setText("相机打不开，看不了");
            wake.unmute();
            scheduleChatRestart(600);
            return;
        }
        pendingVisionQuestion = null;
        bubble.setText("我看一眼…");
        waitVisionFrame(text, 0);
    }

    /** 等相机出第一帧，最多约 2 秒 */
    private void waitVisionFrame(String text, int tries) {
        if (!chatMode) return;
        VisionTracker v = vision;
        byte[] jpeg = v == null ? null : v.latestJpeg();
        if (jpeg != null) {
            describeFrame(jpeg, text);
            return;
        }
        if (tries >= 35) {
            Log.w(TAG, "vision frame timeout, jpeg="
                    + (v == null || v.latestJpeg() == null ? 0 : v.latestJpeg().length));
            finishSnapshotVision();
            bubble.setText("画面没准备好，再说一次");
            wake.unmute();
            scheduleChatRestart(1500);
            return;
        }
        handler.postDelayed(() -> waitVisionFrame(text, tries + 1), 100);
    }

    private void describeFrame(byte[] jpeg, String text) {
        chatWaiting = true;
        chatSentAt = System.currentTimeMillis();
        Log.i(TAG, "describeFrame jpeg=" + jpeg.length + "B flipped=" + flipped + " q=" + text);
        // 调试：把这一帧留在应用私有目录，便于核对方向/内容
        try (java.io.FileOutputStream fo =
                     new java.io.FileOutputStream(new java.io.File(getFilesDir(), "last_vlm.jpg"))) {
            fo.write(jpeg);
        } catch (Exception ignored) {
        }
        bubble.setText("看一眼…");
        hint.setVisibility(TextView.VISIBLE);
        hint.setText("看图…");
        PhoneVoice.describeImage(this, jpeg, text, new PhoneVoice.TextCallback() {
            @Override
            public void onText(String desc) {
                chatWaiting = false;
                String shortText = shortReply(desc);
                bubble.setText(shortText);
                finishSnapshotVision();
                speakCloud(shortText);
            }

            @Override
            public void onError(String message) {
                chatWaiting = false;
                bubble.setText(message);
                hint.setText(chatMode ? "说「退出」结束" : wakeHint());
                finishSnapshotVision();
                wake.unmute();
                scheduleChatRestart(1500);
            }
        });
    }

    /** 只为看图打开的相机，答完就关；本来手动开着的相机不受影响 */
    private void finishSnapshotVision() {
        if (!visionOnDemand) return;
        stopVisionForGood();
    }

    /** 没连电脑时，手机直接问云端模型（支持语义判断是否返回 [CAM] 调相机） */
    private void cloudChat(String text) {
        if (!PhoneVoice.chatReady(this)) {
            chatWaiting = false;
            bubble.setText("没连电脑：请在设置页填对话 Key");
            hint.setVisibility(TextView.VISIBLE);
            hint.setText(chatMode ? "说「退出」结束" : wakeHint());
            scheduleChatRestart(1500);
            return;
        }
        chatWaiting = true;
        chatSentAt = System.currentTimeMillis();
        bubble.setText("「" + text + "」");
        hint.setVisibility(TextView.VISIBLE);
        hint.setText("想一想…");
        Log.i(TAG, "cloud chat: " + text);
        boolean allowCam = PhoneVoice.vlmReady(this) && PhoneVoice.looksLikeLookRequest(text);
        PhoneVoice.chat(this, text, allowCam, new PhoneVoice.TextCallback() {
            @Override
            public void onText(String reply) {
                chatWaiting = false;
                if (allowCam && PhoneVoice.isCameraMarker(reply)) {
                    Log.i(TAG, "semantic camera trigger for: " + text);
                    askVision(text);
                    return;
                }
                hint.setVisibility(chatMode ? TextView.VISIBLE : TextView.GONE);
                if (chatMode) hint.setText("说「退出」结束");
                String shortText = shortReply(reply);
                bubble.setText(shortText);
                speakCloud(shortText);
            }

            @Override
            public void onError(String message) {
                chatWaiting = false;
                bubble.setText(message);
                hint.setVisibility(TextView.VISIBLE);
                hint.setText(chatMode ? "说「退出」结束" : wakeHint());
                wake.unmute();
                scheduleChatRestart(1500);
            }
        });
    }

    @Override
    public void onVoiceText(VoiceBus.Result r) {
        if (r.id != voiceId) {
            // 预识别（负 id）和上一轮的迟到结果都走这里，别打断当前这一轮
            Log.i(TAG, "voice_text stale id=" + r.id + " cur=" + voiceId);
            return;
        }
        awaitingText = false;
        waitingBridgeVoice = false;
        Log.i(TAG, "voice_text id=" + r.id + " ok=" + r.ok + " text=" + r.text);
        if (wakePending) {
            wakePending = false;
            boolean hit = r.ok && onWakeText(r.text);
            if (!hit) {
                if (!r.ok) {
                    // 常驻监听里识别失败很常见（咳嗽、关门声），安静回去继续听
                    Log.i(TAG, "wake asr miss: " + r.error);
                    if (!chatMode && !isReplyPlaying()) hint.setText(wakeHint());
                }
                if (pendingWakePcm != null && wakeOn && !chatMode) {
                    byte[] next = pendingWakePcm;
                    int nextMs = pendingWakeMs;
                    pendingWakePcm = null;
                    onWakeSegment(next, nextMs);
                }
            }
            return;
        }
        if (!r.ok) {
            bubble.setText("识别失败：" + (r.error.isEmpty() ? "没听清" : r.error));
            voiceFailed();
            return;
        }
        String text = r.text == null ? "" : r.text.trim();
        if (kwsWakeFollowup) {
            kwsWakeFollowup = false;
            int[] hit = locateWake(text);
            if (hit != null) {
                text = text.substring(hit[1])
                        .replaceAll("^[\\s，。！？、,.!?~…：:；;]+", "").trim();
            } else {
                // 端侧已命中唤醒词但 ASR 略去或微偏首词时，若前 5 字后跟逗号则裁掉前缀
                text = text.replaceFirst("^.{1,5}[，,、\\s]+", "").trim();
            }
            if (text.length() < 2) {
                String greet = WARM_GREETINGS[RND.nextInt(WARM_GREETINGS.length)];
                bubble.setText(greet);
                speakGreeting(greet);
                return;
            }
        }
        if (text.isEmpty()) {
            // 空字符串发过去 Bridge 会直接拒掉，这一轮就白问了：自己补一句提示并重新听
            bubble.setText("没听清，再说一次");
            voiceFailed();
            return;
        }
        if (agentVoice) {
            agentVoice = false;
            showDraft(text);
            return;
        }
        if (chatMode) sendChat(text);
        else voiceFailed();
    }

    /** 识别出的指令先给用户确认，避免话说一半就被发出去 */
    private void showDraft(String text) {
        draft = text;
        bubble.setText(text);
        hint.setVisibility(TextView.VISIBLE);
        hint.setText("确认后发给 Agent");
        othersView.setVisibility(TextView.GONE);
        actionRow.setVisibility(TextView.VISIBLE);
    }

    private void sendDraft() {
        if (draft == null) return;
        String text = draft;
        draft = null;
        actionRow.setVisibility(TextView.GONE);
        hint.setVisibility(TextView.GONE);
        sendAgentPrompt(text);
    }

    private void clearDraft() {
        draft = null;
        actionRow.setVisibility(TextView.GONE);
        hint.setVisibility(TextView.VISIBLE);
        hint.setText(wakeHint());
        bubble.setText("已取消");
        if (wakeOn && !chatMode && !wake.isRunning()) wake.start(wakeCallback);
        wake.unmute();
    }

    /** 语音输入的内容直接下发给电脑上的 Claude Agent */
    private void sendAgentPrompt(String text) {
        if (!AppState.get().snapshot().connected) {
            bubble.setText("Agent 未连接");
            hint.setVisibility(TextView.VISIBLE);
            hint.setText("先启动电脑端 Bridge，再点语音输入");
            if (wakeOn && !chatMode && !wake.isRunning()) wake.start(wakeCallback);
            wake.unmute();
            return;
        }
        Log.i(TAG, "agent prompt: " + text);
        if (!BridgeService.sendPrompt(text)) {
            bubble.setText("Agent 未连接：指令没发出去");
            hint.setVisibility(TextView.VISIBLE);
            hint.setText("检查电脑端 Bridge 是否在跑");
            if (wakeOn && !chatMode && !wake.isRunning()) wake.start(wakeCallback);
            wake.unmute();
            return;
        }
        holdReply("「" + text + "」已发给 Claude");
        hint.setVisibility(TextView.VISIBLE);
        hint.setText("等 Claude 回复…");
        // 指令已经发出去，麦克风要立刻回到常驻监听（以前这里漏了 unmute，之后喊「哈基米」全都没反应）
        if (wakeOn && !chatMode && !wake.isRunning()) wake.start(wakeCallback);
        wake.unmute();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean granted = grantResults.length > 0
                && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
        if (requestCode == 10) {
            String q = pendingVisionQuestion;
            pendingVisionQuestion = null;
            if (!granted) {
                visionOnDemand = false;
                visionWanted = false;
                bubble.setText("需要相机权限才能看图");
                if (chatMode) scheduleChatRestart(600);
                return;
            }
            handler.post(() -> {
                startVision();
                if (q != null && vision != null && chatMode) {
                    bubble.setText("我看一眼…");
                    waitVisionFrame(q, 0);
                }
            });
            return;
        }
        if (requestCode != 9) return;
        if (!granted) {
            pendingMicRequest = false;
            pendingMicForAgent = false;
            wake.unmute();
            return;
        }
        if (resumed) {
            boolean forAgent = pendingMicForAgent;
            pendingMicRequest = false;
            pendingMicForAgent = false;
            startVoice(forAgent);
        }
    }

    // ------------------------------------------------------------ 常驻对话（唤醒词进入）

    /** 唤醒词命中后进入连续对话：不再需要每句都喊「哈基米」 */
    private void enterVoiceChat(boolean withCue) {
        if (chatMode) return;
        stopWake();                  // 常驻监听和对话录音不能同时开麦
        chatMode = true;
        chip.setText("HACHIMO · 对话");
        agentTag.setText(agentName(AppState.get().snapshot().agent) + " · VOICE CHAT");
        hint.setVisibility(TextView.VISIBLE);
        hint.setText("说「退出」结束");
        othersView.setVisibility(TextView.GONE);
        BridgeService.resetChat();
        PhoneVoice.resetChat();
        if (withCue) cue("sfx_welcome");
        armChatIdle();
        handler.removeCallbacks(chatWatchdog);
        handler.postDelayed(chatWatchdog, 2000);
    }

    private void speakGreeting(String greet) {
        // 本机离线音色 / OpenAI TTS 模式时直接由手机发声，避免与对话回复音色分裂
        String mode = PhoneVoice.ttsMode(this);
        if (!PhoneVoice.TTS_MODE_LOCAL.equals(mode)
                && !PhoneVoice.ttsReady(this)
                && AppState.get().snapshot().connected) {
            chatId++;
            chatWaiting = true;
            chatSentAt = System.currentTimeMillis();
            streamFrames = 0;
            holdReply(greet);
            if (BridgeService.sendSay(greet, chatId)) return;
            chatWaiting = false;
        }
        speakCloud(greet);
    }

    private void exitChat() {
        if (!chatMode) return;
        chatMode = false;
        chatWaiting = false;
        awaitingText = false;
        agentVoice = false;
        draft = null;
        actionRow.setVisibility(TextView.GONE);
        voice.cancel();
        recorder.cancel();
        voiceBusy = false;
        handler.removeCallbacks(chatWatchdog);
        stopReply();
        if (chatIdleTask != null) handler.removeCallbacks(chatIdleTask);
        if (chatRestartTask != null) handler.removeCallbacks(chatRestartTask);
        BridgeService.resetChat();
        PhoneVoice.resetChat();
        stopVisionForGood();
        bubble.setText("对话结束");
        hint.setVisibility(TextView.GONE);
        render(AppState.get().snapshot());
        startWake();                // 退出对话后回到「哈基米」常驻监听
        if (pendingDone) {
            pendingDone = false;
            cue("sfx_done", true);
            playOnce("done");
        }
    }

    /** 用户口头结束对话的短句（长句里带这些词不算，避免误判） */
    private static final String[] EXIT_WORDS = {
            "退出", "退下", "下去", "回去", "结束", "不聊了", "别聊了", "不说了", "先这样",
            "再见", "拜拜", "晚安", "睡吧", "去睡", "休息", "歇着", "歇会", "走吧",
            "没事了", "暂停", "停一下", "闭嘴", "安静", "下线", "散会", "就这样",
    };

    /** 「记住他叫小明」这类介绍：本地建档，不调用大模型，也就没有额外费用 */
    private static final java.util.regex.Pattern REMEMBER_NAME = java.util.regex.Pattern.compile(
            "(?:记住|记一下|认一下|认识一下)[^，。,.!?！？]{0,8}?(?:叫|名字是|名字叫|是)\\s*([\\u4e00-\\u9fa5A-Za-z]{1,6})");
    private static final String[] NAME_BLOCK = {"什么", "啥", "谁", "外卖", "车", "我", "你", "他", "她", "它"};

    private boolean tryRememberFace(String text) {
        java.util.regex.Matcher m = REMEMBER_NAME.matcher(text);
        if (!m.find() || faceMemory == null) return false;
        String name = m.group(1).trim();
        for (String bad : NAME_BLOCK) {
            if (name.equals(bad)) return false;
        }
        String say;
        if (!faceMemory.isReady()) {
            say = "我还在学认人，等我一会儿再说";
        } else if (faceMemory.lastEmbedding() == null) {
            say = "没看清脸，凑近一点再说一次";
        } else if (faceMemory.remember(name, faceMemory.lastEmbedding())) {
            say = "记住啦，" + name + "，下回我一眼就认出来";
        } else {
            return false;
        }
        Log.i(TAG, "face remember: " + name);
        holdReply(say);
        wake.mute();
        speakCloud(say);
        return true;
    }

    private boolean isExitPhrase(String text) {
        String t = text.replaceAll("[\\s，。！？、,.!?~…：:；;]", "");
        for (String w : EXIT_WORDS) {
            if (t.equals(w)) return true;          // 「退出」「再见」
        }
        if (text.matches(".*[?？].*")) return false;   // 问句（「什么时候结束？」）不算
        if (t.length() > 10) return false;
        for (String w : EXIT_WORDS) {
            if (t.contains(w)) return true;        // 「你退下吧」「好啦你去休息吧」
        }
        return false;
    }

    private void sendChat(String text) {
        if (text == null || text.trim().isEmpty()) {
            voiceFailed();
            return;
        }
        text = text.trim();
        // 强视觉直通（节省一次 LLM 往返）；模糊请求（如「帮我看看…」）交给对话模型语义判断 [CAM]
        if (PhoneVoice.vlmReady(this) && hasDirectVisualIntent(text)) {
            askVision(text);
            return;
        }
        // 「记住他叫小明」这类介绍：直接把当前这张脸存进本地名册，不走大模型（省钱、离线）
        if (tryRememberFace(text)) return;
        if (isExitPhrase(text)) {
            Log.i(TAG, "exit phrase: " + text);
            exitChat();
            // 离开前先打声招呼
            String bye = FAREWELLS[RND.nextInt(FAREWELLS.length)];
            bubble.setText(bye);
            wake.mute();
            speakCloud(bye);
            return;
        }
        chatId++;
        chatWaiting = true;
        chatSentAt = System.currentTimeMillis();
        streamFrames = 0;
        replyHold = null;
        Log.i(TAG, "send chat id=" + chatId + " text=" + text);
        // 没连电脑：手机自己调云端模型，回答用系统 TTS 念
        if (!AppState.get().snapshot().connected) {
            cloudChat(text);
            return;
        }
        bubble.setText("「" + text + "」");
        hint.setVisibility(TextView.VISIBLE);
        hint.setText("想一想…");
        if (!BridgeService.sendChat(text, chatId)) {
            chatWaiting = false;
            bubble.setText("发送失败：Bridge 未连接");
            cloudChat(text);
            return;
        }
        armChatIdle();
    }

    @Override
    public void onChatReply(ChatBus.Reply r) {
        if (r.id == -2) {                 // Agent 的回复（主动下发 / 电脑上直接跑的都显示）
            Log.i(TAG, "prompt_reply: " + r.text);
            holdReply(r.text);
            wake.unmute();
            return;
        }
        // 对话回复只在对话会话里收（唤醒词/语音输入发起的都是别的通道）
        if (!chatMode) return;
        if (r.id != -1 && r.id != chatId) return;   // 上一轮的迟到回复
        switch (r.kind) {
            case "text":
                // 文字先到：先显示，音频边合成边播
                hint.setVisibility(TextView.GONE);
                bubble.setText(r.text);
                return;
            case "audio_start":
                startAudioStream(r.sampleRate);
                return;
            case "audio_chunk":
                pushAudioStream(r.pcm);
                return;
            case "audio_end":
                endAudioStream();
                return;
            case "need_camera":
                chatWaiting = false;
                Log.i(TAG, "bridge semantic camera trigger for: " + r.text);
                if (PhoneVoice.vlmReady(this)) askVision(r.text);
                else cloudChat(r.text);
                return;
            default:
                break;
        }
        chatWaiting = false;
        hint.setVisibility(TextView.GONE);
        Log.i(TAG, "chat_reply id=" + r.id + " ok=" + r.ok + " streamed=" + r.streamed
                + " text=" + (r.text.length() > 20 ? r.text.substring(0, 20) : r.text));
        if (!r.ok) {
            stopReply();
            bubble.setText("对话失败：" + (r.error.isEmpty() ? "未知错误" : r.error));
            hint.setVisibility(TextView.VISIBLE);
            hint.setText("说「退出」结束");
            // 失败也算「刚聊过」：不刷新的话空闲计时会把正在重试的会话直接踢掉
            lastTalkAt = System.currentTimeMillis();
            scheduleChatRestart(600);
            return;
        }
        holdReply(r.text);
        armChatIdle();
        if (r.streamed) {
            // 音频已经流式播过了；万一一段都没收到，先释放可能已建的 chatTrack 再用系统发音兜底
            if (streamFrames == 0) {
                stopReply();
                speakSystem(r.text);
            } else if (!streamEnded) {
                // audio_end 万一丢了（比如重连把它吞了），这里自己补一次收尾，别一直等着
                handler.postDelayed(() -> {
                    if (chatTrack != null && !streamEnded) {
                        Log.w(TAG, "chat_reply 已到但 audio_end 没来，自己收尾");
                        endAudioStream();
                    }
                }, 800);
            }
            return;
        }
        playReply(r);
    }

    /** 30 秒没有新的对话就退出会话，回到「哈基米」待命 */
    private static final long CHAT_IDLE_MS = 30000;
    /** 最近一次「有效对话」的时刻（用户说完一句 / 回复播完） */
    private long lastTalkAt;

    private void armChatIdle() {
        lastTalkAt = System.currentTimeMillis();
        if (chatIdleTask != null) handler.removeCallbacks(chatIdleTask);
        chatIdleTask = () -> {
            if (!chatMode) return;
            long idle = System.currentTimeMillis() - lastTalkAt;
            // 还在录（且确实有人声）、等识别、等回复、放声音：不算空闲，过会儿再看
            if (idle < CHAT_IDLE_MS || isReplyPlaying() || chatWaiting || awaitingText
                    || (voiceBusy && recorder.heardVoice())) {
                handler.postDelayed(chatIdleTask, 1000);
                return;
            }
            Log.i(TAG, "对话空闲 30s，退出会话");
            exitChat();
            // 自动退出用「没人理我」的说法，别和用户主动赶它走混在一起
            String bye = IDLE_FAREWELLS[RND.nextInt(IDLE_FAREWELLS.length)];
            bubble.setText(bye);
            wake.mute();
            speakCloud(bye);
        };
        handler.postDelayed(chatIdleTask, CHAT_IDLE_MS);
    }

    private void scheduleChatRestart(long delay) {
        if (!chatMode) return;
        if (chatRestartTask != null) handler.removeCallbacks(chatRestartTask);
        chatRestartTask = () -> {
            if (chatMode && resumed && !voiceBusy && !awaitingText
                    && !chatWaiting && !isReplyPlaying()) startVoice();
        };
        handler.postDelayed(chatRestartTask, delay);
    }

    /** 播音前确保麦克风已停，防止自问自答回声 */
    private void stopMicForPlayback() {
        recorder.cancel();
        voiceBusy = false;
        if (chatRestartTask != null) handler.removeCallbacks(chatRestartTask);
    }

    /** 流式播放：Bridge 边合成边推 PCM，用 AudioTrack 边收边播 */
    private void startAudioStream(int rate) {
        stopMicForPlayback();
        // 注意：不能调用 stopReply()，它会自增 ttsSeq，导致本次流式合成的回调全部被当成过期丢掉
        releaseAudioTrack();
        try {
            int min = android.media.AudioTrack.getMinBufferSize(rate,
                    android.media.AudioFormat.CHANNEL_OUT_MONO,
                    android.media.AudioFormat.ENCODING_PCM_16BIT);
            chatTrack = new android.media.AudioTrack(android.media.AudioManager.STREAM_MUSIC, rate,
                    android.media.AudioFormat.CHANNEL_OUT_MONO,
                    android.media.AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(min * 4, 16384), android.media.AudioTrack.MODE_STREAM);
            chatTrack.play();
            // 音调不是 1.0 时才启用 Sonic，避免个别机型播放头计数异常、音频被提前判成播完
            if (Math.abs(TTS_PITCH - 1f) > 0.01f) {
                try {
                    chatTrack.setPlaybackParams(new android.media.PlaybackParams()
                            .setPitch(TTS_PITCH).setSpeed(1f));
                } catch (Exception ignored) {
                    /* 个别机型不支持，保持原声 */
                }
            }
            streamFrames = 0;
            streamRate = rate;
            streamEnded = false;
            streamLastChunkAt = System.currentTimeMillis();
            streamStartedAt = streamLastChunkAt;
            lastPlayedHead = 0;
            lastPlayedAdvanceAt = streamStartedAt;
            pendingWrites = new java.util.concurrent.atomic.AtomicInteger();
            audioWriter = java.util.concurrent.Executors.newSingleThreadExecutor();
            handler.removeCallbacks(streamDrainTask);
            Log.i(TAG, "audio stream start rate=" + rate + " buf=" + Math.max(min * 4, 16384));
        } catch (Exception e) {
            Log.e(TAG, "audio stream start failed", e);
            chatTrack = null;
        }
    }

    private void pushAudioStream(String base64) {
        if (base64.isEmpty()) return;
        pushAudioStream(android.util.Base64.decode(base64, android.util.Base64.DEFAULT));
    }

    private void pushAudioStream(byte[] pcm) {
        final android.media.AudioTrack track = chatTrack;
        final java.util.concurrent.ExecutorService writer = audioWriter;
        if (track == null || writer == null || pcm.length == 0) return;
        amplify(pcm);
        streamLastChunkAt = System.currentTimeMillis();
        pendingWrites.incrementAndGet();
        try {
            writer.submit(() -> {
                try {
                    track.write(pcm, 0, pcm.length);
                } catch (Exception ignored) {
                } finally {
                    pendingWrites.decrementAndGet();
                }
            });
            streamFrames += pcm.length / 2;
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            pendingWrites.decrementAndGet();
        }
    }

    /** 16bit 小端 PCM 数字增益：外屏扬声器偏小，直接放大 2 倍并做削顶保护 */
    private static void amplify(byte[] pcm) {
        if (PLAY_GAIN <= 1f) return;
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            int s = (short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8));
            int v = Math.round(s * PLAY_GAIN);
            if (v > 32767) v = 32767;
            if (v < -32768) v = -32768;
            pcm[i] = (byte) (v & 0xff);
            pcm[i + 1] = (byte) ((v >> 8) & 0xff);
        }
    }

    private void endAudioStream() {
        streamEnded = true;
        streamEndedAt = System.currentTimeMillis();
        handler.removeCallbacks(streamDrainTask);
        handler.postDelayed(streamDrainTask, 50);
    }

    /** 等 AudioTrack 把缓冲区播完，再恢复听下一句 */
    private void checkStreamDrained() {
        android.media.AudioTrack track = chatTrack;
        if (track == null) {
            chatWaiting = false;
            onReplySpoken();
            return;
        }
        long now = System.currentTimeMillis();
        long played = track.getPlaybackHeadPosition() & 0xFFFFFFFFL;
        if (played > lastPlayedHead || pendingWrites.get() > 0) {
            lastPlayedHead = played;
            lastPlayedAdvanceAt = now;
        }
        long durationMs = streamRate > 0 ? streamFrames * 1000L / streamRate : 0;
        long elapsed = now - streamStartedAt;
        long tol = Math.max(512, streamRate / 20);
        boolean headReached = played + tol >= streamFrames;
        boolean headStalled = pendingWrites.get() == 0 && streamEnded
                && elapsed >= durationMs + 150 && (now - lastPlayedAdvanceAt > 300);
        // 兼容 Android HAL 尾帧对齐停在 streamFrames 之前几百帧的情况，避免播完后还盲等数秒
        boolean drained = pendingWrites.get() == 0 && elapsed >= durationMs - 250
                && (played >= streamFrames || (elapsed >= durationMs + 100 && headReached) || headStalled);
        boolean timeout = now - Math.max(streamStartedAt + durationMs, streamLastChunkAt) > 2500;
        if (drained || timeout) {
            Log.i(TAG, "audio drained played=" + played + "/" + streamFrames
                    + " duration=" + durationMs + "ms elapsed=" + elapsed + "ms"
                    + (timeout && !drained ? " timeout" : ""));
            stopReply();
            chatWaiting = false;
            onReplySpoken();
            return;
        }
        if (streamEnded) handler.postDelayed(streamDrainTask, 50);
    }

    /** 有没有音频在播（播的时候不能开麦，否则会把自己的声音录进去） */
    private boolean isReplyPlaying() {
        return ttsPending || chatTrack != null || replyPlayer != null || systemSpeaking;
    }

    /** AudioTrack 里的数据是不是已经全部播完（看门狗用它判断「其实早该继续听了」） */
    private boolean drainedNow() {
        android.media.AudioTrack track = chatTrack;
        if (track == null) return true;
        if (pendingWrites.get() != 0) return false;
        long now = System.currentTimeMillis();
        long played = track.getPlaybackHeadPosition() & 0xFFFFFFFFL;
        if (played > lastPlayedHead) {
            lastPlayedHead = played;
            lastPlayedAdvanceAt = now;
        }
        long durationMs = streamRate > 0 ? streamFrames * 1000L / streamRate : 0;
        long elapsed = now - streamStartedAt;
        long tol = Math.max(512, streamRate / 20);
        boolean headReached = played + tol >= streamFrames;
        boolean headStalled = streamEnded && elapsed >= durationMs + 150 && (now - lastPlayedAdvanceAt > 300);
        return elapsed >= durationMs - 250
                && (played >= streamFrames || (elapsed >= durationMs + 100 && headReached) || headStalled);
    }

    /** 千问/豆包 TTS 有音频就直接播（整段 WAV / PCM）；都没有就用系统 TTS */
    private void playReply(ChatBus.Reply r) {
        stopMicForPlayback();
        stopReply();
        if (!r.wav.isEmpty() || !r.audio.isEmpty()) {
            try {
                File f = new File(getCacheDir(), "chat_reply.wav");
                if (!r.wav.isEmpty()) {
                    byte[] wav = android.util.Base64.decode(r.wav, android.util.Base64.DEFAULT);
                    try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
                        out.write(wav);
                    }
                } else {
                    byte[] pcm = android.util.Base64.decode(r.audio, android.util.Base64.DEFAULT);
                    writeWav(f, pcm, r.sampleRate);
                }
                MediaPlayer mp = new MediaPlayer();
                replyPlayer = mp;
                mp.setDataSource(f.getAbsolutePath());
                mp.setOnPreparedListener(p -> {
                    if (p == replyPlayer) p.start();
                });
                mp.setOnCompletionListener(p -> {
                    if (p == replyPlayer) replyPlayer = null;
                    try {
                        p.release();
                    } catch (Exception ignored) {
                    }
                    onReplySpoken();
                });
                mp.setOnErrorListener((p, what, extra) -> {
                    Log.e(TAG, "reply audio error " + what);
                    stopReply();
                    speakSystem(r.text);
                    return true;
                });
                mp.prepareAsync();
                return;
            } catch (Exception e) {
                Log.e(TAG, "reply audio failed", e);
            }
        }
        speakSystem(r.text);
    }

    /** 只关掉正在播放的 AudioTrack，不动 ttsSeq：新建流时不能把本次回调用到的 seq 作废 */
    private void releaseAudioTrack() {
        handler.removeCallbacks(streamDrainTask);
        java.util.concurrent.ExecutorService writer = audioWriter;
        audioWriter = null;
        if (writer != null) writer.shutdownNow();
        android.media.AudioTrack track = chatTrack;
        chatTrack = null;
        if (track != null) {
            try {
                track.pause();
                track.flush();
                track.stop();
            } catch (Exception ignored) {
            }
            try {
                track.release();
            } catch (Exception ignored) {
            }
        }
        pendingWrites.set(0);
    }

    private void stopReply() {
        ttsSeq++;
        ttsPending = false;
        releaseAudioTrack();
        systemSpeaking = false;
        MediaPlayer mp = replyPlayer;
        replyPlayer = null;
        if (mp != null) {
            try {
                mp.stop();
            } catch (Exception ignored) {
            }
            try {
                mp.release();
            } catch (Exception ignored) {
            }
        }
        if (tts != null) {
            try {
                tts.stop();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 没连电脑时的出声：用千问流式 TTS，音色和电脑端一致（默认 Nofish），
     * 失败才退回手机系统 TTS，免得同一只狗一会儿女声一会儿男声。
     */
    private void speakCloud(String text) {
        stopMicForPlayback();
        stopReply();
        if (text == null || text.isEmpty()) {
            onReplySpoken();
            return;
        }
        holdReply(text);
        Log.i(TAG, "speakCloud mode=" + PhoneVoice.ttsMode(this)
                + " ready=" + PhoneVoice.ttsReady(this) + " len=" + text.length());
        // 手机端离线 TTS：sherpa-onnx 中文 VITS（免费，首次后台下载模型）
        if (PhoneVoice.TTS_MODE_LOCAL.equals(PhoneVoice.ttsMode(this))) {
            wake.mute();
            ttsPending = true;
            ttsPendingAt = System.currentTimeMillis();
            final long seq = ++ttsSeq;
            final String say = text;
            LocalTts.speak(this, say, new LocalTts.Callback() {
                @Override
                public void onFile(File file) {
                    handler.post(() -> {
                        if (seq != ttsSeq || !resumed) return;
                        ttsPending = false;
                        playAudioFile(file, say);
                    });
                }

                @Override
                public void onError(String message) {
                    handler.post(() -> {
                        if (seq != ttsSeq || !resumed) return;
                        ttsPending = false;
                        Log.w(TAG, "local tts: " + message);
                        speakSystem(say);
                    });
                }
            }, msg -> handler.post(() -> {
                if (seq == ttsSeq) bubble.setText(msg);
            }));
            return;
        }
        if (!PhoneVoice.ttsReady(this)) {
            Log.w(TAG, "tts not ready, fallback");
            speakFallback(text);
            return;
        }
        wake.mute();
        final String say = text;
        ttsPending = true;
        ttsPendingAt = System.currentTimeMillis();
        final long seq = ++ttsSeq;
        if (PhoneVoice.TTS_MODE_OPENAI.equals(PhoneVoice.ttsMode(this))) {
            PhoneVoice.speakSpeechFile(this, say, new PhoneVoice.AudioFileCallback() {
                @Override
                public void onFile(File file) {
                    if (seq != ttsSeq || !resumed) return;
                    ttsPending = false;
                    playAudioFile(file, say);
                }

                @Override
                public void onError(String message) {
                    if (seq != ttsSeq || !resumed) return;
                    ttsPending = false;
                    Log.w(TAG, "openai tts: " + message);
                    speakSystem(say);
                }
            });
            return;
        }
        PhoneVoice.speak(this, say, new PhoneVoice.SpeakListener() {
            @Override
            public void onStart(int sampleRate) {
                if (seq != ttsSeq || !resumed) return;
                ttsPending = false;
                startAudioStream(sampleRate);
            }

            @Override
            public void onPcm(byte[] pcm) {
                if (seq != ttsSeq || !resumed) return;
                pushAudioStream(pcm);
            }

            @Override
            public void onDone() {
                if (seq != ttsSeq || !resumed) return;
                ttsPending = false;
                // 云端一句都没吐（或 AudioTrack 没建起来）：退回系统 TTS，至少要有声音
                if (chatTrack != null && streamFrames > 0) {
                    endAudioStream();
                } else {
                    Log.w(TAG, "cloud tts 没有音频，退回本机发音");
                    speakFallback(say);
                }
            }

            @Override
            public void onError(String message) {
                if (seq != ttsSeq || !resumed) return;
                ttsPending = false;
                Log.w(TAG, "cloud tts: " + message);
                if (chatTrack != null && streamFrames > 0) endAudioStream();
                else speakFallback(say);
            }
        });
    }

    /** 云端发音失败时的兜底：优先本机童声（离线），再退系统 TTS */
    private void speakFallback(String text) {
        stopReply();
        if (!LocalTts.ready(this)) {
            Log.w(TAG, "local tts 模型未就绪，退回系统 TTS");
            speakSystem(text);
            return;
        }
        final long seq = ++ttsSeq;
        ttsPending = true;
        ttsPendingAt = System.currentTimeMillis();
        LocalTts.speak(this, text, new LocalTts.Callback() {
            @Override
            public void onFile(File file) {
                handler.post(() -> {
                    if (seq != ttsSeq || !resumed) return;
                    ttsPending = false;
                    playAudioFile(file, text);
                });
            }

            @Override
            public void onError(String message) {
                handler.post(() -> {
                    if (seq != ttsSeq || !resumed) return;
                    ttsPending = false;
                    Log.w(TAG, "local fallback tts: " + message);
                    speakSystem(text);
                });
            }
        }, msg -> handler.post(() -> {
            if (seq == ttsSeq) bubble.setText(msg);
        }));
    }

    private void playAudioFile(File file, String fallbackText) {
        stopMicForPlayback();
        stopReply();
        try {
            MediaPlayer mp = new MediaPlayer();
            replyPlayer = mp;
            mp.setDataSource(file.getAbsolutePath());
            mp.setOnPreparedListener(p -> {
                if (p == replyPlayer) p.start();
            });
            mp.setOnCompletionListener(p -> {
                if (p == replyPlayer) replyPlayer = null;
                try {
                    p.release();
                } catch (Exception ignored) {
                }
                onReplySpoken();
            });
            mp.setOnErrorListener((p, what, extra) -> {
                Log.e(TAG, "speech audio error " + what);
                stopReply();
                speakSystem(fallbackText);
                return true;
            });
            mp.prepareAsync();
        } catch (Exception e) {
            Log.e(TAG, "speech audio failed", e);
            stopReply();
            speakSystem(fallbackText);
        }
    }

    private void speakSystem(String text) {
        stopMicForPlayback();
        if (tts == null || !ttsReady || text.isEmpty()) {
            onReplySpoken();
            return;
        }
        systemSpeaking = true;
        try {
            tts.setPitch(TTS_PITCH);
            tts.setSpeechRate(1.02f);
        } catch (Exception ignored) {
        }
        tts.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
            }

            @Override
            public void onDone(String utteranceId) {
                systemSpeaking = false;
                handler.post(DeviceActivity.this::onReplySpoken);
            }

            @Override
            public void onError(String utteranceId) {
                systemSpeaking = false;
                handler.post(DeviceActivity.this::onReplySpoken);
            }
        });
        int res = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "chat");
        if (res != TextToSpeech.SUCCESS) {
            systemSpeaking = false;
            onReplySpoken();
        }
    }

    /** 说完/播完继续听下一句 */
    private void onReplySpoken() {
        if (chatMode) {
            armChatIdle();              // 从「播完」开始算空闲时间
            scheduleChatRestart(50);
            return;
        }
        if (!wakeOn) return;
        // 常驻监听：一轮答完接着等「哈基米」
        chatWaiting = false;
        awaitingText = false;
        wake.unmute();
        hint.setVisibility(TextView.VISIBLE);
        hint.setText(wakeHint());
        if (!wake.isRunning()) wake.start(wakeCallback);
    }

    private void initTts() {
        ttsReady = false;
        tts = new TextToSpeech(this, status -> {
            ttsReady = status == TextToSpeech.SUCCESS && tts != null;
            if (ttsReady) {
                try {
                    tts.setLanguage(java.util.Locale.CHINA);
                } catch (Exception ignored) {
                }
            }
        });
    }

    private TextView pillButton(String label, int bgColor, int textColor) {
        TextView v = new TextView(this);
        v.setText(label);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setTextColor(textColor);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(10), dp(8), dp(10), dp(8));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(dp(12));
        v.setBackground(bg);
        return v;
    }

    /** PCM（16 bit 单声道）套一个 WAV 头，交给 MediaPlayer 播放（实现在 WavUtil，与 PhoneVoice 共用） */
    private static void writeWav(File file, byte[] pcm, int sampleRate) throws Exception {
        WavUtil.writeWav(file, pcm, sampleRate);
    }

    // ------------------------------------------------------------ 视频播放

    private void playLoop(String family) {
        oneShot = false;
        start(family);
    }

    private void playOnce(String family) {
        oneShot = true;
        start(family);
    }

    private void start(String family) {
        wanted = family;
        if (!surfaceReady) return;                       // surfaceCreated 时会补播
        if (family.equals(playing) && player != null && player.isPlaying()) return;
        playing = family;
        Log.i(TAG, "clip " + family);
        releasePlayer();
        try {
            AssetFileDescriptor afd = getAssets().openFd(DIR + family + ".mp4");
            MediaPlayer mp = new MediaPlayer();
            mp.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            afd.close();
            mp.setSurface(videoSurface);
            mp.setOnPreparedListener(p -> {
                if (p == player) {
                    playerPrepared = true;
                    if (resumed) p.start();
                }
            });
            mp.setOnCompletionListener(p -> {
                if (p == player) onClipEnd();
            });
            mp.setOnErrorListener((p, what, extra) -> {
                Log.e(TAG, "clip error " + family + " what=" + what + " extra=" + extra);
                return true;
            });
            player = mp;
            mp.prepareAsync();
        } catch (Exception e) {
            Log.e(TAG, "clip open failed " + family + " " + e.getMessage());
        }
    }

    private void onClipEnd() {
        oneShot = false;
        resumeState();
    }

    private void resumeState() {
        switch (state) {
            case "waiting":
                playLoop("waiting_user");
                break;
            case "thinking":
            case "tool":
                playLoop(nextWorkClip());
                break;
            default:
                playLoop(nextIdleClip());
        }
    }

    private String nextIdleClip() {
        String f = IDLE_CLIPS[idleIndex % IDLE_CLIPS.length];
        idleIndex++;
        return f;
    }

    private String nextWorkClip() {
        String[] pool = "tool".equals(state) ? TOOL_CLIPS : WORK_CLIPS;
        String f = pool[workIndex % pool.length];
        workIndex++;
        return f;
    }

    private void releasePlayer() {
        playerPrepared = false;
        if (player == null) return;
        MediaPlayer mp = player;
        player = null;
        try {
            mp.reset();
            mp.release();
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------ 提示音

    private void cue(String name) {
        cue(name, false);
    }

    /**
     * 播放提示音。
     *
     * @param force true 时忽略「音效开关」：任务完成的汪汪叫属于核心反馈，
     *              不能因为误长按屏幕把音效关掉就消失
     */
    private void cue(String name, boolean force) {
        if (!force && !BridgeService.sfxEnabled(this)) {
            Log.i(TAG, "cue skipped (sfx off): " + name);
            return;
        }
        releaseCue();
        // 优先用外部文件：/sdcard/Android/data/<包名>/files/<name>.wav，可直接替换成自己的录音，不用重装
        java.io.File ext = new java.io.File(getExternalFilesDir(null), name + ".wav");
        boolean useExt = ext.exists();
        try {
            cue = new MediaPlayer();
            if (useExt) {
                cue.setDataSource(ext.getAbsolutePath());
            } else {
                AssetFileDescriptor afd = getAssets().openFd(DIR + name + ".wav");
                cue.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
                afd.close();
            }
            // 放提示音的这几百毫秒里把自己听成用户，会白白吞掉唤醒词
            wake.mute();
            cue.setOnCompletionListener(mp -> {
                releaseCue();
                if (!chatMode && !isReplyPlaying() && !awaitingText) wake.unmute();
            });
            cue.setOnErrorListener((mp, what, extra) -> {
                Log.w(TAG, "sfx " + name + " playback error " + what + "/" + extra);
                releaseCue();
                if (!chatMode && !isReplyPlaying() && !awaitingText) wake.unmute();
                return true;
            });
            cue.setOnPreparedListener(mp -> {
                Log.i(TAG, "sfx " + name + " " + mp.getDuration() + "ms " + (useExt ? "(ext)" : "(asset)"));
                mp.start();
            });
            cue.prepareAsync();
        } catch (Exception e) {
            Log.e(TAG, "sfx " + name + " failed: " + e.getMessage());
            releaseCue();
            if (!chatMode && !isReplyPlaying() && !awaitingText) wake.unmute();
        }
    }

    private void releaseCue() {
        if (cue == null) return;
        try {
            cue.release();
        } catch (Exception ignored) {
        }
        cue = null;
    }

    // ------------------------------------------------------------ 文案

    private static String agentName(String agent) {
        String a = agent == null ? "" : agent.trim();
        return a.isEmpty() ? "HACHIMO" : a.toUpperCase(java.util.Locale.ROOT);
    }

    private static String sessionText(AppState.State s) {
        String sid = s.sessionId == null ? "" : s.sessionId;
        return sid.isEmpty() ? "SESSION —" : "SESSION " + sid.toUpperCase(java.util.Locale.ROOT);
    }

    private static String chinese(String st) {
        switch (st) {
            case "thinking": return "思考中";
            case "tool": return "执行中";
            case "waiting": return "等你确认";
            case "done": return "可以查看";
            case "error": return "出错了";
            default: return "待机";
        }
    }

    private static String defaultLabel(String st) {
        switch (st) {
            case "thinking": return "正在思考…";
            case "tool": return "正在执行工具…";
            case "waiting": return "需要你确认";
            case "done": return "任务完成";
            case "error": return "执行出错";
            default: return "待机中";
        }
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}
