package app.orchard.tv.aerialvoice;
import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.util.Locale;

public final class RemoteMicTester {
    public interface Listener {
        void onStatus(String message);
        void onLevel(String message);
        void onFinished(String message);
    }
    private static final String TAG = "AerialVoiceSpeech";
    private static final int SAMPLE_RATE = 16000;
    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean running;
    private volatile AudioRecord activeRecord;
    private Thread worker;

    public RemoteMicTester(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }
    public static String listInputDevices(Context context) {
        AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (manager == null) return "AudioManager недоступен";
        AudioDeviceInfo[] devices = manager.getDevices(AudioManager.GET_DEVICES_INPUTS);
        if (devices.length == 0) return "Android не сообщил доступные входы";
        StringBuilder result = new StringBuilder();
        for (AudioDeviceInfo device : devices) {
            if (result.length() > 0) result.append('\n');
            result.append("• ").append(device.getProductName())
                    .append(" | id=").append(device.getId())
                    .append(" | type=").append(device.getType());
        }
        return result.toString();
    }
    public synchronized void start(int source, String sourceName) {
        stop();
        Thread previous = worker;
        if (previous != null && previous.isAlive()) {
            try { previous.join(400L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        running = true;
        worker = new Thread(() -> runCapture(source, sourceName), "AerialVoice-RCIn-Test");
        worker.start();
    }
    public void stop() {
        running = false;
        AudioRecord record = activeRecord;
        if (record != null && record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
            try { record.stop(); } catch (Exception ignored) { }
        }
        Thread current = worker;
        if (current != null && current != Thread.currentThread() && current.isAlive()) {
            try { current.join(700L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }
    private void runCapture(int source, String sourceName) {
        AudioRecord record = null;
        String finishMessage = "Тест остановлен.";
        try {
            AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (manager == null) { finishMessage = "AudioManager недоступен."; return; }
            AudioDeviceInfo target = findRcInput(manager.getDevices(AudioManager.GET_DEVICES_INPUTS));
            if (target == null) {
                finishMessage = "RC In не найден среди входов, доступных приложению.\n" + listInputDevices(context);
                Log.w(TAG, "RC In test: no RC In in AudioManager input device list");
                return;
            }
            Log.i(TAG, "RC In test: found target id=" + target.getId() + " name=" + target.getProductName() + " type=" + target.getType());
            postStatus("Найден RC In (id=" + target.getId() + "). Создаю AudioRecord…");
            int minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minBuffer <= 0) { finishMessage = "Android не смог определить размер буфера AudioRecord: " + minBuffer; return; }
            AudioFormat format = new AudioFormat.Builder().setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build();
            record = new AudioRecord.Builder().setAudioSource(source).setAudioFormat(format)
                    .setBufferSizeInBytes(Math.max(minBuffer, 8192)).build();
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                finishMessage = "AudioRecord не инициализировался для источника " + sourceName + ".";
                return;
            }
            boolean preferredAccepted = record.setPreferredDevice(target);
            Log.i(TAG, "RC In test: source=" + sourceName + " setPreferredDevice=" + preferredAccepted);
            record.startRecording();
            Thread.sleep(450L);
            AudioDeviceInfo routed = record.getRoutedDevice();
            Log.i(TAG, "RC In test: actual route=" + describe(routed));
            if (!isRcInput(routed, target)) {
                finishMessage = "Android не направил запись в RC In.\n" +
                        "Запрошен: " + describe(target) + "\n" +
                        "Фактический вход: " + describe(routed) + "\n" +
                        "setPreferredDevice: " + (preferredAccepted ? "принят" : "отклонён") + ".\n" +
                        "Тест остановлен, чтобы не записывать звук со встроенного микрофона.";
                return;
            }
            activeRecord = record;
            postStatus("Маршрут подтверждён: RC In · " + sourceName + " · id=" + routed.getId());
            short[] samples = new short[1600];
            long lastLog = 0L;
            while (running) {
                int count = record.read(samples, 0, samples.length, AudioRecord.READ_BLOCKING);
                if (count > 0) {
                    double sumSquares = 0.0;
                    int peak = 0;
                    for (int i = 0; i < count; i++) {
                        int value = samples[i];
                        int absolute = Math.abs(value);
                        if (absolute > peak) peak = absolute;
                        sumSquares += (double) value * value;
                    }
                    double rms = Math.sqrt(sumSquares / count);
                    double dbfs = rms < 1.0 ? -90.0 : 20.0 * Math.log10(rms / 32768.0);
                    String signal = dbfs > -60.0 ? "СИГНАЛ ЕСТЬ" : "ПОКА ТИШИНА";
                    String message = String.format(Locale.US, "Вход: RC In (%s)\nУровень: %.1f dBFS\nПик: %d / 32768\n%s", sourceName, dbfs, peak, signal);
                    postLevel(message);
                    long now = System.currentTimeMillis();
                    if (now - lastLog >= 1500L) {
                        Log.i(TAG, "RC In level: source=" + sourceName + " dbfs=" + String.format(Locale.US, "%.1f", dbfs) + " peak=" + peak);
                        lastLog = now;
                    }
                } else if (count < 0) {
                    if (running) { finishMessage = "AudioRecord вернул ошибку чтения: " + count; Log.w(TAG, finishMessage); }
                    break;
                }
            }
        } catch (SecurityException e) {
            finishMessage = "Нет доступа к микрофону: " + safeMessage(e); Log.e(TAG, finishMessage, e);
        } catch (Exception e) {
            finishMessage = "Ошибка теста RC In: " + safeMessage(e); Log.e(TAG, finishMessage, e);
        } finally {
            activeRecord = null;
            if (record != null) {
                try { if (record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) record.stop(); } catch (Exception ignored) { }
                try { record.release(); } catch (Exception ignored) { }
            }
            running = false;
            postFinished(finishMessage);
        }
    }
    private AudioDeviceInfo findRcInput(AudioDeviceInfo[] devices) {
        for (AudioDeviceInfo device : devices) {
            String name = String.valueOf(device.getProductName()).trim();
            if ("RC In".equalsIgnoreCase(name) || name.toLowerCase(Locale.ROOT).contains("rc in")) return device;
        }
        return null;
    }
    private boolean isRcInput(AudioDeviceInfo actual, AudioDeviceInfo target) {
        if (actual == null) return false;
        if (actual.getId() == target.getId()) return true;
        String name = String.valueOf(actual.getProductName()).toLowerCase(Locale.ROOT);
        return name.equals("rc in") || name.contains("rc in");
    }
    private String describe(AudioDeviceInfo device) {
        if (device == null) return "не определён (null)";
        return String.valueOf(device.getProductName()) + " (id=" + device.getId() + ", type=" + device.getType() + ")";
    }
    private String safeMessage(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
    private void postStatus(String message) { main.post(() -> listener.onStatus(message)); }
    private void postLevel(String message) { main.post(() -> listener.onLevel(message)); }
    private void postFinished(String message) { main.post(() -> listener.onFinished(message)); }
}
