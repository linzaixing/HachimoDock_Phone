package com.example.hachimophone;

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;

/**
 * Bridge 的实时对话结果通道。
 * 状态走 {@link AppState}，对话回复单独走这里，避免和 Agent 状态混在一起。
 */
final class ChatBus {

    static final class Reply {
        /** text / audio_start / audio_chunk / audio_end / full */
        final String kind;
        final long id;
        final boolean ok;
        final String text;
        /** 豆包 TTS 返回的 PCM（base64）；为空时由手机系统 TTS 念 */
        final String audio;
        /** 千问 TTS 返回的完整 WAV（base64），优先于 audio */
        final String wav;
        /** 流式音频的一段 PCM（base64） */
        final String pcm;
        final int sampleRate;
        final String error;
        /** 音频已经通过流式包播过了，不要再整段播放 */
        final boolean streamed;

        Reply(long id, boolean ok, String text, String audio, int sampleRate, String error) {
            this("full", id, ok, text, audio, "", "", sampleRate, error);
        }

        Reply(long id, boolean ok, String text, String audio, String wav, int sampleRate, String error) {
            this("full", id, ok, text, audio, wav, "", sampleRate, error);
        }

        Reply(String kind, long id, boolean ok, String text, String audio, String wav,
              String pcm, int sampleRate, String error) {
            this(kind, id, ok, text, audio, wav, pcm, sampleRate, error, false);
        }

        Reply(String kind, long id, boolean ok, String text, String audio, String wav,
              String pcm, int sampleRate, String error, boolean streamed) {
            this.kind = kind == null ? "full" : kind;
            this.id = id;
            this.ok = ok;
            this.text = text == null ? "" : text;
            this.audio = audio == null ? "" : audio;
            this.wav = wav == null ? "" : wav;
            this.pcm = pcm == null ? "" : pcm;
            this.sampleRate = sampleRate > 0 ? sampleRate : 16000;
            this.error = error == null ? "" : error;
            this.streamed = streamed;
        }

        static Reply text(long id, String text) {
            return new Reply("text", id, true, text, "", "", "", 16000, "");
        }

        static Reply audioStart(long id, int rate) {
            return new Reply("audio_start", id, true, "", "", "", "", rate, "");
        }

        static Reply audioChunk(long id, String pcm) {
            return new Reply("audio_chunk", id, true, "", "", "", pcm, 0, "");
        }

        static Reply audioEnd(long id) {
            return new Reply("audio_end", id, true, "", "", "", "", 0, "");
        }

        static Reply needCamera(long id, String prompt) {
            return new Reply("need_camera", id, true, prompt, "", "", "", 16000, "");
        }
    }

    interface Listener {
        void onChatReply(Reply reply);
    }

    private static final List<Listener> listeners = new ArrayList<>();
    private static final Handler ui = new Handler(Looper.getMainLooper());

    private ChatBus() {
    }

    static synchronized void add(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    static synchronized void remove(Listener l) {
        listeners.remove(l);
    }

    static void dispatch(Reply reply) {
        final List<Listener> copy;
        synchronized (ChatBus.class) {
            copy = new ArrayList<>(listeners);
        }
        ui.post(() -> {
            for (Listener l : copy) l.onChatReply(reply);
        });
    }

    /** 连接断开时让界面别一直停在「想一想…」 */
    static void dispatchDisconnected() {
        dispatch(new Reply(-1, false, "", "", 0, "Bridge 已断开"));
    }
}
