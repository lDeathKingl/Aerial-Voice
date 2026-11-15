package app.orchard.tv.aerialvoice;

/** Pure selection policy; Android-independent and covered by unit tests. */
public final class AsrSelector {
    public static final String AUTO = "auto";
    public static final String GIGAAM = "gigaam";
    public static final String VOSK = "vosk";

    private AsrSelector() { }

    public static String normalized(String selected) {
        if (GIGAAM.equals(selected) || VOSK.equals(selected)) return selected;
        return AUTO;
    }

    /** Returns chosen engine name or empty string if requested engine is unavailable. */
    public static String choose(String selected, boolean gigaamAvailable, boolean voskAvailable) {
        switch (normalized(selected)) {
            case GIGAAM: return gigaamAvailable ? GIGAAM : "";
            case VOSK: return voskAvailable ? VOSK : "";
            default:
                return gigaamAvailable ? GIGAAM : (voskAvailable ? VOSK : "");
        }
    }
}
