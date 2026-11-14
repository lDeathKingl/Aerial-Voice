package app.orchard.tv.aerialvoice;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** User-controlled long-term facts, encrypted locally with a dedicated Android Keystore key. */
public final class MemoryStore {
    private static final String PREFS = "aerial_voice_settings";
    private static final String KEY = "long_term_memory_ciphertext";
    private static final String KEY_ALIAS = "aerial_voice_memory_v1";
    private static final int MAX_FACTS = 40;
    private static final int MAX_FACT_LENGTH = 500;

    private MemoryStore() { }

    public static synchronized List<String> read(Context context) {
        List<String> facts = new ArrayList<>();
        try {
            String encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "");
            if (encoded == null || encoded.isEmpty()) return facts;
            byte[] all = Base64.decode(encoded, Base64.NO_WRAP);
            if (all.length <= 12) return facts;
            ByteBuffer buffer = ByteBuffer.wrap(all);
            byte[] iv = new byte[12];
            buffer.get(iv);
            byte[] encrypted = new byte[buffer.remaining()];
            buffer.get(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
            JSONArray array = new JSONArray(new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8));
            for (int i = 0; i < array.length(); i++) {
                String fact = array.optString(i, "").trim();
                if (!fact.isEmpty()) facts.add(fact);
            }
        } catch (Exception ignored) {
            // Never crash the assistant because an old or unreadable memory blob exists.
        }
        return facts;
    }

    public static synchronized boolean add(Context context, String value) {
        String fact = value == null ? "" : value.trim();
        if (fact.isEmpty()) return false;
        if (fact.length() > MAX_FACT_LENGTH) fact = fact.substring(0, MAX_FACT_LENGTH).trim();
        List<String> facts = read(context);
        String normalized = normalize(fact);
        for (String old : facts) if (normalize(old).equals(normalized)) return false;
        facts.add(fact);
        while (facts.size() > MAX_FACTS) facts.remove(0);
        return write(context, facts);
    }

    /** Removes facts containing the supplied phrase; returns the number removed. */
    public static synchronized int forget(Context context, String phrase) {
        String needle = normalize(phrase);
        if (needle.isEmpty()) return 0;
        List<String> facts = read(context);
        int before = facts.size();
        facts.removeIf(f -> normalize(f).contains(needle));
        int removed = before - facts.size();
        if (removed > 0) write(context, facts);
        return removed;
    }

    public static synchronized void clear(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply();
    }

    public static int count(Context context) { return read(context).size(); }

    public static String prompt(Context context) {
        List<String> facts = read(context);
        if (facts.isEmpty()) return "";
        StringBuilder out = new StringBuilder("Сохранённая долговременная память пользователя. Используй её только когда она относится к вопросу; не выдумывай дополнительные факты.\n");
        for (String fact : facts) out.append("- ").append(fact).append('\n');
        return out.toString().trim();
    }

    private static boolean write(Context context, List<String> facts) {
        try {
            JSONArray array = new JSONArray();
            for (String fact : facts) array.put(fact);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            byte[] encrypted = cipher.doFinal(array.toString().getBytes(StandardCharsets.UTF_8));
            ByteBuffer buffer = ByteBuffer.allocate(cipher.getIV().length + encrypted.length);
            buffer.put(cipher.getIV());
            buffer.put(encrypted);
            String encoded = Base64.encodeToString(buffer.array(), Base64.NO_WRAP);
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, encoded).apply();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        java.security.Key existing = store.getKey(KEY_ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replace('ё', 'е')
                .replaceAll("[^\\p{L}\\p{N} ]", " ").replaceAll("\\s+", " ").trim();
    }
}
