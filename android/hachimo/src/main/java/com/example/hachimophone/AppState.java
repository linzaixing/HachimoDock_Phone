package com.example.hachimophone;

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;

/**
 * 全局状态：Bridge 推来的 Agent 状态、连接状态、会话列表。
 * 所有监听器回调都在主线程，UI 直接刷新即可。
 */
final class AppState {

    interface Listener {
        void onState(State s);
    }

    static final class State {
        String agent = "";
        String sessionId = "";
        String state = "idle";
        String label = "";
        long ts;
        boolean connected;
        String endpoint = "";
    }

    private static final AppState INSTANCE = new AppState();

    static AppState get() {
        return INSTANCE;
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private final State state = new State();
    /** 每个 agent 的最近状态：多个 agent 同时用时互不覆盖 */
    private final java.util.LinkedHashMap<String, State> agents = new java.util.LinkedHashMap<>();

    private AppState() {
    }

    synchronized void add(Listener l) {
        listeners.add(l);
    }

    synchronized void remove(Listener l) {
        listeners.remove(l);
    }

    synchronized State snapshot() {
        State copy = new State();
        copy.agent = state.agent;
        copy.sessionId = state.sessionId;
        copy.state = state.state;
        copy.label = state.label;
        copy.ts = state.ts;
        copy.connected = state.connected;
        copy.endpoint = state.endpoint;
        return copy;
    }

    void setConnected(boolean connected, String endpoint) {
        synchronized (this) {
            state.connected = connected;
            state.endpoint = endpoint == null ? "" : endpoint;
        }
        fire();
    }

    /** 本地产生的错误（连不上 / 地址非法 / Bridge 拒绝），直接顶到主状态上展示 */
    void setError(String message) {
        synchronized (this) {
            state.agent = "bridge";
            state.sessionId = "";
            state.state = "error";
            state.label = message == null || message.isEmpty() ? "出错" : message;
            state.ts = System.currentTimeMillis();
        }
        fire();
    }

    /** 主状态优先级：等待用户最急，其次是出错，再到正在干活，最后是完成/待机 */
    private static int priority(String st) {
        switch (st == null ? "" : st) {
            case "waiting": return 0;
            case "error": return 1;
            case "tool": return 2;
            case "thinking": return 3;
            case "done": return 4;
            default: return 5;
        }
    }

    /**
     * 从多 agent 缓存里挑主状态。
     * 以前是「谁最后上报谁当主」，codex / Claude / Trae 交替上报时外屏会来回跳。
     */
    private State pickPrimary() {
        State best = null;
        long now = System.currentTimeMillis();
        for (State s : agents.values()) {
            if (s.ts > 0 && now - s.ts > 10 * 60_000L) continue;
            if (best == null) {
                best = s;
                continue;
            }
            int p = priority(s.state);
            int bp = priority(best.state);
            if (p < bp || (p == bp && s.ts > best.ts)) best = s;
        }
        return best;
    }

    /** 把某个 agent 的状态复制成主状态 */
    private void adopt(State src) {
        state.agent = src.agent;
        state.sessionId = src.sessionId;
        state.state = src.state;
        state.label = src.label;
        state.ts = src.ts;
    }

    void apply(State patch) {
        synchronized (this) {
            if (!patch.endpoint.isEmpty()) state.endpoint = patch.endpoint;

            String key = patch.agent == null || patch.agent.isEmpty() ? "agent" : patch.agent;
            agents.remove(key);
            agents.put(key, patch);

            State primary = pickPrimary();
            if (primary != null) adopt(primary);
        }
        fire();
    }

    /** 用服务端的完整快照替换本地缓存（reset / 过期清理后保持一致） */
    void replaceAll(List<State> list) {
        synchronized (this) {
            agents.clear();
            for (State s : list) {
                String key = s.agent == null || s.agent.isEmpty() ? "agent" : s.agent;
                agents.put(key, s);
            }
            State primary = pickPrimary();
            if (primary == null) {
                state.agent = "";
                state.sessionId = "";
                state.state = "idle";
                state.label = "等待 Agent 接入";
                state.ts = System.currentTimeMillis();
            } else {
                adopt(primary);
            }
        }
        fire();
    }

    /** 除当前主状态之外，其它 agent 的最近状态（最近的在前；超过 10 分钟的不再显示） */
    synchronized List<State> others() {
        long now = System.currentTimeMillis();
        List<State> rest = new ArrayList<>();
        for (java.util.Map.Entry<String, State> e : agents.entrySet()) {
            if (e.getKey().equals(state.agent)) continue;
            State s = e.getValue();
            if (s.ts > 0 && now - s.ts > 10 * 60_000L) continue;   // 太久没动静的不占版面
            rest.add(s);
        }
        // List.sort 是 API 24 才有的方法，老机型（Android 6）用 Collections.sort
        java.util.Collections.sort(rest, (a, b) -> Long.compare(b.ts, a.ts));
        return rest;
    }

    private void fire() {
        final State copy = snapshot();
        ui.post(() -> {
            List<Listener> copyListeners;
            synchronized (AppState.this) {
                copyListeners = new ArrayList<>(listeners);
            }
            for (Listener l : copyListeners) l.onState(copy);
        });
    }
}
