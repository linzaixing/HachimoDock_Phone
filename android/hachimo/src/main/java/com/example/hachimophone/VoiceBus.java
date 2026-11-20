package com.example.hachimophone;

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;

/**
 * PC 端语音识别的结果通道（手机只录音，识别在 PC 上做）。
 */
final class VoiceBus {

    static final class Result {
        final long id;
        final boolean ok;
        final String text;
        final String error;

        Result(long id, boolean ok, String text, String error) {
            this.id = id;
            this.ok = ok;
            this.text = text == null ? "" : text;
            this.error = error == null ? "" : error;
        }
    }

    interface Listener {
        void onVoiceText(Result result);
    }

    private static final List<Listener> listeners = new ArrayList<>();
    private static final Handler ui = new Handler(Looper.getMainLooper());

    private VoiceBus() {
    }

    static synchronized void add(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    static synchronized void remove(Listener l) {
        listeners.remove(l);
    }

    static void dispatch(Result result) {
        final List<Listener> copy;
        synchronized (VoiceBus.class) {
            copy = new ArrayList<>(listeners);
        }
        ui.post(() -> {
            for (Listener l : copy) l.onVoiceText(result);
        });
    }
}
