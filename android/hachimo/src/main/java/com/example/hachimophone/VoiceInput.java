package com.example.hachimophone;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;

/**
 * 用手机自带的语音识别做「说一句话 → 文字」。
 * <p>
 * 不依赖 PC，也不需要 API Key；能不能用取决于手机是否装了语音识别服务
 * （{@link SpeechRecognizer#isRecognitionAvailable(Context)}）。
 * 所有回调都在主线程，界面可以直接改。
 */
final class VoiceInput {

    interface Listener {
        /** 边说边出的临时结果，可能多次回调 */
        void onPartial(String text);

        /** 最终结果 */
        void onFinal(String text);

        /** 识别失败；用户主动取消时不会回调 */
        void onError(int error, String message);
    }

    /** 这些错误说明本机识别在这台机器上根本用不了，应该改走 PC 识别 */
    static boolean needsPc(int error) {
        return error == SpeechRecognizer.ERROR_NETWORK
                || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT
                || error == SpeechRecognizer.ERROR_SERVER
                || error == ERROR_LANGUAGE_UNAVAILABLE;
    }

    /** Android 12 才有这个常量（语言离线包没下载） */
    private static final int ERROR_LANGUAGE_UNAVAILABLE = 12;

    private SpeechRecognizer recognizer;
    private Listener listener;
    private boolean active;

    static boolean available(Context c) {
        try {
            return SpeechRecognizer.isRecognitionAvailable(c);
        } catch (Exception e) {
            return false;
        }
    }

    boolean isActive() {
        return active;
    }

    /** 开始收音；返回 false 表示本机不支持或启动失败 */
    boolean start(Context ctx, Listener l) {
        if (!available(ctx)) return false;
        cancel();

        final SpeechRecognizer rec = SpeechRecognizer.createSpeechRecognizer(ctx.getApplicationContext());
        recognizer = rec;
        listener = l;
        active = true;

        rec.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {
            }

            @Override
            public void onBeginningOfSpeech() {
            }

            @Override
            public void onRmsChanged(float rmsdB) {
            }

            @Override
            public void onBufferReceived(byte[] buffer) {
            }

            @Override
            public void onEndOfSpeech() {
            }

            @Override
            public void onError(int error) {
                if (!active) return;
                Listener l2 = listener;
                String message = messageFor(error);
                cleanup();
                // ERROR_CLIENT 是用户自己取消/打断，不打扰界面
                if (message != null && l2 != null) l2.onError(error, message);
            }

            @Override
            public void onResults(Bundle results) {
                if (!active) return;
                Listener l2 = listener;
                String text = best(results);
                cleanup();
                if (l2 == null) return;
                if (text.isEmpty()) l2.onError(SpeechRecognizer.ERROR_NO_MATCH, "没听清，再说一次");
                else l2.onFinal(text);
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
                if (!active || listener == null) return;
                String text = best(partialResults);
                if (!text.isEmpty()) listener.onPartial(text);
            }

            @Override
            public void onEvent(int eventType, Bundle params) {
            }
        });

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN");
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.getPackageName());
        // 走设备端识别：Google 在线识别在国内连不上，只能指望本地模型
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        // 说完停顿一会儿就自动结束，手机上比「长按」好用
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1100);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1100);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 800);

        try {
            rec.startListening(intent);
        } catch (Exception e) {
            cleanup();
            return false;
        }
        return true;
    }

    /** 提前结束收音，等最终结果回调 */
    void stop() {
        SpeechRecognizer rec = recognizer;
        if (rec == null || !active) return;
        try {
            rec.stopListening();
        } catch (Exception ignored) {
        }
    }

    /** 放弃本次收音，不再回调 */
    void cancel() {
        cleanup();
    }

    private void cleanup() {
        active = false;
        listener = null;
        SpeechRecognizer rec = recognizer;
        recognizer = null;
        if (rec != null) {
            try {
                rec.cancel();
            } catch (Exception ignored) {
            }
            try {
                rec.destroy();
            } catch (Exception ignored) {
            }
        }
    }

    private static String best(Bundle results) {
        if (results == null) return "";
        ArrayList<String> list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (list == null || list.isEmpty()) return "";
        String text = list.get(0);
        return text == null ? "" : text.trim();
    }

    private static String messageFor(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_NO_MATCH:
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return "没听清，再说一次";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return "需要麦克风权限，请在内屏 App 里允许";
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                return "语音服务网络异常";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return "识别器忙，稍后再试";
            case SpeechRecognizer.ERROR_SERVER:
                return "语音服务不可用";
            case ERROR_LANGUAGE_UNAVAILABLE:
                return "本机缺少中文离线语音包";
            case SpeechRecognizer.ERROR_CLIENT:
                return null;
            default:
                return "语音识别失败（" + error + "）";
        }
    }
}
