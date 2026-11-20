package com.k2fsa.sherpa.onnx;

/** 只保留 Android 需要的加载逻辑：从 jniLibs 里加载 libsherpa-onnx-jni.so。 */
public class LibraryLoader {
    private static volatile boolean loaded = false;

    private LibraryLoader() {
    }

    static synchronized void maybeLoad() {
        if (loaded) return;
        System.loadLibrary("sherpa-onnx-jni");
        loaded = true;
    }
}
