package app.orchard.tv.aerialvoice;

import org.junit.Test;
import static org.junit.Assert.*;
public final class SpeechEndpointDetectorTest {
    @Test public void recognizesShortSpeechAndWaitsForSilence(){
        SpeechEndpointDetector end=new SpeechEndpointDetector(1000,1250);
        end.receive(170,1100);
        assertTrue(end.speechSeen());
        assertFalse(end.phraseDone(2250));
        assertTrue(end.phraseDone(2351));
    }
    @Test public void ignoresLongQuietRoom(){
        SpeechEndpointDetector end=new SpeechEndpointDetector(0,1350);
        for(int i=0;i<55;i++)end.receive(25,i*200);
        assertFalse(end.speechSeen());
        assertFalse(end.noSpeechTimeout(11999));
        assertTrue(end.noSpeechTimeout(12000));
    }
    @Test public void adaptsToNoiseAndClampsSilence(){
        SpeechEndpointDetector end=new SpeechEndpointDetector(0,200);
        for(int i=0;i<20;i++)end.receive(100,i*100);
        assertTrue(end.thresholdRms()>=130);
        end.receive(450,2100);
        assertFalse(end.phraseDone(2800));
        assertTrue(end.phraseDone(3000));
    }
}
