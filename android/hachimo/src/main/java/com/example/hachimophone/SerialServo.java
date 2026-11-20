package com.example.hachimophone;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.util.Log;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.util.List;

/**
 * ESP32-C3 云台：手机 Type-C 直接接在 ESP32 的 USB 口上（OTG），走 USB CDC 串口。
 *
 * 协议（每 150~300ms 一行，ASCII）：
 *   P&lt;x&gt;,&lt;y&gt;\n   x/y 是 0~1000 的画面归一化坐标（0=左/上，1000=右/下）
 *   L\n            没检测到人，云台回中/待机
 */
final class SerialServo {

    private static final String TAG = "HachimoPhone";
    private static final String ACTION_PERMISSION = "com.example.hachimophone.USB_PERMISSION";
    private static final int BAUD = 115200;

    private final Context ctx;
    private UsbManager usbManager;
    private UsbSerialPort port;
    private boolean receiverRegistered;
    private volatile java.util.concurrent.ExecutorService writeExec;
    private volatile long lastSentAt;
    private volatile boolean lastFound;
    /** 上次弹 USB 授权框的时刻：插着没授权时别每 5 秒弹一次 */
    private volatile long lastRequestAt;
    /** 用户点了「拒绝」的设备：拔掉之前不再反复弹框 */
    private volatile String deniedFor;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_PERMISSION.equals(intent.getAction())) return;
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            Log.i(TAG, "servo usb permission " + granted + " dev=" + (device == null ? "-" : device.getDeviceName()));
            if (!granted && device != null) deniedFor = device.getDeviceName();
            if (granted && device != null) {
                deniedFor = null;
                open(device);
            }
        }
    };

    SerialServo(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    /** 插上/拔掉后都可以反复调用：没有设备就静默失败，不影响语音和视觉 */
    void start() {
        if (port != null) return;
        usbManager = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
        if (usbManager == null) return;
        List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
        if (drivers.isEmpty()) {
            // 大部分手机也不会主动通知，插上后下一次 start() 再找
            deniedFor = null;                        // 已经拔掉了：下次插上重新申请
            return;
        }
        UsbDevice device = drivers.get(0).getDevice();
        if (usbManager.hasPermission(device)) {
            open(device);
            return;
        }
        registerReceiver();
        // 用户点过拒绝：同一台设备拔掉重插前不再打扰
        if (device.getDeviceName().equals(deniedFor)) return;
        if (System.currentTimeMillis() - lastRequestAt < 15000) return;   // 别反复弹授权框
        lastRequestAt = System.currentTimeMillis();
        try {
            // UsbManager.requestPermission 需要往 PendingIntent 写入 EXTRA_DEVICE/GRANTED，
            // Android 12+ 必须用 FLAG_MUTABLE（且配合 setPackage 满足 Android 14+ 安全要求），不能用 IMMUTABLE
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                flags |= PendingIntent.FLAG_MUTABLE;
            }
            PendingIntent pi = PendingIntent.getBroadcast(ctx, 0,
                    new Intent(ACTION_PERMISSION).setPackage(ctx.getPackageName()),
                    flags);
            usbManager.requestPermission(device, pi);
            Log.i(TAG, "servo usb permission requested");
        } catch (Exception e) {
            Log.w(TAG, "servo request permission failed: " + e.getMessage());
        }
    }

    private void open(UsbDevice device) {
        try {
            UsbSerialDriver driver = UsbSerialProber.getDefaultProber()
                    .probeDevice(device);
            if (driver == null) {
                Log.w(TAG, "servo: no serial driver for " + device.getDeviceName());
                return;
            }
            UsbDeviceConnection conn = usbManager.openDevice(device);
            if (conn == null) {
                Log.w(TAG, "servo: openDevice returned null");
                return;
            }
            UsbSerialPort p = driver.getPorts().get(0);
            p.open(conn);
            p.setParameters(BAUD, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            port = p;
            Log.i(TAG, "servo connected " + device.getDeviceName());
        } catch (Exception e) {
            Log.w(TAG, "servo open failed: " + e.getMessage());
            port = null;
        }
    }

    /**
     * @param found 画面里有没有人
     * @param x     0~1
     * @param y     0~1
     */
    void update(boolean found, float x, float y) {
        if (port == null) return;
        long now = System.currentTimeMillis();
        if (now - lastSentAt < 140) return;          // 云台不需要更高频率
        lastSentAt = now;
        if (!found) {
            if (lastFound) {
                lastFound = false;
                write("L\n");
            }
            return;
        }
        lastFound = true;
        int px = Math.round(clamp01(x) * 1000);
        int py = Math.round(clamp01(y) * 1000);
        write("P" + px + "," + py + "\n");
    }

    private void write(String s) {
        if (port == null) return;
        java.util.concurrent.ExecutorService exec = writeExec;
        if (exec == null || exec.isShutdown()) {
            exec = java.util.concurrent.Executors.newSingleThreadExecutor();
            writeExec = exec;
        }
        try {
            exec.execute(() -> {
                UsbSerialPort p = port;
                if (p == null) return;
                try {
                    p.write(s.getBytes("UTF-8"), 500);
                } catch (Exception e) {
                    Log.w(TAG, "servo write failed: " + e.getMessage());
                    close();
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
        }
    }

    /** 串口是否已经打开（给调试页判断用） */
    boolean isOpen() {
        return port != null;
    }

    private static float clamp01(float v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    void stop() {
        close();
        java.util.concurrent.ExecutorService exec = writeExec;
        writeExec = null;
        if (exec != null) exec.shutdownNow();
        if (receiverRegistered) {
            try {
                ctx.unregisterReceiver(receiver);
            } catch (Exception ignored) {
            }
            receiverRegistered = false;
        }
    }

    private void close() {
        UsbSerialPort p = port;
        port = null;
        if (p != null) {
            try {
                p.close();
            } catch (Exception ignored) {
            }
        }
    }

    private void registerReceiver() {
        if (receiverRegistered) return;
        IntentFilter filter = new IntentFilter(ACTION_PERMISSION);
        try {
            androidx.core.content.ContextCompat.registerReceiver(
                    ctx, receiver, filter,
                    androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
            receiverRegistered = true;
        } catch (Exception e) {
            Log.w(TAG, "servo register receiver failed: " + e.getMessage());
        }
    }
}
