package app.orchard.tv.aerialvoice;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

/** Optional TV boot launch. Disabled until the user turns it on in Aerial Voice settings. */
public final class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "AerialVoiceBoot";

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        boolean enabled = context.getSharedPreferences("aerial_voice_settings", Context.MODE_PRIVATE)
                .getBoolean("auto_start_enabled", false);
        if (!enabled) return;
        if (android.os.Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(context)) {
            Log.w(TAG, "Auto-start is enabled but SYSTEM_ALERT_WINDOW permission is not granted.");
            return;
        }
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                Intent launch = new Intent(context, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                context.startActivity(launch);
                Log.i(TAG, "Aerial Voice launched after boot.");
            } catch (Exception e) {
                Log.w(TAG, "Could not launch Aerial Voice after boot.", e);
            }
        }, 2500L);
    }
}
