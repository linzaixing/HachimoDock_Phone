package com.example.hachimophone;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.UserManager;
import android.util.Log;

/** 开机 / 应用更新后自动拉起常驻服务（外屏页面由服务按折叠状态拉起）。 */
public final class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        Log.i("HachimoPhone", "boot receiver " + action);
        // LOCKED_BOOT_COMPLETED 时凭据加密存储尚未解锁，读 SharedPreferences 会抛 IllegalStateException
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            UserManager um = context.getSystemService(UserManager.class);
            if (um != null && !um.isUserUnlocked()) return;
        }
        try {
            if (!BridgeService.autoStart(context)) return;
            // 用户主动点过「断开」，重启后也不该自己连回去
            if (BridgeService.userDisconnected(context)) return;
            BridgeService.start(context);
        } catch (IllegalStateException e) {
            Log.w("HachimoPhone", "boot receiver skipped before unlock: " + e.getMessage());
        }
    }
}
