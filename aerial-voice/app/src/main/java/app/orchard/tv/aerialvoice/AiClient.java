package app.orchard.tv.aerialvoice;

import android.content.Context;

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

/** OpenAI-compatible Chat Completions client. No key is bundled with the application. */
public final class AiClient {
    public interface Callback {
        void onSuccess(String answer);
        void onError(String message);
    }

    private static final String PREFS = "aerial_voice_settings";
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final String SYSTEM_PROMPT =
            "Ты Aerial Voice — голосовой помощник телевизора. Всегда отвечай только на русском языке. " +
            "Отвечай естественно, понятно и обычно кратко. Учитывай предыдущие сообщения диалога. " +
            "Не утверждай, что управляешь телевизором, если действие не выполнено локальной командой приложения. " +
            "Если пользователь просит действие, которое приложение пока не поддерживает, честно объясни ограничение.";

    private AiClient() { }

    public static boolean isConfigured(Context context) {
        try { return !SecretStore.read(context).isEmpty(); }
        catch (Exception ignored) { return false; }
    }

    public static void ask(Context context, JSONArray history, Callback callback) {
        Context app = context.getApplicationContext();
        IO.execute(() -> {
            HttpURLConnection connection = null;
            try {
                String key = SecretStore.read(app);
                if (key.isEmpty()) throw new IllegalStateException("ИИ не настроен. Добавь API-ключ в настройках Aerial Voice.");
                android.content.SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                String endpoint = normalizeEndpoint(prefs.getString("endpoint", "https://api.openai.com/v1/chat/completions"));
                String model = prefs.getString("model", "gpt-4o-mini").trim();
                if (model.isEmpty()) model = "gpt-4o-mini";

                JSONArray messages = new JSONArray();
                messages.put(new JSONObject().put("role", "system").put("content", SYSTEM_PROMPT));
                for (int i = Math.max(0, history.length() - 12); i < history.length(); i++) {
                    messages.put(history.getJSONObject(i));
                }
                JSONObject body = new JSONObject()
                        .put("model", model)
                        .put("messages", messages)
                        .put("temperature", 0.4);

                connection = (HttpURLConnection) new URL(endpoint).openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(30000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Authorization", "Bearer " + key);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setRequestProperty("Accept", "application/json");
                byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(payload.length);
                try (OutputStream out = connection.getOutputStream()) { out.write(payload); }

                int code = connection.getResponseCode();
                InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
                String response = readAll(stream);
                if (code < 200 || code >= 300) {
                    String detail = response;
                    try {
                        JSONObject error = new JSONObject(response).optJSONObject("error");
                        if (error != null) detail = error.optString("message", response);
                    } catch (Exception ignored) { }
                    throw new IllegalStateException("ИИ-сервер вернул HTTP " + code + ": " + shorten(detail, 260));
                }
                JSONObject json = new JSONObject(response);
                JSONArray choices = json.optJSONArray("choices");
                if (choices == null || choices.length() == 0) throw new IllegalStateException("ИИ вернул пустой ответ.");
                JSONObject message = choices.getJSONObject(0).optJSONObject("message");
                String answer = message == null ? "" : message.optString("content", "").trim();
                if (answer.isEmpty()) throw new IllegalStateException("ИИ вернул пустой текст.");
                callback.onSuccess(answer);
            } catch (Exception e) {
                callback.onError(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    private static String normalizeEndpoint(String value) {
        String endpoint = value == null ? "" : value.trim();
        if (endpoint.isEmpty()) endpoint = "https://api.openai.com/v1/chat/completions";
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

    private static String shorten(String value, int max) {
        if (value == null) return "";
        String clean = value.replace('\n', ' ').replace('\r', ' ').trim();
        return clean.length() <= max ? clean : clean.substring(0, max) + "…";
    }
}
