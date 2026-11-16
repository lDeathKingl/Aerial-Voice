package app.orchard.tv.aerialvoice;

import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognitionService;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;

/** Exposes the same offline GigaAM ASR to Android's optional assistant role. */
public final class AerialRecognitionService extends RecognitionService implements SpeechEngine.Listener {
    private SpeechEngine engine;
    private Callback current;
    @Override protected synchronized void onStartListening(Intent intent, Callback callback) {
        if (current != null) {
            callback.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY);
            return;
        }
        current = callback;
        try {
            engine = new SpeechEngine(this,this,"system");
            if (!engine.isGigaReady()) {
                onError("GigaAM model missing");
                return;
            }
            engine.startListening(true);
        } catch (RuntimeException error) {
            onError(error.getMessage());
        }
    }
    @Override protected synchronized void onStopListening(Callback callback) {
        if (engine != null) engine.stopListening();
    }
    @Override protected synchronized void onCancel(Callback callback) {
        if (engine != null) engine.cancel();
        clear();
    }
    private synchronized void clear() {
        if (engine != null) { engine.destroy();engine = null; }
        current = null;
    }
    @Override public synchronized void onFinal(String text) {
        Callback cb=current;
        if(cb==null)return;
        Bundle result=new Bundle();
        ArrayList<String> matches=new ArrayList<>();
        matches.add(text);
        result.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION,matches);
        try { cb.results(result); }
        catch (Exception ignored) { }
        clear();
    }
    @Override public synchronized void onNoSpeechTimeout() { fail(SpeechRecognizer.ERROR_SPEECH_TIMEOUT); }
    @Override public synchronized void onError(String message) { fail(SpeechRecognizer.ERROR_NO_MATCH); }
    private void fail(int error) {
        Callback cb=current;
        if (cb != null) {
            try { cb.error(error); } catch (Exception ignored) { }
        }
        clear();
    }
    @Override public void onStatus(String status) { }
    @Override public void onPartial(String partial) { }
    @Override public void onTtsReady(boolean ready,String detail) { }
    @Override public void onSpeechDone() { }
    @Override public synchronized void onDestroy() {
        clear();
        super.onDestroy();
    }
}
