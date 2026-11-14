package app.orchard.tv.aerialvoice;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.ByteBuffer;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Stores the optional AI API key encrypted with an Android Keystore AES-GCM key. */
public final class SecretStore {
    private static final String STORE = "AndroidKeyStore";
    private static final String ALIAS = "aerial_voice_api_key_v1";
    private static final String PREFS = "aerial_voice_settings";
    private static final String KEY_CIPHER = "api_key_ciphertext";

    private SecretStore() { }

    public static void save(Context context, String value) throws Exception {
        if (value == null || value.trim().isEmpty()) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_CIPHER).apply();
            return;
        }
        SecretKey key = getOrCreateKey();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] encrypted = cipher.doFinal(value.trim().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ByteBuffer buffer = ByteBuffer.allocate(cipher.getIV().length + encrypted.length);
        buffer.put(cipher.getIV());
        buffer.put(encrypted);
        String encoded = Base64.encodeToString(buffer.array(), Base64.NO_WRAP);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_CIPHER, encoded).apply();
    }

    public static String read(Context context) throws Exception {
        String encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CIPHER, "");
        if (encoded == null || encoded.isEmpty()) return "";
        byte[] all = Base64.decode(encoded, Base64.NO_WRAP);
        if (all.length <= 12) return "";
        ByteBuffer buffer = ByteBuffer.wrap(all);
        byte[] iv = new byte[12];
        buffer.get(iv);
        byte[] encrypted = new byte[buffer.remaining()];
        buffer.get(encrypted);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(encrypted), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore store = KeyStore.getInstance(STORE);
        store.load(null);
        java.security.Key existing = store.getKey(ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;

        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE);
        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build();
        generator.init(spec);
        return generator.generateKey();
    }
}
