package app.orchard.tv.aerialvoice;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

/** Russian offline speech recognition from Xiaomi RC In, plus Android TTS. */
public final class SpeechEngine implements TextToSpeech.OnInitListener {
    private static final String TAG = "AerialVoiceSpeech";
    private static final int SAMPLE_RATE = 16000;
    private static final int FRAME_SAMPLES = 3200;

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
    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile Model model;
    private volatile boolean modelReady;
    private volatile boolean modelLoading = true;
    private volatile boolean destroyed;
    private volatile boolean listening;
    private volatile boolean stopRequested;
    private volatile boolean deliverResult = true;
    private volatile AudioRecord activeRecord;
    private Thread captureThread;

    private TextToSpeech tts;
    private volatile boolean ttsReady;

    public SpeechEngine(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;

        try {
            tts = new TextToSpeech(this.context, this);
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) { }
                @Override public void onDone(String id) {
                    if (!destroyed) main.post(() -> listener.onSpeechDone());
                }
                @Override public void onError(String id) {
                    if (!destroyed) postStatus("Не удалось озвучить ответ.");
                }
            });
        } catch (Exception e) {
            ttsReady = false;
            postTtsReady(false, "Не удалось запустить синтез речи: " + safeMessage(e));
        }

        postStatus("Подготавливаю офлайн-распознавание Vosk…");
        VoskModelManager.prepare(this.context, new VoskModelManager.Listener() {
            @Override public void onProgress(String message) {
                if (!destroyed) postStatus(message);
            }

            @Override public void onReady(File modelDirectory) {
                if (destroyed) return;
                Thread loader = new Thread(() -> {
                    try {
                        Model loaded = new Model(modelDirectory.getAbsolutePath());
                        if (destroyed) {
                            loaded.close();
                            return;
                        }
                        model = loaded;
                        modelReady = true;
                        modelLoading = false;
                        postStatus("Русское офлайн-распознавание Vosk готово.");
                    } catch (Exception e) {
                        modelLoading = false;
                        postError("Не удалось загрузить модель Vosk: " + safeMessage(e));
                    }
                }, "AerialVoice-Vosk-Load");
                loader.start();
            }

            @Override public void onError(String message) {
                modelLoading = false;
                if (!destroyed) postError(message);
            }
        });
    }

    public boolean isModelReady() { return modelReady; }
    public boolean isModelLoading() { return modelLoading; }
    public boolean isListening() { return listening; }
    public boolean isTtsReady() { return ttsReady; }

    /** Starts a capture session. The caller should stop it when the remote mic key is released. */
    public synchronized void startListening() {
        if (destroyed || listening) return;
        if (!modelReady || model == null) {
            postError(modelLoading
                    ? "Русская модель Vosk ещё загружается. Подожди окончания загрузки."
                    : "Русская модель Vosk не готова. Проверь интернет и свободное место на телевизоре.");
            return;
        }

        stopRequested = false;
        deliverResult = true;
        listening = true;
        captureThread = new Thread(this::captureFromRemoteMic, "AerialVoice-Vosk-RCIn");
        captureThread.start();
    }

    /** Stops capture and submits the final recognized phrase. */
    public void stopListening() {
        requestStop(true);
    }

    /** Stops capture and discards the current phrase. */
    public void cancel() {
        requestStop(false);
    }

    private void requestStop(boolean deliver) {
        deliverResult = deliver;
        stopRequested = true;
        AudioRecord record = activeRecord;
        if (record != null && record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
            try { record.stop(); } catch (Exception ignored) { }
        }
    }

    private void captureFromRemoteMic() {
        AudioRecord record = null;
        Recognizer recognizer = null;
        String failure = null;
        StringBuilder completed = new StringBuilder();
        boolean routeConfirmed = false;
        try {
            recognizer = new Recognizer(model, SAMPLE_RATE);
            AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (audioManager == null) throw new IOException("AudioManager недоступен.");

            AudioDeviceInfo remoteInput = findRemoteMic(audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS));
            if (remoteInput == null) {
                throw new IOException("Вход RC In / MIBT.VoiceControl не найден среди доступных микрофонов.");
            }

            int minimumBuffer = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minimumBuffer <= 0) {
                throw new IOException("Android не смог создать аудиобуфер: " + minimumBuffer);
            }

            AudioFormat format = new AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build();

            record = new AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(Math.max(minimumBuffer * 2, FRAME_SAMPLES * 4))
                    .build();

            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IOException("AudioRecord не инициализировался.");
            }

            boolean preferredAccepted = record.setPreferredDevice(remoteInput);
            Log.i(TAG, "Vosk RC In: requested=" + describe(remoteInput)
                    + " setPreferredDevice=" + preferredAccepted);
            activeRecord = record;
            record.startRecording();

            if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IOException("Android не запустил запись с микрофона.");
            }

            short[] samples = new short[FRAME_SAMPLES];
            long routeDeadline = System.currentTimeMillis() + 6000L;
            AudioDeviceInfo actual = null;
            while (!stopRequested && System.currentTimeMillis() < routeDeadline) {
                actual = record.getRoutedDevice();
                if (isRemoteMic(actual, remoteInput)) {
                    routeConfirmed = true;
                    break;
                }
                int read = record.read(samples, 0, samples.length, AudioRecord.READ_BLOCKING);
                if (read < 0 && !stopRequested) {
                    throw new IOException("Ошибка ожидания аудиомаршрута: " + read);
                }
            }

            if (stopRequested) return;
            if (!routeConfirmed) {
                throw new IOException("Android не подтвердил маршрут в RC In. Фактический вход: "
                        + describe(actual) + ". Запись со встроенного микрофона не используется.");
            }

            Log.i(TAG, "Vosk RC In: route confirmed " + describe(actual));
            postStatus("RC In подключён. Говори, удерживая кнопку микрофона…");

            String lastPartial = "";
            long lastUiUpdate = 0L;
            while (!stopRequested) {
                int count = record.read(samples, 0, samples.length, AudioRecord.READ_BLOCKING);
                if (count > 0) {
                    if (recognizer.acceptWaveForm(samples, count)) {
                        String segment = readJsonText(recognizer.getResult(), "text");
                        appendText(completed, segment);
                        String current = completed.toString();
                        if (!current.equals(lastPartial)) {
                            lastPartial = current;
                            postPartial(current);
                        }
                    } else {
                        long now = System.currentTimeMillis();
                        if (now - lastUiUpdate >= 180L) {
                            String partial = readJsonText(recognizer.getPartialResult(), "partial");
                            String current = joinText(completed.toString(), partial);
                            if (!current.equals(lastPartial)) {
                                lastPartial = current;
                                postPartial(current);
                            }
                            lastUiUpdate = now;
                        }
                    }
                } else if (count < 0) {
                    if (!stopRequested) throw new IOException("AudioRecord вернул ошибку чтения: " + count);
                    break;
                }
            }

            if (deliverResult && routeConfirmed) {
                String tail = readJsonText(recognizer.getFinalResult(), "text");
                appendText(completed, tail);
            }
        } catch (Exception e) {
            if (!stopRequested || deliverResult) failure = safeMessage(e);
            Log.e(TAG, "Vosk capture failed", e);
        } finally {
            activeRecord = null;
            if (record != null) {
                try {
                    if (record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) record.stop();
                } catch (Exception ignored) { }
                try { record.release(); } catch (Exception ignored) { }
            }
            if (recognizer != null) {
                try { recognizer.close(); } catch (Exception ignored) { }
            }

            listening = false;
            final String result = completed.toString().trim();
            final String error = failure;
            final boolean shouldDeliver = deliverResult && !destroyed;
            if (destroyed) closeModelIfIdle();
            if (shouldDeliver) {
                main.post(() -> {
                    if (destroyed) return;
                    if (!result.isEmpty()) listener.onFinal(result);
                    else if (error != null && !error.isEmpty()) listener.onError("Ошибка Vosk: " + error);
                    else listener.onError("Речь не распознана. Удерживай кнопку микрофона и произнеси фразу целиком.");
                });
            }
        }
    }

    private AudioDeviceInfo findRemoteMic(AudioDeviceInfo[] devices) {
        for (AudioDeviceInfo device : devices) {
            String name = String.valueOf(device.getProductName()).trim().toLowerCase(Locale.ROOT);
            if (name.equals("rc in") || name.contains("rc in")
                    || name.equals("mibt.voicecontrol") || name.contains("voicecontrol")) {
                return device;
            }
        }
        return null;
    }

    private boolean isRemoteMic(AudioDeviceInfo actual, AudioDeviceInfo requested) {
        if (actual == null) return false;
        if (actual.getId() == requested.getId()) return true;
        String name = String.valueOf(actual.getProductName()).trim().toLowerCase(Locale.ROOT);
        return name.equals("rc in") || name.contains("rc in")
                || name.equals("mibt.voicecontrol") || name.contains("voicecontrol");
    }

    private String describe(AudioDeviceInfo device) {
        if (device == null) return "не определён";
        return String.valueOf(device.getProductName()) + " (id=" + device.getId()
                + ", type=" + device.getType() + ")";
    }

    private String readJsonText(String json, String field) {
        if (json == null || json.isEmpty()) return "";
        try { return new JSONObject(json).optString(field, "").trim(); }
        catch (Exception ignored) { return ""; }
    }

    private void appendText(StringBuilder target, String value) {
        if (value == null || value.trim().isEmpty()) return;
        String text = value.trim();
        if (target.length() == 0) target.append(text);
        else if (!target.toString().endsWith(text)) target.append(' ').append(text);
    }

    private String joinText(String first, String second) {
        if (first == null || first.isEmpty()) return second == null ? "" : second;
        if (second == null || second.isEmpty()) return first;
        return first + " " + second;
    }

    public void speak(String text) {
        if (tts == null || !ttsReady || text == null || text.trim().isEmpty()) return;
        int result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null,
                "aerial-voice-answer-" + System.currentTimeMillis());
        if (result == TextToSpeech.ERROR) {
            postStatus("Синтез речи не смог воспроизвести ответ.");
            main.post(() -> { if (!destroyed) listener.onSpeechDone(); });
        }
    }

    @Override public void onInit(int status) {
        if (destroyed) return;
        if (status == TextToSpeech.SUCCESS && tts != null) {
            int language = tts.setLanguage(new Locale("ru", "RU"));
            ttsReady = language != TextToSpeech.LANG_MISSING_DATA
                    && language != TextToSpeech.LANG_NOT_SUPPORTED;
            postTtsReady(ttsReady, ttsReady
                    ? "Русская озвучка готова"
                    : "В системе нет русского голоса TTS.");
        } else {
            ttsReady = false;
            postTtsReady(false, "Не удалось инициализировать синтез речи.");
        }
    }

    public void destroy() {
        destroyed = true;
        cancel();
        if (tts != null) {
            try { tts.stop(); tts.shutdown(); } catch (Exception ignored) { }
            tts = null;
        }
        if (!listening) closeModelIfIdle();
    }

    private synchronized void closeModelIfIdle() {
        if (listening) return;
        Model current = model;
        model = null;
        modelReady = false;
        if (current != null) {
            try { current.close(); } catch (Exception ignored) { }
        }
    }

    private void postStatus(String message) {
        main.post(() -> { if (!destroyed) listener.onStatus(message); });
    }

    private void postPartial(String text) {
        main.post(() -> { if (!destroyed) listener.onPartial(text); });
    }

    private void postError(String message) {
        main.post(() -> { if (!destroyed) listener.onError(message); });
    }

    private void postTtsReady(boolean ready, String message) {
        main.post(() -> { if (!destroyed) listener.onTtsReady(ready, message); });
    }

    private String safeMessage(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
