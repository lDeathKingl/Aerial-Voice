package app.orchard.tv.aerialvoice;

import java.util.Locale;

/** Android-independent recognition of RHVoice as an installed TTS engine. */
public final class TtsEngineChoice {
    private TtsEngineChoice() { }

    public static boolean isRhVoice(String packageName, String label) {
        String pkg = packageName == null ? "" : packageName.toLowerCase(Locale.ROOT);
        String name = label == null ? "" : label.toLowerCase(Locale.ROOT);
        return pkg.contains("rhvoice") || name.contains("rhvoice") || name.contains("rh voice");
    }
}
