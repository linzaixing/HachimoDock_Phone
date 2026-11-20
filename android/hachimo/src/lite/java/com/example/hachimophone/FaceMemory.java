package com.example.hachimophone;

import android.content.Context;

/**
 * 轻量版占位：lite 包不含 ArcFace + ONNX Runtime（节省约 28MB APK 体积），
 * 如需离线人脸建档认人请安装 full 版；lite 版仍支持系统自带人脸跟踪与云端 VLM 看图。
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
