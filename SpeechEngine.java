package app.orchard.tv.aerialvoice;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import java.util.ArrayList;
import java.util.Locale;

/** Russian speech recognition through Android's configured RecognitionService and Russian TTS. */
public final class SpeechEngine implements RecognitionListener, TextToSpeech.OnInitListener {
    private static final String TAG = "AerialVoiceSpeech";
    public interface Listener {
        void onStatus(String status);
        void onPartial(String text);
        void onFinal(String text);
        void onError(String message);
        void onTtsReady(boolean ready, String detail);
        void onSpeechDone();
    }

    private final Context context;
    private final Listener listener;
    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private boolean ttsReady;
    private boolean destroyed;

    public SpeechEngine(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        try {
            tts = new TextToSpeech(this.context, this);
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) { }
                @Override public void onDone(String id) { listener.onSpeechDone(); }
                @Override public void onError(String id) { listener.onStatus("Не удалось озвучить ответ."); }
            });
        } catch (Exception e) {
            ttsReady = false;
            listener.onTtsReady(false, "Не удалось запустить синтез речи: " + safeMessage(e));
        }
    }

    public boolean isRecognitionAvailable() {
        return SpeechRecognizer.isRecognitionAvailable(context);
    }

    public void startListening() {
        if (destroyed) return;
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            listener.onError("На телевизоре не найден сервис распознавания речи Android.");
            return;
        }
        try {
            if (recognizer != null) {
                try { recognizer.destroy(); } catch (Exception ignored) { }
            }
            recognizer = SpeechRecognizer.createSpeechRecognizer(context);
            recognizer.setRecognitionListener(this);
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU");
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ru-RU");
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
            intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.getPackageName());
            intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false);
            Log.i(TAG, "Starting Android SpeechRecognizer with requested locale ru-RU");
            listener.onStatus("Слушаю… говори по-русски");
            recognizer.startListening(intent);
        } catch (Exception e) {
            listener.onError("Не удалось запустить распознавание: " + safeMessage(e));
        }
    }

    public void stopListening() {
        if (recognizer != null) {
            try { recognizer.stopListening(); } catch (Exception ignored) { }
        }
    }

    public void cancel() {
        if (recognizer != null) {
            try { recognizer.cancel(); } catch (Exception ignored) { }
        }
    }

    public boolean isTtsReady() { return ttsReady; }

    public void speak(String text) {
        if (tts == null || !ttsReady || text == null || text.trim().isEmpty()) return;
        int result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "aerial-voice-answer-" + System.currentTimeMillis());
        if (result == TextToSpeech.ERROR) {
            listener.onStatus("Синтез речи не смог воспроизвести ответ.");
            listener.onSpeechDone();
        }
    }

    @Override public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS && tts != null) {
            int language = tts.setLanguage(new Locale("ru", "RU"));
            ttsReady = language != TextToSpeech.LANG_MISSING_DATA && language != TextToSpeech.LANG_NOT_SUPPORTED;
            listener.onTtsReady(ttsReady, ttsReady ? "Русская озвучка готова" : "В системе нет русского голоса TTS.");
        } else {
            ttsReady = false;
            listener.onTtsReady(false, "Не удалось инициализировать синтез речи.");
        }
    }

    @Override public void onReadyForSpeech(Bundle params) { Log.i(TAG, "Recognition service is ready for audio"); listener.onStatus("Микрофон активен. Говори…"); }
    @Override public void onBeginningOfSpeech() { Log.i(TAG, "Speech input started"); listener.onStatus("Распознаю речь…"); }
    @Override public void onRmsChanged(float rmsdB) { }
    @Override public void onBufferReceived(byte[] buffer) { }
    @Override public void onEndOfSpeech() { listener.onStatus("Обрабатываю фразу…"); }
    @Override public void onError(int error) { Log.w(TAG, "SpeechRecognizer error code=" + error); listener.onError(errorText(error)); }

    @Override public void onResults(Bundle results) {
        ArrayList<String> matches = results == null ? null : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        String text = matches == null || matches.isEmpty() ? "" : matches.get(0).trim();
        Log.i(TAG, "Recognition results received; candidateCount=" + (matches == null ? 0 : matches.size()));
        if (text.isEmpty()) listener.onError("Не удалось разобрать речь. Попробуй ещё раз.");
        else listener.onFinal(text);
    }

    @Override public void onPartialResults(Bundle partialResults) {
        ArrayList<String> matches = partialResults == null ? null : partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches != null && !matches.isEmpty()) listener.onPartial(matches.get(0));
    }
    @Override public void onEvent(int eventType, Bundle params) { }

    public void destroy() {
        destroyed = true;
        if (recognizer != null) {
            try { recognizer.cancel(); recognizer.destroy(); } catch (Exception ignored) { }
            recognizer = null;
        }
        if (tts != null) {
            try { tts.stop(); tts.shutdown(); } catch (Exception ignored) { }
            tts = null;
        }
    }

    private String errorText(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_AUDIO: return "Ошибка аудиовхода. Не удалось получить звук с микрофона.";
            case SpeechRecognizer.ERROR_CLIENT: return "Распознавание прервано приложением или системой.";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "Нет разрешения на использование микрофона.";
            case SpeechRecognizer.ERROR_NETWORK: return "Ошибка сети при распознавании речи.";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "Сервис распознавания не ответил вовремя.";
            case SpeechRecognizer.ERROR_NO_MATCH: return "Речь не распознана. Попробуй говорить чуть громче и ближе к пульту.";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "Сервис распознавания уже занят. Подожди секунду.";
            case SpeechRecognizer.ERROR_SERVER: return "Ошибка сервера распознавания речи.";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "Не услышал речь. Нажми микрофон и попробуй ещё раз.";
            case SpeechRecognizer.ERROR_TOO_MANY_REQUESTS: return "Слишком много запросов к распознаванию. Подожди немного.";
            default: return "Ошибка распознавания речи (код " + error + ").";
        }
    }

    private String safeMessage(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
