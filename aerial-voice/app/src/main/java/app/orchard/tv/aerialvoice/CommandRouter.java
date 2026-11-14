package app.orchard.tv.aerialvoice;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.provider.Settings;
import android.view.KeyEvent;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Local, deterministic TV commands. Anything else may be sent to the configured AI endpoint. */
public final class CommandRouter {
    public static final class Result {
        public final boolean handled;
        public final String message;
        public Result(boolean handled, String message) { this.handled = handled; this.message = message; }
    }

    private static final Map<String, String> APPS = new LinkedHashMap<>();
    static {
        APPS.put("зона", "mobi.zona");
        APPS.put("zona", "mobi.zona");
        APPS.put("торрсерв", "ru.yourok.torrserve");
        APPS.put("torrserve", "ru.yourok.torrserve");
        APPS.put("торрент сервер", "ru.yourok.torrserve");
        APPS.put("лампа", "top.rootu.lampa");
        APPS.put("lampa", "top.rootu.lampa");
        APPS.put("iptv pro", "ru.iptvremote.android.iptv.pro");
        APPS.put("айпитиви про", "ru.iptvremote.android.iptv.pro");
        APPS.put("ай пи ти ви про", "ru.iptvremote.android.iptv.pro");
        APPS.put("кинопоиск", "ru.kinopoisk.tv");
        APPS.put("happ", "su.happ.proxyutility");
        APPS.put("хапп", "su.happ.proxyutility");
        APPS.put("leankeyboard", "org.liskovsoft.androidtv.rukeyboard");
        APPS.put("lean keyboard", "org.liskovsoft.androidtv.rukeyboard");
        APPS.put("лиан клавиатура", "org.liskovsoft.androidtv.rukeyboard");
        APPS.put("adb appcontrol", "com.cybercat.adbappcontrol.tv");
        APPS.put("adb app control", "com.cybercat.adbappcontrol.tv");
        APPS.put("адб аппконтрол", "com.cybercat.adbappcontrol.tv");
        APPS.put("smarttv client", "com.fgl27.twitch");
        APPS.put("smart tv client", "com.fgl27.twitch");
        APPS.put("клиент для твича", "com.fgl27.twitch");
        APPS.put("twitch", "com.fgl27.twitch");
        APPS.put("твич", "com.fgl27.twitch");
        APPS.put("tv bro", "com.phlox.tvwebbrowser");
        APPS.put("тв бро", "com.phlox.tvwebbrowser");
        APPS.put("браузер", "com.phlox.tvwebbrowser");
        APPS.put("vk tv", "com.vk.tv");
        APPS.put("вк тв", "com.vk.tv");
        APPS.put("вк видео", "com.vk.tv");
        APPS.put("rustore", "ru.vk.store.tv");
        APPS.put("ру стор", "ru.vk.store.tv");
        APPS.put("рустор", "ru.vk.store.tv");
        APPS.put("aerial korobovo", "app.orchard.tv.korobovo");
        APPS.put("aerial", "app.orchard.tv.korobovo");
        APPS.put("эриал", "app.orchard.tv.korobovo");
        APPS.put("лаунчер", "app.orchard.tv.korobovo");
        APPS.put("vlc", "org.videolan.vlc");
    }

    private CommandRouter() { }

    public static Result execute(Context context, String spoken) {
        String q = normalize(spoken);
        if (q.isEmpty()) return new Result(false, "Не удалось разобрать команду.");

        if (containsAny(q, "громче", "увеличь громкость", "прибавь звук")) {
            AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (audio != null) audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
            return new Result(true, "Делаю громче.");
        }
        if (containsAny(q, "тише", "уменьши громкость", "убавь звук")) {
            AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (audio != null) audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
            return new Result(true, "Делаю тише.");
        }
        if (containsAny(q, "выключи звук", "без звука", "отключи звук")) {
            AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (audio != null && android.os.Build.VERSION.SDK_INT >= 23) audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI);
            return new Result(true, "Звук выключен.");
        }
        if (containsAny(q, "включи звук", "верни звук", "со звуком")) {
            AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (audio != null && android.os.Build.VERSION.SDK_INT >= 23) audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI);
            return new Result(true, "Звук включён.");
        }
        if (containsAny(q, "пауза", "поставь на паузу", "останови воспроизведение")) {
            dispatchMedia(context, KeyEvent.KEYCODE_MEDIA_PAUSE);
            return new Result(true, "Пауза.");
        }
        if (containsAny(q, "продолжи", "возобнови", "продолжить воспроизведение", "играй")) {
            dispatchMedia(context, KeyEvent.KEYCODE_MEDIA_PLAY);
            return new Result(true, "Продолжаю воспроизведение.");
        }
        if (containsAny(q, "домой", "на главный экран", "главный экран")) {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { context.startActivity(home); } catch (Exception ignored) { }
            return new Result(true, "Открываю главный экран.");
        }
        if (containsAny(q, "назад", "вернись назад", "вернуться назад")) {
            return new Result(true, "Нажми кнопку Back на пульте — глобальный Back без отдельного системного разрешения недоступен.");
        }
        if (containsAny(q, "настройки телевизора", "открой настройки", "открой настройки тв")) {
            try {
                Intent settings = new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(settings);
                return new Result(true, "Открываю настройки.");
            } catch (ActivityNotFoundException e) {
                return new Result(true, "На телевизоре не найден экран настроек Android.");
            }
        }

        String appKey = null;
        for (String alias : APPS.keySet()) {
            if (q.contains(alias)) { appKey = alias; break; }
        }
        if (appKey != null && (q.contains("открой") || q.contains("запусти") || q.contains("включи") || q.contains("перейди") || q.equals(appKey))) {
            String pkg = APPS.get(appKey);
            Intent launch = context.getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch == null && "ru.vk.store.tv".equals(pkg)) launch = context.getPackageManager().getLaunchIntentForPackage("ru.vk.store");
            if (launch == null) return new Result(true, "Приложение «" + appKey + "» не установлено или не имеет доступного экрана запуска.");
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                context.startActivity(launch);
                return new Result(true, "Открываю " + appKey + ".");
            } catch (Exception e) {
                return new Result(true, "Не удалось открыть " + appKey + ".");
            }
        }
        return new Result(false, "");
    }

    private static void dispatchMedia(Context context, int code) {
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audio == null) return;
        long now = android.os.SystemClock.uptimeMillis();
        audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0));
        audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0));
    }

    private static boolean containsAny(String text, String... values) {
        for (String value : values) if (text.contains(value)) return true;
        return false;
    }

    private static String normalize(String text) {
        if (text == null) return "";
        return text.toLowerCase(Locale.ROOT).replace('ё', 'е').replaceAll("[^\\p{L}\\p{N} ]", " ").replaceAll("\\s+", " ").trim();
    }
}
