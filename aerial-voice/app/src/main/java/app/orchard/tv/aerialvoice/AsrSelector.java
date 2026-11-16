package app.orchard.tv.aerialvoice;

/** Migrates old Vosk/auto preferences to the only supported offline engine. */
public final class AsrSelector {
    public static final String GIGAAM = "gigaam";

    private AsrSelector() { }

    public static String normalized(String ignored) {
        return GIGAAM;
    }

    /** Kept for old callers; never reports a different ASR engine. */
    public static String choose(String selected, boolean gigaamAvailable, boolean ignoredLegacyVosk) {
        return gigaamAvailable ? GIGAAM : "";
    }
}
