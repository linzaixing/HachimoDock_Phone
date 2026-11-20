package com.example.hachimophone;

import android.util.Log;

import org.json.JSONObject;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

import java.util.concurrent.TimeUnit;

/**
 * 与 PC Bridge 的 WebSocket 通道：只负责收发 JSON 文本，重连策略由 {@link BridgeService} 控制。
 */
final class BridgeClient {

    private static final String TAG = "HachimoPhone";

    interface Callback {
        void onOpen();

        void onText(JSONObject packet);

        void onClosed(String reason);
    }

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .pingInterval(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build();

    private WebSocket socket;
    private volatile boolean intentionalClose;

    void connect(final String url, final Callback cb) {
        close();
        intentionalClose = false;
        Request request = new Request.Builder().url(url).build();
        socket = HTTP.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                Log.i(TAG, "ws open " + url);
                cb.onOpen();
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                try {
                    cb.onText(new JSONObject(text));
                } catch (Exception e) {
                    Log.w(TAG, "bad packet", e);
                }
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                Log.i(TAG, "ws closed " + code + " " + reason);
                if (!intentionalClose) cb.onClosed(reason);
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                Log.w(TAG, "ws failure " + t.getMessage());
                if (!intentionalClose) cb.onClosed(t.getMessage());
            }
        });
    }

    boolean send(JSONObject packet) {
        WebSocket ws = socket;
        return ws != null && ws.send(packet.toString());
    }

    void close() {
        intentionalClose = true;
        WebSocket ws = socket;
        socket = null;
        if (ws != null) {
            try {
                ws.close(1000, "bye");
            } catch (Exception ignored) {
            }
        }
    }
}
