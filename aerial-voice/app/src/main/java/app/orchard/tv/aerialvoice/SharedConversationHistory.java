package app.orchard.tv.aerialvoice;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Both the full-screen UI and floating assistant use the same existing history key.
 * Every write is a read-modify-write operation to avoid replacing newer conversations.
 */
final class SharedConversationHistory {
    static final String PREFS = "aerial_voice_settings";
    static final String KEY = "conversation_history_json";
    private static final int MAX_MESSAGES = 30;

    private SharedConversationHistory() { }

    static synchronized JSONArray load(Context context) {
        String raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "");
        if (raw == null || raw.trim().isEmpty()) return new JSONArray();
        try {
            JSONArray input = new JSONArray(raw);
            JSONArray result = new JSONArray();
            for (int i = Math.max(0, input.length() - MAX_MESSAGES); i < input.length(); i++) {
                JSONObject msg = input.optJSONObject(i);
                if (msg == null) continue;
                String role = msg.optString("role","");
                String value = msg.optString("content","").trim();
                if (!value.isEmpty() && ("user".equals(role) || "assistant".equals(role)))
                    result.put(msg);
            }
            return result;
        } catch (Exception error) {
            // Do not overwrite unreadable history automatically.
            return new JSONArray();
        }
    }

    static synchronized JSONArray append(Context context, String role, String message) {
        JSONArray current = load(context);
        if (message == null || message.trim().isEmpty()) return current;
        if (!"user".equals(role) && !"assistant".equals(role)) return current;
        try {
            JSONObject item = new JSONObject();
            item.put("role",role);
            item.put("content",message.trim());
            item.put("timestamp",System.currentTimeMillis());
            current.put(item);
            while (current.length() > MAX_MESSAGES) current.remove(0);
            SharedPreferences prefs=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
            prefs.edit().putString(KEY,current.toString()).apply();
        } catch (Exception ignored) { }
        return current;
    }
}
