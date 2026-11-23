package com.example.hachimophone;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 内屏控制台：状态概览、外屏拉起、免费/付费语音方案配置、高级接口抽屉与 PC Bridge 联动。
 */
public final class MainActivity extends Activity implements AppState.Listener {

    private static final int BG_CANVAS = 0xFF0B1220;
    private static final int BG_CARD = 0xFF131C2E;
    private static final int STROKE_CARD = 0xFF24324A;
    private static final int BG_INPUT = 0xFF0B1220;
    private static final int STROKE_INPUT = 0xFF2B3B55;
    private static final int BG_PILL = 0xFF1E293B;
    private static final int ACCENT_CYAN = 0xFF22D3EE;
    private static final int ACCENT_TEXT_DARK = 0xFF082F49;
    private static final int TEXT_PRIMARY = 0xFFF1F5F9;
    private static final int TEXT_SECONDARY = 0xFF94A3B8;
    private static final int TEXT_HINT = 0xFF64748B;

    private EditText urlInput;
    private EditText displayInput;
    private TextView statusDot;
    private TextView status;
    private TextView overlayPill;
    private android.media.MediaPlayer samplePlayer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG_CANVAS);
        root.setPadding(dp(16), dp(18), dp(16), dp(28));

        // ------------------------------------------------------------ 顶部标题栏
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams headerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        headerLp.bottomMargin = dp(12);
        root.addView(header, headerLp);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("HachimoPhone");
        title.setTextColor(TEXT_PRIMARY);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        titleCol.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("外屏宠物 · 语音与视觉控制台");
        subtitle.setTextColor(TEXT_SECONDARY);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        titleCol.addView(subtitle);
        header.addView(titleCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView verBadge = pillText("v" + BridgeService.versionLabel(this), 0xFF164E63, 0xFF67E8F9);
        verBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        verBadge.setPadding(dp(10), dp(4), dp(10), dp(4));
        header.addView(verBadge);

        // ------------------------------------------------------------ 卡片 1：运行状态与外屏
        LinearLayout statusCard = addCard(root, null);

        LinearLayout statusTop = new LinearLayout(this);
        statusTop.setOrientation(LinearLayout.HORIZONTAL);
        statusTop.setGravity(Gravity.CENTER_VERTICAL);
        statusDot = new TextView(this);
        statusDot.setText("●");
        statusDot.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        statusDot.setPadding(0, 0, dp(6), 0);
        statusTop.addView(statusDot);

        TextView statusTitle = new TextView(this);
        statusTitle.setText("运行状态");
        statusTitle.setTextColor(TEXT_PRIMARY);
        statusTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        statusTitle.setTypeface(Typeface.DEFAULT_BOLD);
        statusTop.addView(statusTitle);
        statusCard.addView(statusTop);

        status = new TextView(this);
        status.setTextColor(TEXT_SECONDARY);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        status.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stLp.topMargin = dp(6);
        stLp.bottomMargin = dp(10);
        statusCard.addView(status, stLp);

        LinearLayout quickRow = rowLayout();
        TextView coverBtn = pillButton("打开外屏展示页", ACCENT_CYAN, ACCENT_TEXT_DARK);
        coverBtn.setOnClickListener(v -> startActivity(new Intent(this, DeviceActivity.class)));
        addWeight(quickRow, coverBtn, 1.3f, 8);

        overlayPill = pillButton("开启外屏常驻", BG_PILL, TEXT_PRIMARY);
        overlayPill.setOnClickListener(v -> {
            if (Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "外屏常驻已开启，合盖可自动显示小狗", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:" + getPackageName()));
            try {
                startActivity(i);
            } catch (Exception e) {
                Toast.makeText(this, "无法打开设置: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        addWeight(quickRow, overlayPill, 1f, 0);
        statusCard.addView(quickRow);

        // ------------------------------------------------------------ 卡片 2：语音方案与密钥
        LinearLayout voiceCard = addCard(root, "语音方案与模型");

        // 分段切换：免费方案 vs 付费方案
        LinearLayout planSegment = new LinearLayout(this);
        planSegment.setOrientation(LinearLayout.HORIZONTAL);
        planSegment.setBackground(roundRect(BG_INPUT, STROKE_INPUT, dp(12)));
        planSegment.setPadding(dp(4), dp(4), dp(4), dp(4));
        LinearLayout.LayoutParams segLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        segLp.bottomMargin = dp(10);
        voiceCard.addView(planSegment, segLp);

        final TextView freePlanTab = pillButton("免费 (智谱+阿里)", ACCENT_CYAN, ACCENT_TEXT_DARK);
        freePlanTab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        final TextView paidPlanTab = pillButton("付费 (阿里云 1 Key)", Color.TRANSPARENT, TEXT_SECONDARY);
        paidPlanTab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        addWeight(planSegment, freePlanTab, 1f, 4);
        addWeight(planSegment, paidPlanTab, 1f, 0);

        // 常用 Key（免费：智谱负责想 glm-4.7-flash 和看 glm-4v-flash + 阿里云每月免费语音/或本机童声；付费：阿里云 1 Key）
        final EditText zhipuKeyInput = styledInput(
                "智谱 Key（永久免费 · 对话+看图共用）", PhoneVoice.zhipuKey(this), true);
        final EditText freeDashKeyInput = styledInput(
                "阿里云 Key（每月重置 paraformer+sambert，留空用本机童声）", PhoneVoice.dashKey(this), true);
        final EditText dashKeyInput = styledInput(
                "阿里云 DashScope Key（一个 Key 全通，sk-…）", PhoneVoice.dashKey(this), true);

        final LinearLayout freeKeysBox = new LinearLayout(this);
        freeKeysBox.setOrientation(LinearLayout.VERTICAL);
        addLabeledField(freeKeysBox, "智谱 Key（永久免费 · 对话 glm-4.7-flash + 看图）", zhipuKeyInput);
        addLabeledField(freeKeysBox, "阿里云 Key（每月重置免费识别+语音 · 留空用本机童声）", freeDashKeyInput);
        voiceCard.addView(freeKeysBox);

        final LinearLayout paidKeysBox = new LinearLayout(this);
        paidKeysBox.setOrientation(LinearLayout.VERTICAL);
        addLabeledField(paidKeysBox, "阿里云 DashScope Key（识别 + DeepSeek-V3 + CosyVoice + 看图）", dashKeyInput);
        voiceCard.addView(paidKeysBox);

        // 语音与唤醒快捷控制
        LinearLayout ttsRow = rowLayout();
        final TextView ttsModeBtn = pillButton("", BG_PILL, TEXT_PRIMARY);
        final Runnable[] refreshTtsBtn = new Runnable[1];
        refreshTtsBtn[0] = () -> {
            String m = PhoneVoice.ttsMode(this);
            String tm = PhoneVoice.ttsModel(this).toLowerCase(java.util.Locale.US);
            String dashLabel = tm.startsWith("sambert") ? "发音：Sambert免费" : "发音：CosyVoice";
            String label = PhoneVoice.TTS_MODE_LOCAL.equals(m) ? "发音：本机童声"
                    : (PhoneVoice.TTS_MODE_OPENAI.equals(m) ? "发音：OpenAI/本地"
                    : (PhoneVoice.TTS_MODE_DASHSCOPE.equals(m) ? dashLabel : "发音：0元系统TTS"));
            ttsModeBtn.setText(label);
        };
        ttsModeBtn.setOnClickListener(v -> {
            String cur = PhoneVoice.ttsMode(this);
            boolean hasDash = !(PhoneVoice.planPaid(this) ? dashKeyInput : freeDashKeyInput)
                    .getText().toString().trim().isEmpty()
                    || !PhoneVoice.dashKey(this).isEmpty();
            String next;
            if (PhoneVoice.TTS_MODE_SYSTEM.equals(cur)) {
                next = PhoneVoice.TTS_MODE_LOCAL;
            } else if (PhoneVoice.TTS_MODE_LOCAL.equals(cur)) {
                next = hasDash ? PhoneVoice.TTS_MODE_DASHSCOPE : PhoneVoice.TTS_MODE_SYSTEM;
            } else {
                next = PhoneVoice.TTS_MODE_SYSTEM;
            }
            PhoneVoice.setTtsMode(this, next);
            refreshTtsBtn[0].run();
        });
        refreshTtsBtn[0].run();
        addWeight(ttsRow, ttsModeBtn, 1.3f, 6);

        final TextView sidBtn = pillButton("", BG_PILL, TEXT_PRIMARY);
        final Runnable[] refreshSid = new Runnable[1];
        refreshSid[0] = () -> sidBtn.setText("童声 #" + LocalTts.speakerId(this));
        sidBtn.setOnClickListener(v -> {
            LocalTts.setSpeakerId(this, (LocalTts.speakerId(this) + 1) % 5);
            refreshSid[0].run();
        });
        refreshSid[0].run();
        addWeight(ttsRow, sidBtn, 0.9f, 6);

        TextView previewSidBtn = pillButton("试听", 0xFF164E63, 0xFF67E8F9);
        previewSidBtn.setOnClickListener(v -> playLocalSample(LocalTts.speakerId(this)));
        addWeight(ttsRow, previewSidBtn, 0.6f, 0);
        voiceCard.addView(ttsRow);

        // 方案样式联动
        final Runnable[] refreshPlan = new Runnable[1];
        refreshPlan[0] = () -> {
            boolean paid = PhoneVoice.planPaid(this);
            stylePill(freePlanTab, paid ? Color.TRANSPARENT : ACCENT_CYAN, paid ? TEXT_SECONDARY : ACCENT_TEXT_DARK);
            stylePill(paidPlanTab, paid ? ACCENT_CYAN : Color.TRANSPARENT, paid ? ACCENT_TEXT_DARK : TEXT_SECONDARY);
            freeKeysBox.setVisibility(paid ? View.GONE : View.VISIBLE);
            paidKeysBox.setVisibility(paid ? View.VISIBLE : View.GONE);
        };
        refreshPlan[0].run();

        View.OnClickListener switchPlan = v -> {
            boolean toPaid = v == paidPlanTab;
            boolean wasPaid = PhoneVoice.planPaid(this);
            String curDk = (wasPaid ? dashKeyInput : freeDashKeyInput).getText().toString().trim();
            dashKeyInput.setText(curDk);
            freeDashKeyInput.setText(curDk);
            PhoneVoice.setZhipuKey(this, zhipuKeyInput.getText().toString());
            PhoneVoice.setDashKey(this, curDk);
            String hintMsg = PhoneVoice.applyPlan(this, toPaid);
            refreshPlan[0].run();
            refreshTtsBtn[0].run();
            String msg = toPaid ? "已切到付费方案"
                    : (curDk.isEmpty() ? "已切到智谱免费方案（本机童声）" : "已切到智谱+阿里长期免费方案");
            if (!hintMsg.isEmpty()) msg += "（" + hintMsg + "）";
            Toast.makeText(this, msg, hintMsg.isEmpty() ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
        };
        freePlanTab.setOnClickListener(switchPlan);
        paidPlanTab.setOnClickListener(switchPlan);

        // 主保存按钮
        TextView saveCloud = pillButton("保存语音与模型配置", ACCENT_CYAN, ACCENT_TEXT_DARK);
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        saveLp.topMargin = dp(2);
        saveLp.bottomMargin = dp(4);
        voiceCard.addView(saveCloud, saveLp);

        saveCloud.setOnClickListener(v -> {
            boolean paid = PhoneVoice.planPaid(this);
            boolean hadDk = !PhoneVoice.dashKey(this).isEmpty();
            String prevTts = PhoneVoice.ttsMode(this);
            String zp = zhipuKeyInput.getText().toString().trim();
            String dk = (paid ? dashKeyInput : freeDashKeyInput).getText().toString().trim();
            dashKeyInput.setText(dk);
            freeDashKeyInput.setText(dk);
            PhoneVoice.setZhipuKey(this, zp);
            PhoneVoice.setDashKey(this, dk);
            String hintMsg = PhoneVoice.applyPlan(this, paid);
            if (hadDk && !dk.isEmpty() && !PhoneVoice.TTS_MODE_DASHSCOPE.equals(prevTts)) {
                PhoneVoice.setTtsMode(this, prevTts);
            }
            refreshTtsBtn[0].run();
            String toast = PhoneVoice.asrReady(this) && PhoneVoice.chatReady(this)
                    ? "已保存：纯手机可独立对话"
                    : ("已保存" + (hintMsg.isEmpty() ? "" : "（" + hintMsg + "）"));
            Toast.makeText(this, toast, Toast.LENGTH_SHORT).show();
        });

        // ------------------------------------------------------------ 卡片 3：电脑 Bridge 联动
        LinearLayout bridgeCard = addCard(root, "电脑端 Bridge 联动（可选）");

        urlInput = styledInput("ws://192.168.x.x:8765/ws（留空 = 自动发现）", BridgeService.bridgeUrl(this), false);
        addLabeledField(bridgeCard, "Bridge 地址", urlInput);

        final EditText tokenInput = styledInput("配对令牌（见电脑 bridge/token.txt）", BridgeService.bridgeToken(this), false);
        displayInput = styledInput("1", String.valueOf(BridgeService.coverDisplayId(this)), false);
        displayInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        LinearLayout bridgeMetaRow = rowLayout();
        addWeight(bridgeMetaRow, tokenInput, 2.4f, 8);
        addWeight(bridgeMetaRow, displayInput, 0.8f, 0);
        addLabeledField(bridgeCard, "配对令牌 / 外屏 Display ID", bridgeMetaRow);

        LinearLayout connRow = rowLayout();
        TextView connect = pillButton("连接", 0xFF164E63, 0xFF67E8F9);
        connect.setOnClickListener(v -> {
            if (!BridgeService.setBridgeUrl(this, urlInput.getText().toString().trim())) {
                Toast.makeText(this, "地址格式不对，应形如 ws://192.168.1.5:8765/ws", Toast.LENGTH_LONG).show();
                return;
            }
            BridgeService.setBridgeToken(this, tokenInput.getText().toString().trim());
            BridgeService.setUserDisconnected(this, false);
            saveDisplayId();
            BridgeService.start(this);
        });
        addWeight(connRow, connect, 1f, 6);

        TextView disconnect = pillButton("断开", BG_PILL, TEXT_PRIMARY);
        disconnect.setOnClickListener(v -> {
            BridgeService.setUserDisconnected(this, true);
            BridgeService.stop(this);
        });
        addWeight(connRow, disconnect, 1f, 6);

        TextView discover = pillButton("自动发现", BG_PILL, TEXT_PRIMARY);
        discover.setOnClickListener(v -> {
            urlInput.setText("");
            BridgeService.setBridgeUrl(this, "");
            BridgeService.setBridgeFingerprint(this, "");
            BridgeService.setBridgeToken(this, tokenInput.getText().toString().trim());
            BridgeService.setUserDisconnected(this, false);
            saveDisplayId();
            BridgeService.stop(this);
            BridgeService.start(this);
            Toast.makeText(this, "正在搜索局域网内 Bridge…", Toast.LENGTH_SHORT).show();
        });
        addWeight(connRow, discover, 1.1f, 0);
        bridgeCard.addView(connRow);

        // 下发一句话给 Claude
        LinearLayout promptRow = rowLayout();
        final EditText promptInput = styledInput("发给 Claude 的一句话指令", "", false);
        addWeight(promptRow, promptInput, 2.4f, 8);
        TextView send = pillButton("发送", 0xFF164E63, 0xFF67E8F9);
        send.setOnClickListener(v -> {
            String text = promptInput.getText().toString().trim();
            if (text.isEmpty()) {
                Toast.makeText(this, "先输入内容", Toast.LENGTH_SHORT).show();
                return;
            }
            boolean ok = BridgeService.sendPrompt(text);
            Toast.makeText(this, ok ? "已发送" : "发送失败：Bridge 未连接", Toast.LENGTH_SHORT).show();
            if (ok) promptInput.setText("");
        });
        addWeight(promptRow, send, 0.9f, 0);
        bridgeCard.addView(promptRow);

        // ------------------------------------------------------------ 卡片 4：外设与交互开关
        LinearLayout toggleCard = addCard(root, "外设与交互开关");
        LinearLayout toggleRow = rowLayout();

        final TextView sfx = pillButton("", BG_PILL, TEXT_PRIMARY);
        final Runnable[] refreshSfx = new Runnable[1];
        refreshSfx[0] = () -> {
            boolean on = BridgeService.sfxEnabled(this);
            sfx.setText(on ? "音效：开" : "音效：关");
            stylePill(sfx, on ? 0xFF164E63 : BG_PILL, on ? 0xFF67E8F9 : TEXT_SECONDARY);
        };
        sfx.setOnClickListener(v -> {
            BridgeService.setSfxEnabled(this, !BridgeService.sfxEnabled(this));
            refreshSfx[0].run();
        });
        refreshSfx[0].run();
        addWeight(toggleRow, sfx, 1f, 6);

        final TextView cam = pillButton("", BG_PILL, TEXT_PRIMARY);
        final Runnable[] refreshCam = new Runnable[1];
        refreshCam[0] = () -> {
            boolean on = BridgeService.cameraEnabled(this);
            cam.setText(on ? "摄像头：常开" : "摄像头：按需");
            stylePill(cam, on ? 0xFF164E63 : BG_PILL, on ? 0xFF67E8F9 : TEXT_SECONDARY);
        };
        cam.setOnClickListener(v -> {
            BridgeService.setCameraEnabled(this, !BridgeService.cameraEnabled(this));
            refreshCam[0].run();
        });
        refreshCam[0].run();
        addWeight(toggleRow, cam, 1f, 6);

        final TextView mlkit = pillButton("", BG_PILL, TEXT_PRIMARY);
        final Runnable[] refreshMlkit = new Runnable[1];
        refreshMlkit[0] = () -> {
            boolean on = BridgeService.mlkitEnabled(this);
            mlkit.setText(on ? "人脸追踪：开" : "人脸追踪：关");
            stylePill(mlkit, on ? 0xFF164E63 : BG_PILL, on ? 0xFF67E8F9 : TEXT_SECONDARY);
        };
        mlkit.setOnClickListener(v -> {
            BridgeService.setMlkitEnabled(this, !BridgeService.mlkitEnabled(this));
            refreshMlkit[0].run();
        });
        refreshMlkit[0].run();
        addWeight(toggleRow, mlkit, 1f, 0);
        toggleCard.addView(toggleRow);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG_CANVAS);
        scroll.setFillViewport(true);
        scroll.addView(root);
        setContentView(scroll);

        AppState.get().add(this);
        onState(AppState.get().snapshot());
        refreshOverlayPill();
        requestStartupPermissions();
        KwsWake.preload(this);
        if (BridgeService.autoStart(this) && !BridgeService.userDisconnected(this)) {
            BridgeService.start(this);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshOverlayPill();
    }

    private void refreshOverlayPill() {
        if (overlayPill == null) return;
        boolean granted = Settings.canDrawOverlays(this);
        overlayPill.setText(granted ? "外屏常驻：已开启" : "开启外屏常驻");
        stylePill(overlayPill, granted ? 0xFF14532D : 0xFF78350F, granted ? 0xFF86EFAC : 0xFFFDE68A);
    }

    // ------------------------------------------------------------ UI 组件工厂

    private LinearLayout addCard(LinearLayout parent, String sectionTitle) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundRect(BG_CARD, STROKE_CARD, dp(16)));
        card.setPadding(dp(14), dp(14), dp(14), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        parent.addView(card, lp);

        if (sectionTitle != null && !sectionTitle.isEmpty()) {
            TextView hdr = new TextView(this);
            hdr.setText(sectionTitle);
            hdr.setTextColor(TEXT_PRIMARY);
            hdr.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            hdr.setTypeface(Typeface.DEFAULT_BOLD);
            LinearLayout.LayoutParams hdrLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hdrLp.bottomMargin = dp(8);
            card.addView(hdr, hdrLp);
        }
        return card;
    }

    private void addLabeledField(LinearLayout parent, String label, View field) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(TEXT_SECONDARY);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        LinearLayout.LayoutParams lLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lLp.bottomMargin = dp(3);
        parent.addView(tv, lLp);

        if (field.getParent() == null) {
            LinearLayout.LayoutParams fLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            fLp.bottomMargin = dp(8);
            parent.addView(field, fLp);
        }
    }

    private EditText styledInput(String hint, String value, boolean password) {
        EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setHint(hint);
        et.setHintTextColor(TEXT_HINT);
        et.setTextColor(TEXT_PRIMARY);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        if (password) {
            et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        et.setText(value);
        et.setBackground(roundRect(BG_INPUT, STROKE_INPUT, dp(10)));
        et.setPadding(dp(10), dp(8), dp(10), dp(8));
        return et;
    }

    private TextView pillButton(String text, int bgColor, int textColor) {
        TextView btn = pillText(text, bgColor, textColor);
        btn.setPadding(dp(10), dp(9), dp(10), dp(9));
        btn.setClickable(true);
        btn.setFocusable(true);
        return btn;
    }

    private TextView pillText(String text, int bgColor, int textColor) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(textColor);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setMaxLines(1);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tv.setBackground(roundRect(bgColor, 0, dp(10)));
        return tv;
    }

    private void stylePill(TextView btn, int bgColor, int textColor) {
        btn.setTextColor(textColor);
        btn.setBackground(roundRect(bgColor, 0, dp(10)));
    }

    private LinearLayout rowLayout() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        row.setLayoutParams(lp);
        return row;
    }

    private void addWeight(LinearLayout row, View child, float weight, int rightMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
        if (rightMarginDp > 0) lp.rightMargin = dp(rightMarginDp);
        row.addView(child, lp);
    }

    private GradientDrawable roundRect(int fill, int stroke, int radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(radiusPx);
        if (stroke != 0) d.setStroke(dp(1), stroke);
        return d;
    }

    // ------------------------------------------------------------ 权限与音频试听

    /** 合并申请通知与录音权限：连续调两次 requestPermissions 后一次会被系统丢掉 */
    private void requestStartupPermissions() {
        java.util.List<String> needed = new java.util.ArrayList<>();
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            needed.add(android.Manifest.permission.POST_NOTIFICATIONS);
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            needed.add(android.Manifest.permission.RECORD_AUDIO);
        }
        if (!needed.isEmpty()) {
            requestPermissions(needed.toArray(new String[0]), 1);
        }
    }

    private void saveDisplayId() {
        try {
            int id = Integer.parseInt(displayInput.getText().toString().trim());
            BridgeService.setCoverDisplayId(this, id);
        } catch (NumberFormatException ignored) {
            // 留空或非法值就沿用已保存的设置
        }
    }

    private void playLocalSample(int sid) {
        stopSamplePlayer();
        try {
            android.content.res.AssetFileDescriptor afd =
                    getAssets().openFd("tts_samples/local_sid" + sid + ".wav");
            android.media.MediaPlayer mp = new android.media.MediaPlayer();
            samplePlayer = mp;
            mp.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            afd.close();
            mp.setOnPreparedListener(p -> {
                if (p == samplePlayer) p.start();
            });
            mp.setOnCompletionListener(p -> stopSamplePlayer());
            mp.setOnErrorListener((p, what, extra) -> {
                stopSamplePlayer();
                return true;
            });
            mp.prepareAsync();
        } catch (Exception e) {
            Toast.makeText(this, "试听失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void stopSamplePlayer() {
        android.media.MediaPlayer mp = samplePlayer;
        samplePlayer = null;
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
    }

    @Override
    protected void onDestroy() {
        stopSamplePlayer();
        AppState.get().remove(this);
        super.onDestroy();
    }

    @Override
    public void onState(AppState.State s) {
        String conn = s.connected ? "已连接 " + s.endpoint : "独立运行（Bridge 未连接）";
        if (statusDot != null) {
            statusDot.setTextColor(s.connected ? 0xFF4ADE80 : 0xFFFBBF24);
        }
        status.setText("连接：" + conn
                + "\n状态：" + s.state + (s.label.isEmpty() ? "" : " · " + s.label)
                + "\n会话：" + (s.sessionId.isEmpty() ? "-" : s.sessionId)
                + "  |  外屏 Display " + BridgeService.coverDisplayId(this));
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}
