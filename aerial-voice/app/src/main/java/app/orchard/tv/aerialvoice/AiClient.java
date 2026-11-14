package app.orchard.tv.aerialvoice;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** OpenAI-compatible Chat Completions client. No API key is bundled with the application. */
public final class AiClient {
    public interface Callback {
        void onSuccess(String answer);
        void onError(String message);
    }

    private static final String PREFS = "aerial_voice_settings";
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final String DEFAULT_ENDPOINT = "https://api.openai.com/v1/chat/completions";
    private static final String DEFAULT_MODEL = "gpt-4o-mini";
    private static final String SYSTEM_PROMPT =
            "Ты Aerial Voice — русскоязычный голосовой помощник телевизора Xiaomi. " +
            "Всегда отвечай только на русском языке. Говори естественно, понятно и по делу. " +
            "Помни предыдущие сообщения диалога и используй их как контекст. " +
            "Пользователь общается голосом: распознавание иногда ошибается, поэтому исправляй очевидные " +
            "фонетические ошибки по смыслу, но не выдумывай недостающие факты и уточняй неоднозначное. " +
            "Ответы предназначены для озвучивания: не используй Markdown, таблицы, длинные списки и " +
            "декоративные символы, если пользователь явно этого не просит. Обычно отвечай кратко, " +
            "но раскрывай тему подробно, когда об этом просят. " +
            "Не утверждай, что управляешь телевизором, если действие не выполнено локальной командой приложения. " +
            "Если функция не поддерживается, честно объясни ограничение.";

    private AiClient() { }

    public static boolean isConfigured(Context context) {
        try {
            if (SecretStore.read(context).isEmpty()) return false;
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            return !prefs.getString("endpoint", DEFAULT_ENDPOINT).trim().isEmpty()
                    && !prefs.getString("model", DEFAULT_MODEL).trim().isEmpty();
        } catch (Exception ignored) { return false; }
    }

    public static void ask(Context context, JSONArray history, Callback callback) {
        Context app = context.getApplicationContext();
        IO.execute(() -> {
            try {
                String key = SecretStore.read(app);
                if (key.isEmpty()) throw new IllegalStateException("ИИ не настроен. Добавь API-ключ в настройках Aerial Voice.");
                SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                JSONArray messages = new JSONArray();
                messages.put(new JSONObject().put("role", "system").put("content", SYSTEM_PROMPT));
                for (int i = Math.max(0, history.length() - 12); i < history.length(); i++) {
                    JSONObject item = history.getJSONObject(i);
                    String role = item.optString("role", "");
                    String content = item.optString("content", "").trim();
                    if (("user".equals(role) || "assistant".equals(role)) && !content.isEmpty()) {
                        messages.put(new JSONObject().put("role", role).put("content", content));
                    }
                }
                callback.onSuccess(send(prefs.getString("endpoint", DEFAULT_ENDPOINT),
                        prefs.getString("model", DEFAULT_MODEL), key, messages));
            } catch (Exception e) {
                callback.onError(safeMessage(e));
            }
        });
    }

    public static void testConnection(Context context, String endpoint, String model, String suppliedKey, Callback callback) {
        Context app = context.getApplicationContext();
        IO.execute(() -> {
            try {
                String key = suppliedKey == null || suppliedKey.trim().isEmpty()
                        ? SecretStore.read(app) : suppliedKey.trim();
                if (key.isEmpty()) throw new IllegalStateException("Сначала введи API-ключ.");
                if (endpoint == null || endpoint.trim().isEmpty()) throw new IllegalStateException("Укажи API endpoint.");
                if (model == null || model.trim().isEmpty()) throw new IllegalStateException("Укажи название модели.");
                JSONArray messages = new JSONArray();
                messages.put(new JSONObject().put("role", "system").put("content", SYSTEM_PROMPT));
                messages.put(new JSONObject().put("role", "user").put("content", "Проверь соединение. Ответь одним коротким словом: ГОТОВО."));
                callback.onSuccess("Соединение успешно. Ответ модели: " + send(endpoint, model, key, messages));
            } catch (Exception e) {
                callback.onError(safeMessage(e));
            }
        });
    }

    private static String send(String endpointValue, String modelValue, String key, JSONArray messages) throws Exception {
        String endpoint = normalizeEndpoint(endpointValue);
        String model = modelValue == null ? "" : modelValue.trim();
        if (model.isEmpty()) throw new IllegalArgumentException("Не указана модель ИИ.");
        JSONObject body = new JSONObject().put("model", model).put("messages", messages).put("temperature", 0.35);
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(45000);
            connection.setDoOutput(true);
            connection.setUseCaches(false);
            connection.setRequestProperty("Authorization", "Bearer " + key);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "AerialVoice-AndroidTV/0.4.0");
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream out = connection.getOutputStream()) { out.write(payload); out.flush(); }
            int code = connection.getResponseCode();
            InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
            String response = readAll(stream);
            if (code < 200 || code >= 300) {
                String detail = response;
                try {
                    JSONObject error = new JSONObject(response).optJSONObject("error");
                    if (error != null) detail = error.optString("message", response);
                } catch (Exception ignored) { }
                throw new IllegalStateException("ИИ-сервер вернул HTTP " + code + ": " + shorten(detail, 320));
            }
            JSONObject json = new JSONObject(response);
            JSONArray choices = json.optJSONArray("choices");
            if (choices == null || choices.length() == 0) throw new IllegalStateException("ИИ вернул пустой ответ.");
            JSONObject message = choices.getJSONObject(0).optJSONObject("message");
            if (message == null) throw new IllegalStateException("В ответе ИИ отсутствует message.");
            String answer = extractContent(message.opt("content")).trim();
            if (answer.isEmpty()) throw new IllegalStateException("ИИ вернул пустой текст.");
            return answer;
        } catch (java.net.SocketTimeoutException e) {
            throw new IllegalStateException("Истекло время ожидания ответа ИИ. Проверь интернет или VPN.", e);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String extractContent(Object content) {
        if (content instanceof String) return (String) content;
        if (content instanceof JSONArray) {
            JSONArray parts = (JSONArray) content;
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < parts.length(); i++) {
                JSONObject part = parts.optJSONObject(i);
                if (part == null) continue;
                String text = part.optString("text", "").trim();
                if (!text.isEmpty()) { if (out.length() > 0) out.append(' '); out.append(text); }
            }
            return out.toString();
        }
        return "";
    }

    private static String normalizeEndpoint(String value) {
        String endpoint = value == null ? "" : value.trim();
        if (endpoint.isEmpty()) endpoint = DEFAULT_ENDPOINT;
        while (endpoint.endsWith("/")) endpoint = endpoint.substring(0, endpoint.length() - 1);
        if (endpoint.endsWith("/chat/completions")) return endpoint;
        if (endpoint.endsWith("/v1")) return endpoint + "/chat/completions";
        return endpoint + "/v1/chat/completions";
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

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.trim().isEmpty() ? e.getClass().getSimpleName() : message;
    }

    private static String shorten(String value, int max) {
        if (value == null) return "";
        String clean = value.replace('\n', ' ').replace('\r', ' ').trim();
        return clean.length() <= max ? clean : clean.substring(0, max) + "…";
    }
}
