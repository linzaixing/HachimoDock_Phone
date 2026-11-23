package com.example.hachimophone;

import android.content.Context;

/**
 * 轻量视觉占位：不含离线人脸库，仍支持系统自带人脸跟踪与云端 VLM 看图。
 */
final class FaceMemory {

    FaceMemory(Context ctx) {
    }

    boolean isReady() {
        return false;
    }

    String currentName() {
        return "";
    }

    float[] lastEmbedding() {
        return null;
    }

    boolean remember(String name, float[] v) {
        return false;
    }

    void close() {
    }
}
