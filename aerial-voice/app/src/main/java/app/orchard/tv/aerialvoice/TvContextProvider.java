package app.orchard.tv.aerialvoice;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Process;
import android.os.Build;

import java.util.Locale;

/** Best-effort snapshot of the last foreground TV app and active media session. */
public final class TvContextProvider {
    private TvContextProvider() { }

    public static String snapshot(Context context) {
        String app = foregroundApp(context);
        String media = MediaSessionBridge.currentMedia(context);
        String selectedFilm = FilmSelectionStore.currentFilmContext(context);
        if (app.isEmpty() && media.isEmpty() && selectedFilm.isEmpty()) {
            return "Текущее приложение и медиаинформация не определены. Не утверждай, что знаешь, что сейчас открыто на телевизоре.";
        }
        StringBuilder out = new StringBuilder();
        if (!app.isEmpty()) out.append("Последнее активное приложение телевизора: ").append(app).append(". ");
        if (!media.isEmpty()) out.append(media).append(". ");
        if (!selectedFilm.isEmpty()) out.append(selectedFilm).append(". ");
        out.append("Это снимок системного контекста, он может отставать от текущего экрана. Если пользователь говорит о другом приложении или фильме, уточни.");
        return out.toString().trim();
    }

    public static boolean hasUsageAccess(Context context) {
        try {
            AppOpsManager ops = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            if (ops == null) return false;
            int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(), context.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    private static String foregroundApp(Context context) {
        if (!hasUsageAccess(context)) return "";
        try {
            UsageStatsManager usage = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usage == null) return "";
            long now = System.currentTimeMillis();
            UsageEvents events = usage.queryEvents(now - 24 * 60 * 60 * 1000L, now);
            if (events == null) return "";
            String own = context.getPackageName();
            String candidate = "";
            UsageEvents.Event event = new UsageEvents.Event();
            while (events.hasNextEvent()) {
                events.getNextEvent(event);
                int type = event.getEventType();
                boolean foreground = type == UsageEvents.Event.MOVE_TO_FOREGROUND;
                if (Build.VERSION.SDK_INT >= 29) foreground |= type == UsageEvents.Event.ACTIVITY_RESUMED;
                if (!foreground) continue;
                String pkg = event.getPackageName();
                if (pkg == null || pkg.isEmpty() || pkg.equals(own) || isSystemUi(pkg)) continue;
                candidate = pkg;
            }
            if (candidate.isEmpty()) return "";
            try {
                ApplicationInfo info = context.getPackageManager().getApplicationInfo(candidate, 0);
                CharSequence label = context.getPackageManager().getApplicationLabel(info);
                return label == null ? candidate : label.toString();
            } catch (PackageManager.NameNotFoundException e) {
                return candidate;
            }
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean isSystemUi(String pkg) {
        String p = pkg.toLowerCase(Locale.ROOT);
        return p.equals("com.android.systemui") || p.equals("com.google.android.tvlauncher")
                || p.equals("com.xiaomi.mitv.tvhome") || p.equals("com.xiaomi.mitv.patchwall")
                || p.equals("com.android.settings") || p.equals("com.android.tv.settings");
    }
}
