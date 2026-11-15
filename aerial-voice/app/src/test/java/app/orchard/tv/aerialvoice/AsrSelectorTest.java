package app.orchard.tv.aerialvoice;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class AsrSelectorTest {
    @Test public void autoPrefersGigaAm() {
        assertEquals("gigaam", AsrSelector.choose("auto", true, true));
        assertEquals("vosk", AsrSelector.choose("auto", false, true));
        assertEquals("", AsrSelector.choose("auto", false, false));
    }
    @Test public void manualChoiceNeverSilentlySwitches() {
        assertEquals("", AsrSelector.choose("gigaam", false, true));
        assertEquals("", AsrSelector.choose("vosk", true, false));
        assertEquals("vosk", AsrSelector.choose("vosk", true, true));
    }
    @Test public void invalidPreferenceDefaultsToAuto() {
        assertEquals("auto", AsrSelector.normalized("nonsense"));
        assertEquals("gigaam", AsrSelector.choose(null, true, false));
    }
}
