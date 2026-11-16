package app.orchard.tv.aerialvoice;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;


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
        void onNoSpeechTimeout();
    }

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    private final GigaAmEngine gigaAmEngine;
    private volatile String selectedAsr = AsrSelector.GIGAAM;
    private volatile int selectedAudioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION;
    private volatile long silenceTimeoutMs = 1350L;
    private volatile String lastAsrEngine = "ещё не использовался";
    private volatile long lastAsrMillis;
    private volatile String lastAudioRoute = "не проверен";
    private volatile long lastAudioRms;
    private volatile String lastAsrError = "";
    private volatile boolean destroyed;
    private volatile boolean listening;
    private volatile boolean stopRequested;
    private volatile boolean deliverResult = true;
    private volatile boolean handsFreeCapture;
    private volatile AudioRecord activeRecord;
    private Thread captureThread;

    private TextToSpeech tts;
    private volatile boolean ttsReady;
    private volatile String ttsState = "инициализация";
    private volatile String ttsEngineName = "не определён";
    private volatile String ttsLanguageStatus = "не проверен";
    private volatile String requestedTtsEngine = "";
    private volatile String ttsPreference = "auto";
    private volatile boolean autoRhVoiceSelected;
    private volatile boolean ttsAutoFallbackDone;
    private volatile String requestedTtsVoice = "";
    private volatile float speechRate = 1.0f;
    private volatile float speechPitch = 1.0f;

    public SpeechEngine(Context context, Listener listener) {
        this(context, listener, "");
    }

    public SpeechEngine(Context context, Listener listener, String enginePackage) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.gigaAmEngine = new GigaAmEngine(this.context);
        ttsPreference = enginePackage == null ? "auto" : enginePackage.trim();
        if (ttsPreference.isEmpty()) ttsPreference = "system";
        requestedTtsEngine = resolveTtsEngine(ttsPreference);
        autoRhVoiceSelected = "auto".equals(ttsPreference) && !requestedTtsEngine.isEmpty();
        initializeTts();

        postStatus(gigaAmEngine.isInstalled()
                ? "GigaAM v3: офлайн-модель найдена. Готов к записи с RC In."
                : "GigaAM v3 не установлена. Скопируй офлайн-модель установщиком.");


    }

    /** Honors a manual TTS choice. For 'auto', prefer an installed RHVoice Android service. */
    private String resolveTtsEngine(String preference) {
        if ("system".equals(preference)) return "";
        if (!"auto".equals(preference)) return preference;
        try {
            PackageManager pm = context.getPackageManager();
            Intent query = new Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE);
            java.util.List<ResolveInfo> services = pm.queryIntentServices(query, 0);
            for (ResolveInfo service : services) {
                if (service == null || service.serviceInfo == null) continue;
                String pkg = service.serviceInfo.packageName;
                CharSequence label = service.loadLabel(pm);
                if (TtsEngineChoice.isRhVoice(pkg, label == null ? "" : label.toString())) {
                    Log.i(TAG, "Auto selected installed RHVoice: " + pkg);
                    return pkg;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "RHVoice service detection unavailable", e);
        }
        return "";
    }

    private void restartTts() {
        if (destroyed) return;
        if (tts != null) {
            try { tts.stop(); tts.shutdown(); } catch (Exception ignored) { }
            tts = null;
        }
        ttsReady = false;
        ttsState = "переключение движка";
        ttsEngineName = requestedTtsEngine.isEmpty() ? "системный по умолчанию" : requestedTtsEngine;
        initializeTts();
    }

    private void initializeTts() {
        try {
            tts = requestedTtsEngine.isEmpty()
                    ? new TextToSpeech(context, this)
                    : new TextToSpeech(context, this, requestedTtsEngine);
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) {
                    Log.i(TAG, "TTS playback started: " + id);
                }
                @Override public void onDone(String id) {
                    Log.i(TAG, "TTS playback completed: " + id);
                    if (!destroyed) main.post(() -> listener.onSpeechDone());
                }
                @Override public void onError(String id) {
                    Log.e(TAG, "TTS playback failed: " + id);
                    if (!destroyed) postStatus("Не удалось озвучить ответ. " + getTtsDiagnostics());
                }
                @Override public void onError(String id, int errorCode) {
                    Log.e(TAG, "TTS playback failed: " + id + ", code=" + errorCode);
                    if (!destroyed) postStatus("Ошибка TTS (код " + errorCode + "). " + getTtsDiagnostics());
                }
            });
        } catch (Exception e) {
            ttsReady = false;
            ttsState = "ошибка запуска: " + safeMessage(e);
            Log.e(TAG, "Could not initialize Android TTS", e);
            postTtsReady(false, "Не удалось запустить синтез речи: " + safeMessage(e));
        }
    }

    public static final class EngineChoice {
        public final String packageName;
        public final String label;
        public EngineChoice(String packageName, String label) {
            this.packageName = packageName;
            this.label = label;
        }
    }

    public static final class VoiceChoice {
        public final String name;
        public final String label;
        public VoiceChoice(String name, String label) {
            this.name = name;
            this.label = label;
        }
    }

    /** Returns Russian voices exposed by the currently selected TTS engine. */
    public java.util.List<VoiceChoice> getAvailableTtsVoices() {
        java.util.List<VoiceChoice> result = new java.util.ArrayList<>();
        if (tts == null) return result;
        try {
            java.util.Set<Voice> voices = tts.getVoices();
            if (voices == null) return result;
            for (Voice voice : voices) {
                if (voice == null || voice.getLocale() == null
                        || !"ru".equalsIgnoreCase(voice.getLocale().getLanguage())) continue;
                String locale = voice.getLocale().toLanguageTag();
                String quality = voice.getQuality() >= Voice.QUALITY_HIGH ? "высокое качество" : "стандартное качество";
                String network = voice.isNetworkConnectionRequired() ? " · нужен интернет" : " · офлайн";
                result.add(new VoiceChoice(voice.getName(),
                        voice.getName() + " · " + locale + " · " + quality + network));
            }
            java.util.Collections.sort(result, (a, b) -> a.label.compareToIgnoreCase(b.label));
        } catch (Exception e) {
            Log.w(TAG, "Could not enumerate TTS voices", e);
        }
        return result;
    }

    public void setTtsVoice(String voiceName) {
        requestedTtsVoice = voiceName == null ? "" : voiceName.trim();
        main.post(this::applyRequestedTtsVoice);
    }

    private void applyRequestedTtsVoice() {
        if (tts == null || !ttsReady || requestedTtsVoice.isEmpty()) return;
        try {
            java.util.Set<Voice> voices = tts.getVoices();
            if (voices == null) return;
            for (Voice voice : voices) {
                if (voice != null && requestedTtsVoice.equals(voice.getName())
                        && voice.getLocale() != null
                        && "ru".equalsIgnoreCase(voice.getLocale().getLanguage())) {
                    int result = tts.setVoice(voice);
                    if (result == TextToSpeech.ERROR) {
                        Log.w(TAG, "TTS rejected selected voice " + requestedTtsVoice);
                    } else {
                        Log.i(TAG, "Selected TTS voice " + requestedTtsVoice);
                    }
                    return;
                }
            }
            Log.w(TAG, "Selected TTS voice is unavailable in current engine: " + requestedTtsVoice);
        } catch (Exception e) {
            Log.w(TAG, "Could not apply selected TTS voice", e);
        }
    }

    public java.util.List<EngineChoice> getAvailableTtsEngines() {
        java.util.List<EngineChoice> result = new java.util.ArrayList<>();
        if (tts == null) return result;
        try {
            for (TextToSpeech.EngineInfo info : tts.getEngines()) {
                if (info != null && info.name != null && !info.name.trim().isEmpty()) {
                    result.add(new EngineChoice(info.name, info.label == null ? info.name : info.label.toString()));
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not enumerate TTS engines", e);
        }
        return result;
    }

    public String getRequestedTtsEngine() { return requestedTtsEngine; }
    public String getTtsPreference() { return ttsPreference; }

    public void setTtsEngine(String packageName) {
        String selected = packageName == null ? "system" : packageName.trim();
        if (selected.isEmpty()) selected = "system";
        if (selected.equals(ttsPreference)) return;
        ttsPreference = selected;
        ttsAutoFallbackDone = false;
        requestedTtsEngine = resolveTtsEngine(selected);
        autoRhVoiceSelected = "auto".equals(selected) && !requestedTtsEngine.isEmpty();
        main.post(this::restartTts);
    }

    public boolean isGigaReady() {
        try { return gigaAmEngine.isInstalled(); }
        catch (Throwable error) { return false; }
    }
    public boolean isModelReady() { return isGigaReady(); }
    public boolean isModelLoading() { return false; }
    public boolean isListening() { return listening; }
    public void setAsrEngine(String ignored) { selectedAsr = AsrSelector.GIGAAM; }
    public String getAsrEngine() { return AsrSelector.GIGAAM; }
    public void setAudioSource(int source) {
        if (source == MediaRecorder.AudioSource.MIC || source == MediaRecorder.AudioSource.VOICE_RECOGNITION)
            selectedAudioSource = source;
    }
    public int getAudioSource() { return selectedAudioSource; }
    public void setSilenceTimeoutMs(long value) {
        silenceTimeoutMs = Math.max(850L,Math.min(2600L,value));
    }
    public long getSilenceTimeoutMs() { return silenceTimeoutMs; }
    public String getAsrDiagnostics() {
        long freeMiB = context.getFilesDir().getUsableSpace() / (1024L * 1024L);
        return "Режим: только офлайн GigaAM v3 E2E CTC INT8"
                + "\nGigaAM: " + (isGigaReady() ? "файлы модели найдены" : "модель отсутствует")
                + "\nИсточник AudioRecord: " + (selectedAudioSource == MediaRecorder.AudioSource.MIC ? "MIC" : "VOICE_RECOGNITION")
                + "\nПауза для автоотправки: " + silenceTimeoutMs + " мс"
                + "\nПоследнее распознавание: " + lastAsrEngine
                + "\nОбработка после записи: " + lastAsrMillis + " мс"
                + "\nАудиовход: " + lastAudioRoute
                + "\nПоследний RMS: " + lastAudioRms
                + "\nСвободно: " + freeMiB + " МиБ"
                + (lastAsrError.isEmpty() ? "" : "\nПоследняя ошибка: " + lastAsrError);
    }
    public boolean isTtsReady() { return ttsReady; }
    public String getTtsShortState() { return ttsReady ? "готова" : ttsState; }

    public String getTtsDiagnostics() {
        StringBuilder out = new StringBuilder();
        out.append("ready=").append(ttsReady)
                .append("; state=").append(ttsState)
                .append("; engine=").append(ttsEngineName)
                .append("; ru-RU=").append(ttsLanguageStatus);
        if (tts != null) {
            try {
                java.util.List<TextToSpeech.EngineInfo> engines = tts.getEngines();
                out.append("; engines=");
                if (engines == null || engines.isEmpty()) {
                    out.append("нет доступных TTS-движков");
                } else {
                    for (int i = 0; i < engines.size(); i++) {
                        if (i > 0) out.append(", ");
                        out.append(engines.get(i).name);
                    }
                }
            } catch (Exception e) {
                out.append("; engines_error=").append(e.getClass().getSimpleName());
            }
        } else {
            out.append("; engine_instance=null");
        }
        return out.toString();
    }

    public void setVoiceSettings(float rate, float pitch) {
        speechRate = Math.max(0.65f, Math.min(1.4f, rate));
        speechPitch = Math.max(0.7f, Math.min(1.3f, pitch));
        main.post(() -> {
            if (tts != null && ttsReady) {
                tts.setSpeechRate(speechRate);
                tts.setPitch(speechPitch);
            }
        });
    }

    public void stopSpeaking() {
        if (tts != null) {
            try { tts.stop(); } catch (Exception ignored) { }
        }
    }

    /** Starts a capture session. The caller should stop it when the remote mic key is released. */
    public synchronized void startListening() {
        startListening(false);
    }

    /** Hands-free capture ends a phrase after speech followed by silence, or times out safely. */
    public synchronized void startListening(boolean handsFree) {
        if (destroyed || listening) return;
        if (!isGigaReady()) {
            postError("GigaAM v3 не установлена. Запусти офлайн-установщик с ПК.");
            return;
        }
        stopRequested = false;
        deliverResult = true;
        handsFreeCapture = handsFree;
        listening = true;
        int chosenSource = selectedAudioSource;
        captureThread = new Thread(() -> captureFromRemoteMic(chosenSource), "AerialVoice-GigaAM-RCIn");
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

    /** Only RC In / MIBT.VoiceControl is accepted: no silent TV microphone fallback. */
    private void captureFromRemoteMic(int audioSource) {
        AudioRecord record = null;
        String failure = null;
        String result = "";
        boolean routeConfirmed = false;
        boolean noSpeechTimeout = false;
        final short[] buffered = new short[SAMPLE_RATE * 35];
        int bufferedCount = 0;
        final boolean handsFreeSession = handsFreeCapture;
        lastAsrError = "";
        lastAudioRms = 0;
        try {
            AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (audioManager == null) throw new IOException("AudioManager недоступен.");
            AudioDeviceInfo remoteInput = findRemoteMic(audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS));
            if (remoteInput == null) {
                lastAudioRoute = "RC In не найден";
                throw new IOException("Вход RC In / MIBT.VoiceControl не найден. Открой тест микрофона.");
            }
            int minimumBuffer = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minimumBuffer <= 0) throw new IOException("Ошибка аудиобуфера: " + minimumBuffer);
            AudioFormat format = new AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build();
            record = new AudioRecord.Builder()
                    .setAudioSource(audioSource)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(Math.max(minimumBuffer * 2, FRAME_SAMPLES * 4))
                    .build();
            if (record.getState() != AudioRecord.STATE_INITIALIZED)
                throw new IOException("AudioRecord не инициализировался.");
            boolean requested = record.setPreferredDevice(remoteInput);
            Log.i(TAG, "RC In preferred=" + describe(remoteInput) + "; accepted=" + requested);
            activeRecord = record;
            record.startRecording();
            if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
                throw new IOException("Android не запустил запись.");
            short[] samples = new short[FRAME_SAMPLES];
            long routeDeadline = android.os.SystemClock.elapsedRealtime() + 6000L;
            AudioDeviceInfo actual = null;
            while (!stopRequested && android.os.SystemClock.elapsedRealtime() < routeDeadline) {
                actual = record.getRoutedDevice();
                if (isRemoteMic(actual, remoteInput)) { routeConfirmed = true; break; }
                int read = record.read(samples, 0, samples.length, AudioRecord.READ_BLOCKING);
                if (read < 0 && !stopRequested)
                    throw new IOException("Ошибка ожидания RC In: " + read);
            }
            lastAudioRoute = describe(actual);
            if (!routeConfirmed && !stopRequested)
                throw new IOException("Android не подтвердил RC In. Реальный вход: " + describe(actual));
            if (routeConfirmed) {
                postStatus("RC In: запись для GigaAM v3 (" +
                        (audioSource == MediaRecorder.AudioSource.MIC ? "MIC" : "VOICE_RECOGNITION") + ")…");
                long startedAt = android.os.SystemClock.elapsedRealtime();
                SpeechEndpointDetector endpoint = new SpeechEndpointDetector(startedAt,silenceTimeoutMs);
                while (!stopRequested) {
                    int count = record.read(samples, 0, samples.length, AudioRecord.READ_BLOCKING);
                    if (count < 0) {
                        if (!stopRequested) throw new IOException("Ошибка чтения аудио: " + count);
                        break;
                    }
                    if (count == 0) continue;
                    lastAudioRms = rms(samples, count);
                    if (handsFreeSession) {
                        long now = android.os.SystemClock.elapsedRealtime();
                        endpoint.receive(lastAudioRms,now);
                        if (endpoint.noSpeechTimeout(now)) {
                            noSpeechTimeout = true;
                            deliverResult = false;
                            break;
                        }
                        if (endpoint.phraseDone(now)) break;
                    }
                    if (bufferedCount + count > buffered.length)
                        throw new IOException("Фраза длиннее 35 секунд. Повтори её короче.");
                    System.arraycopy(samples, 0, buffered, bufferedCount, count);
                    bufferedCount += count;
                }
            }
            // Android input must be released before decoding a potentially long utterance.
            activeRecord = null;
            if (record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) record.stop();
            record.release();
            record = null;
            if (deliverResult && routeConfirmed && bufferedCount > 0) {
                long inferenceStart = android.os.SystemClock.elapsedRealtime();
                postStatus("GigaAM v3: распознаю офлайн…");
                try {
                    result = gigaAmEngine.transcribe(buffered, bufferedCount).trim();
                } catch (Throwable error) {
                    lastAsrError = "GigaAM: " + error.getClass().getSimpleName() +
                            (error.getMessage() == null ? "" : ": " + error.getMessage());
                    Log.e(TAG, "GigaAM offline recognition failed", error);
                    failure = lastAsrError;
                }
                lastAsrMillis = android.os.SystemClock.elapsedRealtime() - inferenceStart;
                lastAsrEngine = "GigaAM v3";
            }
        } catch (Exception error) {
            if (!stopRequested || deliverResult) failure = safeMessage(error);
            lastAsrError = safeMessage(error);
            Log.e(TAG, "RC In capture failed", error);
        } finally {
            activeRecord = null;
            if (record != null) {
                try {
                    if (record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) record.stop();
                } catch (Exception ignored) { }
                try { record.release(); } catch (Exception ignored) { }
            }
            listening = false;
            final String phrase = result;
            final String error = failure;
            final boolean timedOut = noSpeechTimeout;
            final boolean notify = !destroyed && (deliverResult || timedOut);
            handsFreeCapture = false;
            if (destroyed) closeModelIfIdle();
            if (notify) {
                main.post(() -> {
                    if (destroyed) return;
                    if (timedOut) listener.onNoSpeechTimeout();
                    else if (!phrase.isEmpty()) listener.onFinal(phrase);
                    else if (error != null && !error.isEmpty()) listener.onError("Распознавание: " + error);
                    else listener.onError("Речь не распознана. Удерживай кнопку микрофона во время фразы.");
                });
            }
        }
    }

    private long rms(short[] samples, int count) {
        if (samples == null || count <= 0) return 0L;
        long sum = 0L;
        for (int i = 0; i < count; i++) {
            long value = samples[i];
            sum += value * value;
        }
        return (long) Math.sqrt((double) sum / count);
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

    public void speak(String text) {
        if (text == null || text.trim().isEmpty()) return;
        if (tts == null || !ttsReady) {
            postStatus("Ответ получен, но русская озвучка пока не готова. " + getTtsDiagnostics());
            return;
        }
        String spoken = text.replaceAll("(?s)\\x60\\x60\\x60.*?\\x60\\x60\\x60", "код")
                .replaceAll("https?://\\S+", "ссылка")
                .replaceAll("[*_#~]", "")
                .replaceAll("\\s+", " ")
                .trim();
        if (spoken.isEmpty()) return;
        int result = tts.speak(spoken, TextToSpeech.QUEUE_FLUSH, null,
                "aerial-voice-answer-" + System.currentTimeMillis());
        if (result == TextToSpeech.ERROR) {
            postStatus("Android TTS вернул ERROR при запуске озвучки. " + getTtsDiagnostics());
        }
    }

    @Override public void onInit(int status) {
        if (destroyed) return;
        if (tts != null) {
            try {
                String engine = requestedTtsEngine.isEmpty() ? tts.getDefaultEngine() : requestedTtsEngine;
                if (engine != null && !engine.trim().isEmpty()) ttsEngineName = engine;
            } catch (Exception ignored) { }
        }
        Log.i(TAG, "TTS init callback status=" + status + ", engine=" + ttsEngineName);
        if (status == TextToSpeech.SUCCESS && tts != null) {
            int language = tts.setLanguage(new Locale("ru", "RU"));
            ttsLanguageStatus = languageDescription(language);
            ttsReady = language != TextToSpeech.LANG_MISSING_DATA
                    && language != TextToSpeech.LANG_NOT_SUPPORTED;
            if (!ttsReady && autoRhVoiceSelected && !ttsAutoFallbackDone) {
                fallBackFromRhVoice("RHVoice не предоставил русский голос");
                return;
            }
            if (ttsReady) {
                tts.setSpeechRate(speechRate);
                tts.setPitch(speechPitch);
                ttsState = "готова";
                applyRequestedTtsVoice();
            } else if (language == TextToSpeech.LANG_MISSING_DATA) {
                ttsState = "нет данных русского языка";
            } else {
                ttsState = "движок не поддерживает русский";
            }
            postTtsReady(ttsReady, ttsReady
                    ? "Русская озвучка готова (" + ttsEngineName + ")"
                    : "Русская озвучка недоступна: " + ttsState + ". " + getTtsDiagnostics());
        } else {
            if (autoRhVoiceSelected && !ttsAutoFallbackDone) {
                fallBackFromRhVoice("RHVoice не запустился, код=" + status);
                return;
            }
            ttsReady = false;
            ttsState = "ошибка инициализации, код=" + status;
            postTtsReady(false, "Не удалось инициализировать синтез речи. " + getTtsDiagnostics());
        }
    }

    private void fallBackFromRhVoice(String reason) {
        ttsAutoFallbackDone = true;
        autoRhVoiceSelected = false;
        requestedTtsEngine = "";
        postStatus(reason + ". Переключаюсь на системный TTS.");
        main.post(this::restartTts);
    }

    private String languageDescription(int status) {
        if (status == TextToSpeech.LANG_MISSING_DATA) return "LANG_MISSING_DATA";
        if (status == TextToSpeech.LANG_NOT_SUPPORTED) return "LANG_NOT_SUPPORTED";
        if (status == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE) return "LANG_COUNTRY_VAR_AVAILABLE";
        if (status == TextToSpeech.LANG_COUNTRY_AVAILABLE) return "LANG_COUNTRY_AVAILABLE";
        if (status == TextToSpeech.LANG_AVAILABLE) return "LANG_AVAILABLE";
        return "status=" + status;
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
        gigaAmEngine.close();

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
