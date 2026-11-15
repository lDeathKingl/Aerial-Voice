package app.orchard.tv.aerialvoice;

import static org.junit.Assert.*;
import org.junit.Test;

public class TtsEngineChoiceTest {
    @Test public void recognizesRhVoiceByPackageOrServiceLabel() {
        assertTrue(TtsEngineChoice.isRhVoice("com.github.olga_yakovleva.rhvoice.android", ""));
        assertTrue(TtsEngineChoice.isRhVoice("org.some.tts", "RHVoice"));
        assertTrue(TtsEngineChoice.isRhVoice("org.some.tts", "RH Voice русский"));
    }
    @Test public void ignoresUnrelatedSpeechServices() {
        assertFalse(TtsEngineChoice.isRhVoice(null, null));
        assertFalse(TtsEngineChoice.isRhVoice("com.google.android.tts", "Speech Services"));
    }
}
