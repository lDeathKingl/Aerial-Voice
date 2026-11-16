package app.orchard.tv.aerialvoice;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.provider.Settings;
import android.view.KeyEvent;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

        Matcher volumeMatch = Pattern.compile("(?:громкость|звук)\\s*(?:на\\s*)?(\\d{1,3})").matcher(q);
        if (volumeMatch.find()) {
            int percent;
            try { percent = Integer.parseInt(volumeMatch.group(1)); }
            catch (NumberFormatException e) { percent = -1; }
            if (percent >= 0 && percent <= 100) {
                AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                if (audio != null) {
                    int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                    int target = Math.round(max * percent / 100.0f);
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI);
                    return new Result(true, "Устанавливаю громкость на " + percent + " процентов.");
                }
            }
        }

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
        // Phrases such as "найди в Зоне фильм Дюна" or "найди Дюну в Zona".
        Matcher zonaFirst = Pattern.compile(
                "^(?:найди|поищи|покажи|поиск)\\s+(?:в\\s+)?(?:зоне|zona)\\s+(?:фильм\\s+|сериал\\s+)?(.+)$")
                .matcher(q);
        if (zonaFirst.find()) {
            String title = zonaFirst.group(1).trim();
            if (!title.isEmpty()) return searchFilm(context, title, true);
        }
        Matcher zonaLast = Pattern.compile(
                "^(?:найди|поищи|покажи|поиск)\\s+(?:фильм\\s+|сериал\\s+)?(.+?)\\s+в\\s+(?:зоне|zona)$")
                .matcher(q);
        if (zonaLast.find()) {
            String title = zonaLast.group(1).trim();
            if (!title.isEmpty()) return searchFilm(context, title, true);
        }
        Matcher search = Pattern.compile(
                "^(?:найди фильм|найди сериал|поищи фильм|поищи сериал|поищи|найди|поиск)\\s+(.+)$")
                .matcher(q);
        if (search.find()) {
            String title = search.group(1).trim();
            if (!title.isEmpty()) return searchFilm(context, title, false);
        }

        Matcher hdmi = Pattern.compile("(?:открой|включи|переключи(?:сь)?(?: на)?|переключи источник на)\\s*(?:hdmi|хдми)\\s*(\\d+)").matcher(q);
        if (hdmi.find()) {
            String input = "hdmi" + hdmi.group(1);
            if (context instanceof Activity && TvInputController.openByQuery((Activity) context, input)) {
                return new Result(true, "Переключаю на " + hdmi.group(0).replace("переключи", "").trim() + ".");
            }
            return new Result(true, "Не нашёл такой HDMI-вход среди источников, которые Android TV предоставляет приложениям.");
        }
        if (containsAny(q, "переключи источник", "открой источники", "покажи источники", "выбери источник", "список входов", "hdmi", "хдми")) {
            if (context instanceof Activity) {
                TvInputController.showPicker((Activity) context);
                return new Result(true, "Открываю список доступных HDMI и TV-входов.");
            }
            return new Result(true, "Выбор источника доступен только на экране Aerial Voice.");
        }

        if (containsAny(q, "пауза", "поставь на паузу", "останови воспроизведение")) {
            dispatchMedia(context, KeyEvent.KEYCODE_MEDIA_PAUSE);
            return new Result(true, "Пауза.");
        }
        if (containsAny(q, "следующий канал", "следующее видео", "следующий трек", "следующий фильм")) {
            dispatchMedia(context, KeyEvent.KEYCODE_MEDIA_NEXT);
            return new Result(true, "Переключаю на следующее.");
        }
        if (containsAny(q, "предыдущий канал", "предыдущее видео", "предыдущий трек")) {
            dispatchMedia(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS);
            return new Result(true, "Переключаю на предыдущее.");
        }
        if (containsAny(q, "перемотай вперед", "быстрее")) {
            dispatchMedia(context, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD);
            return new Result(true, "Перематываю вперёд.");
        }
        if (containsAny(q, "перемотай назад", "назад на минуту")) {
            dispatchMedia(context, KeyEvent.KEYCODE_MEDIA_REWIND);
            return new Result(true, "Перематываю назад.");
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

    private static Result searchFilm(Context context, String title, boolean onlyZona) {
        ZonaSearch.Result searched = ZonaSearch.search(context, title, onlyZona);
        return new Result(true, searched.message);
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
