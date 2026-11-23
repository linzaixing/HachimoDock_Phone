# sherpa-onnx C++ JNI 反射/原生绑定必须完整保留
-keep class com.k2fsa.sherpa.onnx.** { *; }

# usb-serial-for-android 驱动枚举反射
-keep class com.hoho.android.usbserial.** { *; }

# CameraX 反射初始化类
-keep class androidx.camera.** { *; }

# OkHttp3 可选平台/Conscrypt 依赖忽略告警
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
