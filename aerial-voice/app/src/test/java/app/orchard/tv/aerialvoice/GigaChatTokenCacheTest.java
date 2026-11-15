package app.orchard.tv.aerialvoice;

import org.junit.Test;
import static org.junit.Assert.*;

public class GigaChatTokenCacheTest {
    private static final long NOW = 1_700_000_000L;

    @Test public void secondsExpiryRemainsSeconds() {
        assertEquals(NOW + 1800L, GigaChatTokenCache.normalizeExpirySeconds(NOW + 1800L, NOW));
    }

    @Test public void millisecondsExpiryIsConvertedToSeconds() {
        assertEquals(NOW + 1800L,
                GigaChatTokenCache.normalizeExpirySeconds((NOW + 1800L) * 1000L, NOW));
    }

    @Test public void missingOrImplausibleExpiryFallsBackToShortValidity() {
        assertEquals(NOW + 1700L, GigaChatTokenCache.normalizeExpirySeconds(0, NOW));
        assertEquals(NOW + 1700L,
                GigaChatTokenCache.normalizeExpirySeconds((NOW + 90000L) * 1000L, NOW));
    }

    @Test public void tokenCannotCrossAuthorizationKeys() {
        GigaChatTokenCache cache = new GigaChatTokenCache();
        cache.put("key-one", "token-one", NOW + 1800L, NOW);
        assertEquals("token-one", cache.get("key-one", NOW));
        assertEquals("", cache.get("key-two", NOW));
    }

    @Test public void tokenRefreshesBeforeExpiry() {
        GigaChatTokenCache cache = new GigaChatTokenCache();
        cache.put("key-one", "token-one", NOW + 180L, NOW);
        assertEquals("token-one", cache.get("key-one", NOW + 89L));
        assertEquals("", cache.get("key-one", NOW + 90L));
    }

    @Test public void cacheClearRemovesToken() {
        GigaChatTokenCache cache = new GigaChatTokenCache();
        cache.put("key-one", "token-one", NOW + 1800L, NOW);
        cache.clear();
        assertEquals("", cache.get("key-one", NOW));
    }
}
