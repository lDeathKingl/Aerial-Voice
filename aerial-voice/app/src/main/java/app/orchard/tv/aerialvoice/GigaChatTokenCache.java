package app.orchard.tv.aerialvoice;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Short-lived, in-process OAuth token cache.
 * No Authorization Key or access token is written to disk or logs.
 */
public final class GigaChatTokenCache {
    private static final long REFRESH_MARGIN_SECONDS = 90L;
    private static final long FALLBACK_VALIDITY_SECONDS = 1700L;
    private static final long MAX_REASONABLE_VALIDITY_SECONDS = 24L * 60L * 60L;

    private String accessToken = "";
    private String credentialFingerprint = "";
    private long expiresAtSeconds;

    public synchronized String get(String authKey, long nowSeconds) {
        if (accessToken.isEmpty() || !credentialFingerprint.equals(fingerprint(authKey))) return "";
        if (expiresAtSeconds <= nowSeconds + REFRESH_MARGIN_SECONDS) return "";
        return accessToken;
    }

    public synchronized void put(String authKey, String token, long rawExpiry, long nowSeconds) {
        if (token == null || token.isEmpty()) {
            clear();
            return;
        }
        accessToken = token;
        credentialFingerprint = fingerprint(authKey);
        expiresAtSeconds = normalizeExpirySeconds(rawExpiry, nowSeconds);
    }

    public synchronized void clear() {
        accessToken = "";
        credentialFingerprint = "";
        expiresAtSeconds = 0L;
    }

    /**
     * GigaChat documentation/examples have shown both Unix seconds and milliseconds.
     * Normalize once before comparing against System.currentTimeMillis() / 1000.
     */
    static long normalizeExpirySeconds(long rawExpiry, long nowSeconds) {
        long seconds = rawExpiry > 100_000_000_000L ? rawExpiry / 1000L : rawExpiry;
        if (seconds <= nowSeconds || seconds > nowSeconds + MAX_REASONABLE_VALIDITY_SECONDS) {
            return nowSeconds + FALLBACK_VALIDITY_SECONDS;
        }
        return seconds;
    }

    private static String fingerprint(String authKey) {
        if (authKey == null) return "";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(authKey.trim().getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                out.append(Character.forDigit((b >>> 4) & 0x0f, 16));
                out.append(Character.forDigit(b & 0x0f, 16));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
