package app.orchard.tv.aerialvoice;

import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import java.util.Locale;

/**
 * Zona 3.x integration without hardcoded private Activity names.
 * ACTION_SEARCH works only when the installed Zona version exports a searchable Activity.
 */
public final class ZonaSearch {
    private static final String[] ZONA_PACKAGES = {"mobi.zona", "ru.zona"};
    private static final String[] OTHER_PLAYERS = {"top.rootu.lampa", "ru.kinopoisk.tv"};

    private ZonaSearch() { }

    public static final class Result {
        public final boolean opened;
        public final boolean searchSent;
        public final String message;
        Result(boolean opened, boolean searchSent, String message) {
            this.opened = opened;
            this.searchSent = searchSent;
            this.message = message;
        }
    }

    public static Result search(Context context, String rawTitle, boolean onlyZona) {
        String title = rawTitle == null ? "" : rawTitle.trim();
        if (title.isEmpty()) return new Result(false, false, "Название фильма не распознано.");

        // First attempt an exported search intent, not an undocumented deep link.
        for (String pkg : ZONA_PACKAGES) {
            Result sent = trySearchIntent(context, pkg, title, "Zona");
            if (sent != null) return sent;
        }
        if (!onlyZona) {
            for (String pkg : OTHER_PLAYERS) {
                Result sent = trySearchIntent(context, pkg, title, "кинотеатр");
                if (sent != null) return sent;
            }
        }

        // If no exported search handler exists, open Zona but DO NOT report a successful search.
        for (String pkg : ZONA_PACKAGES) {
            Result opened = tryLaunch(context, pkg, title, "Zona");
            if (opened != null) return opened;
        }
        if (!onlyZona) {
            for (String pkg : OTHER_PLAYERS) {
                Result opened = tryLaunch(context, pkg, title, "кинотеатр");
                if (opened != null) return opened;
            }
        }
        return new Result(false, false, onlyZona
                ? "Zona не найдена на телевизоре. Проверь установленное приложение."
                : "Не найдены Zona, Lampa или Кинопоиск.");
    }

    private static Result trySearchIntent(Context context, String pkg, String title, String display) {
        try {
            Intent intent = new Intent(Intent.ACTION_SEARCH)
                    .setPackage(pkg)
                    .putExtra(SearchManager.QUERY, title)
                    .putExtra(Intent.EXTRA_TEXT, title)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (intent.resolveActivity(context.getPackageManager()) == null) return null;
            context.startActivity(intent);
            return new Result(true, true,
                    "Отправил запрос «" + title + "» в " + display
                    + ". Если приложение проигнорировало запрос, открой его поиск вручную.");
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Result tryLaunch(Context context, String pkg, String title, String display) {
        try {
            Intent intent = context.getPackageManager().getLaunchIntentForPackage(pkg);
            if (intent == null) return null;
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return new Result(true, false, "Открыл " + display
                    + ", но прямой поиск «" + title
                    + "» эта установленная версия не предоставляет через Android. Выбери раздел «Поиск».");
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public static String diagnostics(Context context) {
        PackageManager pm = context.getPackageManager();
        for (String pkg : ZONA_PACKAGES) {
            try {
                PackageInfo info = pm.getPackageInfo(pkg, 0);
                Intent intent = new Intent(Intent.ACTION_SEARCH).setPackage(pkg);
                boolean exportedSearch = intent.resolveActivity(pm) != null;
                String version = info.versionName == null ? "неизвестна" : info.versionName;
                return pkg + " / " + version + "; ACTION_SEARCH=" + exportedSearch
                        + (version.equals("3.0.79") ? " (целевая версия)" : " (ожидалась 3.0.79)");
            } catch (PackageManager.NameNotFoundException ignored) { }
        }
        return "не установлена или не видна PackageManager";
    }
}
