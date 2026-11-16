package app.orchard.tv.aerialvoice;

/**
 * Offline voice-activity endpointing for GigaAM: configurable silence pause
 * with a slowly adapting background noise estimate. Not a wake-word detector.
 */
final class SpeechEndpointDetector {
    private final long startedAt;
    private final long silenceMs;
    private long noiseRms = 45L;
    private long lastSpeechAt;
    private long speechStartedAt;
    private boolean speechSeen;

    SpeechEndpointDetector(long startedAt, long silenceMs) {
        this.startedAt = startedAt;
        this.silenceMs = Math.max(850L, Math.min(2600L, silenceMs));
        this.lastSpeechAt = startedAt;
    }

    void receive(long rms, long now) {
        // Calibrate only on low-level background noise, never during a loud phrase.
        if (!speechSeen && rms < 115L)
            noiseRms = Math.max(1L, (noiseRms * 9L + rms) / 10L);
        long threshold = Math.max(130L, noiseRms * 3L);
        if (rms >= threshold) {
            if (!speechSeen) speechStartedAt = now;
            speechSeen = true;
            lastSpeechAt = now;
        }
    }

    boolean speechSeen() { return speechSeen; }
    boolean noSpeechTimeout(long now) { return !speechSeen && now - startedAt >= 12000L; }
    boolean phraseDone(long now) {
        return speechSeen && now - speechStartedAt >= 180L && now - lastSpeechAt >= silenceMs;
    }
    long thresholdRms() { return Math.max(130L, noiseRms * 3L); }
}
