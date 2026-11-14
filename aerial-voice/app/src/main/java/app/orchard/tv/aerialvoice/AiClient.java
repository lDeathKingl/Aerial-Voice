package app.orchard.tv.aerialvoice;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** GigaChat REST client. Authorization Key is stored only in the TV's Android Keystore-backed SecretStore. */
public final class AiClient {
    private static final String TAG = "AerialVoiceAI";

    public interface Callback {
        void onSuccess(String answer);
        void onError(String message);
        default void onStatus(String message) { }
    }

    private static final String PREFS = "aerial_voice_settings";
    private static final String MODEL_KEY = "gigachat_model";
    private static final String DEFAULT_MODEL = "GigaChat-2";
    private static final String TOKEN_URL = "https://ngw.devices.sberbank.ru:9443/api/v2/oauth";
    private static final String CHAT_URL = "https://api.giga.chat/v1/chat/completions";
    private static final String SCOPE = "GIGACHAT_API_PERS";
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static String cachedAccessToken = "";
    private static long cachedTokenExpirySeconds = 0L;

    private static final String SYSTEM_PROMPT =
            "Ты Aerial Voice — русскоязычный голосовой помощник телевизора Xiaomi. " +
            "Всегда отвечай по-русски, естественно и понятно. " +
            "Учитывай предыдущие сообщения диалога. Пользователь говорит голосом, поэтому распознавание " +
            "может ошибаться: исправляй очевидные фонетические ошибки по контексту, но не выдумывай факты " +
            "и уточняй неоднозначное. Ответы предназначены для озвучивания: избегай Markdown, таблиц, " +
            "длинных списков и декоративных символов, если пользователь явно этого не просит. " +
            "Обычно отвечай кратко, но раскрывай тему подробно по просьбе. " +
            "Не утверждай, что управляешь телевизором, если действие не выполнено локальной командой приложения. " +
            "Если функция не поддерживается, честно объясни ограничение.";

    private AiClient() { }

    public static boolean isConfigured(Context context) {
        try {
            if (SecretStore.read(context).isEmpty()) return false;
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            return !prefs.getString(MODEL_KEY, DEFAULT_MODEL).trim().isEmpty();
        } catch (Exception ignored) {
            return false;
        }
    }

    public static void ask(Context context, JSONArray history, Callback callback) {
        ask(context, history, "", java.util.Collections.emptyList(), callback);
    }

    public static void ask(Context context, JSONArray history, String tvContext,
                           java.util.List<String> longTermMemory, Callback callback) {
        Context app = context.getApplicationContext();
        final String contextSnapshot = tvContext == null ? "" : tvContext.trim();
        final java.util.List<String> memorySnapshot = longTermMemory == null
                ? java.util.Collections.emptyList() : new java.util.ArrayList<>(longTermMemory);
        IO.execute(() -> {
            try {
                callback.onStatus("Проверяю сохранённый ключ GigaChat…");
                String authKey = SecretStore.read(app);
                if (authKey.isEmpty()) throw new IllegalStateException("В Aerial Voice не найден сохранённый Authorization Key GigaChat. Открой «Настройки ИИ» и сохрани ключ.");
                String model = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .getString(MODEL_KEY, DEFAULT_MODEL).trim();
                JSONArray messages = buildMessages(history, contextSnapshot, memorySnapshot);
                Log.i(TAG, "AI request started. model=" + model + ", messages=" + messages.length()
                        + ", memoryFacts=" + memorySnapshot.size() + ", tvContext=" + !contextSnapshot.isEmpty());
                callback.onStatus("Получаю токен GigaChat…");
                String token = getAccessToken(authKey, false);
                String answer;
                try {
                    callback.onStatus("Отправляю вопрос в GigaChat…");
                    answer = sendChat(token, model, messages);
                } catch (ApiException e) {
                    if (e.httpCode != 401) throw e;
                    Log.w(TAG, "Access token rejected; refreshing once.");
                    callback.onStatus("Обновляю токен GigaChat…");
                    token = getAccessToken(authKey, true);
                    callback.onStatus("Повторно отправляю вопрос в GigaChat…");
                    answer = sendChat(token, model, messages);
                }
                Log.i(TAG, "AI response received. characters=" + answer.length());
                callback.onSuccess(answer);
            } catch (Exception e) {
                Log.e(TAG, "AI request failed", e);
                callback.onError(friendlyError(e));
            }
        });
    }

    public static void testConnection(Context context, String modelValue, String suppliedKey, Callback callback) {
        Context app = context.getApplicationContext();
        IO.execute(() -> {
            try {
                String authKey = suppliedKey == null || suppliedKey.trim().isEmpty()
                        ? SecretStore.read(app) : suppliedKey.trim();
                if (authKey.isEmpty()) throw new IllegalStateException("Введи Authorization Key GigaChat.");
                String model = modelValue == null || modelValue.trim().isEmpty()
                        ? DEFAULT_MODEL : modelValue.trim();
                String token = getAccessToken(authKey, true);
                JSONArray messages = new JSONArray();
                messages.put(new JSONObject().put("role", "system").put("content", SYSTEM_PROMPT));
                messages.put(new JSONObject().put("role", "user")
                        .put("content", "Проверь соединение. Ответь одним коротким словом: ГОТОВО."));
                callback.onSuccess("GigaChat подключён. Модель: " + model + ". Ответ: " + sendChat(token, model, messages));
            } catch (Exception e) {
                Log.e(TAG, "GigaChat connection test failed", e);
                callback.onError(friendlyError(e));
            }
        });
    }

    private static JSONArray buildMessages(JSONArray history, String tvContext,
                                           java.util.List<String> longTermMemory) throws Exception {
        JSONArray messages = new JSONArray();
        StringBuilder system = new StringBuilder(SYSTEM_PROMPT);
        if (tvContext != null && !tvContext.trim().isEmpty()) {
            system.append("\n\nКонтекст телевизора (автоматически определённый снимок, может быть неполным): ")
                    .append(tvContext.trim());
        }
        if (longTermMemory != null && !longTermMemory.isEmpty()) {
            system.append("\n\nДолговременная память, явно сохранённая пользователем. Используй только релевантные факты; если факт не относится к вопросу, игнорируй его:\n");
            for (String fact : longTermMemory) {
                if (fact != null && !fact.trim().isEmpty()) system.append("- ").append(fact.trim()).append("\n");
            }
        }
        messages.put(new JSONObject().put("role", "system").put("content", system.toString()));
        final int maxHistory = 30;
        for (int i = Math.max(0, history.length() - maxHistory); i < history.length(); i++) {
            JSONObject item = history.getJSONObject(i);
            String role = item.optString("role", "");
            String content = item.optString("content", "").trim();
            if (("user".equals(role) || "assistant".equals(role)) && !content.isEmpty()) {
                messages.put(new JSONObject().put("role", role).put("content", content));
            }
        }
        return messages;
    }

    /** OAuth access tokens expire after 30 minutes. Keep a small safety margin. */
    private static synchronized String getAccessToken(String authKey, boolean forceRefresh) throws Exception {
        long now = System.currentTimeMillis() / 1000L;
        if (!forceRefresh && cachedAccessToken != null && !cachedAccessToken.isEmpty()
                && cachedTokenExpirySeconds > now + 90L) {
            return cachedAccessToken;
        }

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(TOKEN_URL).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(30000);
            connection.setDoOutput(true);
            connection.setUseCaches(false);
            connection.setRequestProperty("Authorization", "Basic " + authKey.trim());
            connection.setRequestProperty("RqUID", UUID.randomUUID().toString());
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            connection.setRequestProperty("Accept", "application/json");
            byte[] payload = ("scope=" + URLEncoder.encode(SCOPE, "UTF-8")).getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(payload);
                out.flush();
            }

            int code = connection.getResponseCode();
            Log.i(TAG, "OAuth response HTTP " + code);
            String response = readAll(code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream());
            if (code < 200 || code >= 300) {
                throw apiError(code, response, "Не удалось получить OAuth-токен GigaChat");
            }
            JSONObject json = new JSONObject(response);
            String token = json.optString("access_token", "").trim();
            if (token.isEmpty()) throw new IllegalStateException("GigaChat не вернул access_token.");
            long expiry = json.optLong("expires_at", now + 1700L);
            if (expiry <= now) expiry = now + 1700L;
            cachedAccessToken = token;
            cachedTokenExpirySeconds = expiry;
            return token;
        } catch (java.net.SocketTimeoutException e) {
            throw new IllegalStateException("Тайм-аут при авторизации GigaChat. Проверь интернет-соединение телевизора.", e);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String sendChat(String token, String model, JSONArray messages) throws Exception {
        if (model == null || model.trim().isEmpty()) throw new IllegalArgumentException("Не выбрана модель GigaChat.");
        JSONObject body = new JSONObject()
                .put("model", model.trim())
                .put("messages", messages)
                .put("temperature", 0.35);
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(CHAT_URL).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(60000);
            connection.setDoOutput(true);
            connection.setUseCaches(false);
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "AerialVoice-AndroidTV/0.6.0");
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(payload);
                out.flush();
            }

            int code = connection.getResponseCode();
            Log.i(TAG, "Chat completion response HTTP " + code + ", model=" + model.trim());
            String response = readAll(code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream());
            if (code < 200 || code >= 300) {
                Log.w(TAG, "Chat API returned HTTP " + code + "; response body length=" + (response == null ? 0 : response.length()));
                throw apiError(code, response, "Ошибка GigaChat API");
            }

            JSONObject json = new JSONObject(response);
            JSONArray choices = json.optJSONArray("choices");
            if (choices == null || choices.length() == 0) throw new IllegalStateException("GigaChat вернул пустой ответ.");
            JSONObject message = choices.getJSONObject(0).optJSONObject("message");
            if (message == null) throw new IllegalStateException("В ответе GigaChat отсутствует message.");
            String answer = message.optString("content", "").trim();
            if (answer.isEmpty()) throw new IllegalStateException("GigaChat вернул пустой текст.");
            return answer;
        } catch (java.net.SocketTimeoutException e) {
            throw new IllegalStateException("GigaChat долго не отвечает. Проверь интернет-соединение телевизора.", e);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static ApiException apiError(int code, String response, String prefix) {
        String detail = response == null ? "" : response;
        try {
            JSONObject json = new JSONObject(response);
            JSONObject error = json.optJSONObject("error");
            if (error != null) detail = error.optString("message", error.optString("description", response));
            else detail = json.optString("message", json.optString("description", response));
            String errorCode = json.optString("code", "");
            if (!errorCode.isEmpty()) detail = errorCode + ": " + detail;
        } catch (Exception ignored) { }
        return new ApiException(code, prefix + " (HTTP " + code + "): " + shorten(detail, 360));
    }

    private static String friendlyError(Exception e) {
        if (e instanceof javax.net.ssl.SSLHandshakeException
                || e.getCause() instanceof javax.net.ssl.SSLHandshakeException) {
            return "Не удалось проверить сертификат GigaChat. Проверь, что в сборку включён корневой сертификат НУЦ Минцифры и дата/время телевизора выставлены правильно.";
        }
        String message = e.getMessage();
        if (message == null || message.trim().isEmpty()) return e.getClass().getSimpleName();
        if (message.contains("HTTP 401")) return "GigaChat отклонил авторизацию. Проверь Authorization Key и тип доступа проекта.";
        if (message.contains("HTTP 403")) return "GigaChat запретил запрос: возможно, для проекта не активирован доступ к выбранной модели или бесплатный лимит.";
        if (message.contains("HTTP 429")) return "GigaChat временно ограничил частоту запросов. Подожди немного и повтори.";
        return message;
    }

    private static String readAll(InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) out.append(line);
        }
        return out.toString();
    }

    private static String shorten(String value, int max) {
        if (value == null) return "";
        String clean = value.replace('\n', ' ').replace('\r', ' ').trim();
        return clean.length() <= max ? clean : clean.substring(0, max) + "…";
    }

    private static final class ApiException extends Exception {
        final int httpCode;
        ApiException(int httpCode, String message) {
            super(message);
            this.httpCode = httpCode;
        }
    }
}
