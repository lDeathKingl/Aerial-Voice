package app.orchard.tv.aerialvoice;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
public class VolumeControllerTest {
    @Test public void percentMath() {
        assertEquals(50,VolumeController.pct(5,10));
        assertEquals(0,VolumeController.pct(0,0));
        assertEquals(100,VolumeController.pct(15,15));
    }
    @Test public void truthfulResult() {
        assertTrue(VolumeController.describe(5,5,15,80,12).contains("НЕ изменилась"));
        assertTrue(VolumeController.describe(5,9,15,80,12).contains("не до 80%"));
        assertTrue(VolumeController.describe(5,12,15,80,12).contains("установлена"));
    }
}
