package app.orchard.tv.aerialvoice;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class AsrSelectorTest {
    @Test public void legacyPreferencesAlwaysMigrateToGigaAm() {
        assertEquals("gigaam", AsrSelector.normalized("auto"));
        assertEquals("gigaam", AsrSelector.normalized("vosk"));
        assertEquals("gigaam", AsrSelector.normalized("nonsense"));
        assertEquals("gigaam", AsrSelector.normalized(null));
    }
    @Test public void onlyAvailableGigaAmIsSelected() {
        assertEquals("gigaam", AsrSelector.choose("auto", true, false));
        assertEquals("gigaam", AsrSelector.choose("vosk", true, true));
        assertEquals("", AsrSelector.choose("gigaam", false, true));
        assertEquals("", AsrSelector.choose(null, false, false));
    }
}
