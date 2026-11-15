package app.orchard.tv.aerialvoice;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;



public final class MainActivity extends Activity implements SpeechEngine.Listener {
    private static final int REQUEST_AUDIO = 4101;
    private static final String PREFS = "aerial_voice_settings";
    private static final String HISTORY_KEY = "conversation_history_json";
    private static final String ARCHIVE_KEY = "conversation_archive_json";
    private static final int MAX_HISTORY_MESSAGES = 30;
    private static final String EXTRA_START_LISTENING = "app.orchard.tv.aerialvoice.START_LISTENING";
    private static final String EXTRA_STOP_LISTENING = "app.orchard.tv.aerialvoice.STOP_LISTENING";
    private static final String EXTRA_SET_CONTINUOUS = "app.orchard.tv.aerialvoice.SET_CONTINUOUS";
    private static final String EXTRA_CONTINUOUS = "app.orchard.tv.aerialvoice.CONTINUOUS_MODE";
    private static final int BG = Color.rgb(7, 17, 31);
    private static final int PANEL = Color.rgb(16, 31, 49);
    private static final int PANEL_ALT = Color.rgb(21, 41, 62);
    private static final int TEXT = Color.rgb(238, 245, 252);
    private static final int MUTED = Color.rgb(151, 171, 192);
    private static final int CYAN = Color.rgb(56, 189, 248);
    private static final int GREEN = Color.rgb(52, 211, 153);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final JSONArray history = new JSONArray();
    private SpeechEngine speech;
    private boolean continuousMode;
    private boolean remotePttActive;
    private LinearLayout root;
    private TextView statusText;
    private TextView transcriptText;
    private TextView answerText;
    private TextView diagnosticsText;
    private Button listenButton;
    private Button dialogueButton;
    private Button asrButton;
    private Button autoStartButton;
    private boolean pendingRemoteStart;
    private boolean pendingRemoteStop;
    private final Runnable pendingRemoteStartRunnable = () -> {
        if (!pendingRemoteStart) return;
        pendingRemoteStart = false;
        startListening(false);
        if (pendingRemoteStop) {
            pendingRemoteStop = false;
            if (speech != null) speech.stopListening();
        }
    };
    private final Runnable autoListenRunnable = () -> {
        if (continuousMode && !remotePttActive && speech != null && !speech.isListening()) {
            setStatus("Свободный диалог: проверяю, доступен ли микрофон без удержания кнопки…", CYAN);
            startListening(true);
        }
    };
    private RemoteMicTester remoteMicTester;
    private AlertDialog remoteMicDialog;
    private final Runnable activityF5Long = () -> {
        continuousMode = true;
        updateFeatureButtons();
        setStatus("Режим диалога включён. Отпусти кнопку после фразы.", GREEN);
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        String selectedEngine = getSharedPreferences(PREFS, MODE_PRIVATE).getString("tts_engine", "auto");
        speech = new SpeechEngine(this, this, selectedEngine);
        speech.setAsrEngine(getSharedPreferences(PREFS, MODE_PRIVATE).getString("asr_engine", AsrSelector.AUTO));
        speech.setAudioSource(getSharedPreferences(PREFS, MODE_PRIVATE).getInt(
                "asr_audio_source", MediaRecorder.AudioSource.VOICE_RECOGNITION));
        speech.setTtsVoice(getSharedPreferences(PREFS, MODE_PRIVATE).getString("tts_voice", ""));
        String bootstrapStatus = importBootstrapKey();
        String historyStatus = importBootstrapHistory();
        applyVoiceSettings();
        loadHistory();
        if (historyStatus != null && !historyStatus.isEmpty()) {
            bootstrapStatus = bootstrapStatus == null || bootstrapStatus.isEmpty()
                    ? historyStatus : bootstrapStatus + " · " + historyStatus;
        }
        buildUi();
        refreshDiagnostics();
        if (bootstrapStatus != null && !bootstrapStatus.isEmpty()) {
            boolean imported = bootstrapStatus.startsWith("Authorization Key импортирован");
            setStatus(bootstrapStatus, imported ? GREEN : Color.rgb(251, 146, 60));
        }
        handleLaunchIntent(getIntent());
    }

    /**
     * One-time ADB bootstrap: reads a temporary key from app-specific external storage,
     * encrypts it with Android Keystore via SecretStore, then wipes and deletes the file.
     */
    /** One-time migration of the old app's plain conversation history; no API key is read here. */
    private String importBootstrapHistory() {
        java.io.File file = null;
        boolean imported = false;
        try {
            java.io.File externalDir = getExternalFilesDir(null);
            if (externalDir == null) return "";
            file = new java.io.File(externalDir, "aerial_voice_bootstrap_history.json");
            if (!file.isFile()) return "";
            if (file.length() > 256 * 1024) throw new IllegalStateException("Файл истории превышает 256 КБ.");
            String json;
            try (java.io.InputStream input = new java.io.FileInputStream(file);
                 java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
                json = new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8).trim();
            }
            JSONArray source = new JSONArray(json);
            JSONArray trimmed = new JSONArray();
            int start = Math.max(0, source.length() - MAX_HISTORY_MESSAGES);
            for (int i = start; i < source.length(); i++) {
                JSONObject item = source.optJSONObject(i);
                if (item == null) continue;
                String role = item.optString("role", "");
                String content = item.optString("content", "").trim();
                if (("user".equals(role) || "assistant".equals(role)) && !content.isEmpty()) {
                    trimmed.put(new JSONObject().put("role", role).put("content", content)
                            .put("timestamp", item.optLong("timestamp", System.currentTimeMillis())));
                }
            }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(HISTORY_KEY, trimmed.toString()).apply();
            imported = true;
            return "История старого помощника перенесена (" + trimmed.length() + " сообщений)";
        } catch (Exception e) {
            String message = e.getMessage();
            return "Не удалось перенести историю: "
                    + (message == null || message.trim().isEmpty() ? e.getClass().getSimpleName() : message);
        } finally {
            if (imported && file != null && file.exists()) {
                try {
                    long length = file.length();
                    if (length > 0 && length <= 256 * 1024) {
                        try (java.io.FileOutputStream output = new java.io.FileOutputStream(file, false)) {
                            output.write(new byte[(int) length]);
                        }
                    }
                    file.delete();
                } catch (Exception ignored) { }
            }
        }
    }

    private String importBootstrapKey() {
        java.io.File bootstrapFile = null;
        boolean saved = false;
        boolean attemptedImport = false;
        try {
            java.io.File externalDir = getExternalFilesDir(null);
            if (externalDir == null) return "";
            bootstrapFile = new java.io.File(externalDir, "aerial_voice_bootstrap_key.txt");
            if (!bootstrapFile.isFile()) return "";
            attemptedImport = true;
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            try (java.io.InputStream input = new java.io.FileInputStream(bootstrapFile)) {
                byte[] buffer = new byte[1024];
                int count;
                int total = 0;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > 16384) throw new IllegalStateException("Файл первичной настройки слишком большой.");
                    bytes.write(buffer, 0, count);
                }
            }
            String key = new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8).trim();
            if (key.isEmpty()) throw new IllegalStateException("Файл первичной настройки пуст.");
            SecretStore.save(this, key);
            // Verify the encrypted key can immediately be decrypted again.
            saved = key.equals(SecretStore.read(this));
            if (!saved) throw new IllegalStateException("Не удалось подтвердить сохранение ключа.");
            return "Authorization Key импортирован и зашифрован на телевизоре";
        } catch (Exception e) {
            String message = e.getMessage();
            return "Не удалось импортировать Authorization Key: "
                    + (message == null || message.trim().isEmpty() ? e.getClass().getSimpleName() : message);
        } finally {
            if (attemptedImport && bootstrapFile != null) {
                // Delete plaintext even after a failed import.
                if (bootstrapFile.exists()) {
                    try {
                        long size = bootstrapFile.length();
                        if (size > 0 && size <= 16384) {
                            try (java.io.FileOutputStream output =
                                         new java.io.FileOutputStream(bootstrapFile, false)) {
                                output.write(new byte[(int) size]);
                                output.flush();
                            }
                        }
                    } catch (Exception ignored) { }
                    // Flash-memory erasure cannot be guaranteed.
                    //noinspection ResultOfMethodCallIgnored
                    bootstrapFile.delete();
                }
                // Non-secret acknowledgement read by the Windows ADB installer.
                try (java.io.FileOutputStream acknowledgement = new java.io.FileOutputStream(
                        new java.io.File(bootstrapFile.getParentFile(), "aerial_voice_bootstrap_key.status"))) {
                    acknowledgement.write((saved ? "ok" : "error")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    acknowledgement.flush();
                } catch (Exception e) {
                    Log.w("AerialVoice", "Could not write bootstrap acknowledgement", e);
                }
            }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleLaunchIntent(intent);
    }

    private void handleLaunchIntent(Intent intent) {
        if (intent == null) return;
        if (intent.getBooleanExtra(EXTRA_SET_CONTINUOUS, false)) {
            continuousMode = intent.getBooleanExtra(EXTRA_CONTINUOUS, false);
            updateFeatureButtons();
        }
        boolean start = intent.getBooleanExtra(EXTRA_START_LISTENING, false);
        boolean stop = intent.getBooleanExtra(EXTRA_STOP_LISTENING, false);
        if (start) {
            remotePttActive = true;
            pendingRemoteStart = true;
            pendingRemoteStop = false;
            handler.removeCallbacks(pendingRemoteStartRunnable);
            handler.post(pendingRemoteStartRunnable);
        }
        if (stop) {
            remotePttActive = false;
            handler.removeCallbacks(pendingRemoteStartRunnable);
            if (speech != null && speech.isListening()) {
                speech.stopListening();
            } else if (pendingRemoteStart) {
                pendingRemoteStart = false;
                pendingRemoteStop = false;
                setStatus("Микрофон не успел начать запись. Повтори нажатие и удерживай кнопку чуть дольше.", Color.rgb(251, 146, 60));
            }
        }
    }

    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(34), dp(24), dp(34), dp(22));
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(9, 24, 42), Color.rgb(7, 17, 31), Color.rgb(11, 29, 48)}));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand = text("AERIAL", 18, CYAN, true);
        TextView title = text("VOICE", 34, TEXT, true);
        title.setPadding(dp(12), 0, 0, 0);
        header.addView(brand, new LinearLayout.LayoutParams(-2, -2));
        header.addView(title, new LinearLayout.LayoutParams(-2, -2));
        TextView version = text("AERIAL VOICE · 0.6.3", 13, MUTED, true);
        version.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        header.addView(version, new LinearLayout.LayoutParams(0, -1, 1));
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(54)));

        statusText = text("Готов к работе", 19, GREEN, true);
        statusText.setPadding(0, dp(8), 0, dp(16));
        root.addView(statusText);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        root.addView(scroll, scrollLp);

        LinearLayout hero = panel(this, PANEL, 22);
        TextView heroTitle = text("Слушаю тебя.", 30, TEXT, true);
        TextView heroSub = text("Удерживай микрофон пульта во время фразы и отпусти для отправки.\nДля проверки RC In используй тест ниже; GigaAM и Vosk работают офлайн.", 17, MUTED, false);
        heroSub.setPadding(0, dp(8), 0, dp(14));
        hero.addView(heroTitle);
        hero.addView(heroSub);
        listenButton = button("●  НАЧАТЬ РАЗГОВОР", true);
        listenButton.setOnClickListener(v -> {
            if (speech != null && speech.isListening()) {
                speech.stopListening();
            } else {
                continuousMode = false;
                startListening();
            }
        });
        hero.addView(listenButton, new LinearLayout.LayoutParams(-1, dp(62)));
        content.addView(hero, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(14), 0, dp(14));
        Button stop = button("Остановить", false);
        stop.setOnClickListener(v -> stopListening());
        Button settings = button("Настройки ИИ", false);
        settings.setOnClickListener(v -> showAiSettings());
        Button diag = button("Диагностика", false);
        diag.setOnClickListener(v -> showDiagnostics());
        addWeighted(actions, stop);
        addWeighted(actions, settings);
        addWeighted(actions, diag);
        content.addView(actions);

        LinearLayout secondaryActions = new LinearLayout(this);
        secondaryActions.setOrientation(LinearLayout.HORIZONTAL);
        secondaryActions.setPadding(0, 0, 0, dp(12));
        Button voiceSettings = button("Настройки голоса", false);
        voiceSettings.setOnClickListener(v -> showVoiceSettings());
        Button clearChat = button("Очистить диалог", false);
        clearChat.setOnClickListener(v -> confirmClearHistory());
        Button aiTest = button("Тест ИИ", false);
        aiTest.setOnClickListener(v -> testAiFromHome());
        addWeighted(secondaryActions, voiceSettings);
        addWeighted(secondaryActions, clearChat);
        addWeighted(secondaryActions, aiTest);
        content.addView(secondaryActions);

        LinearLayout featureActions = new LinearLayout(this);
        featureActions.setOrientation(LinearLayout.HORIZONTAL);
        featureActions.setPadding(0, 0, 0, dp(12));
        Button historyButton = button("История", false);
        historyButton.setOnClickListener(v -> showHistory());
        Button memoryButton = button("Долговременная память", false);
        memoryButton.setOnClickListener(v -> showMemorySettings());
        Button tvButton = button("Управление ТВ", false);
        tvButton.setOnClickListener(v -> showTvControls());
        addWeighted(featureActions, historyButton);
        addWeighted(featureActions, memoryButton);
        addWeighted(featureActions, tvButton);
        content.addView(featureActions);

        LinearLayout modeActions = new LinearLayout(this);
        modeActions.setOrientation(LinearLayout.HORIZONTAL);
        modeActions.setPadding(0, 0, 0, dp(12));
        dialogueButton = button("Диалог: ВЫКЛ", false);
        dialogueButton.setOnClickListener(v -> toggleDialogueMode());
        autoStartButton = button("Автозапуск: ВЫКЛ", false);
        autoStartButton.setOnClickListener(v -> toggleAutoStart());
        Button contextButton = button("Контекст ТВ", false);
        contextButton.setOnClickListener(v -> showTvContext());
        addWeighted(modeActions, dialogueButton);
        addWeighted(modeActions, autoStartButton);
        addWeighted(modeActions, contextButton);
        content.addView(modeActions);

        Button micTest = button("Проверить микрофон пульта · RC In", false);
        micTest.setOnClickListener(v -> showRemoteMicTest());
        LinearLayout.LayoutParams micTestLp = new LinearLayout.LayoutParams(-1, dp(50));
        micTestLp.bottomMargin = dp(12);
        content.addView(micTest, micTestLp);
        asrButton = button("Распознавание: Авто (GigaAM → Vosk)", false);
        asrButton.setOnClickListener(v -> showAsrSettings());
        LinearLayout.LayoutParams asrLp = new LinearLayout.LayoutParams(-1, dp(50));
        asrLp.bottomMargin = dp(12);
        content.addView(asrButton, asrLp);

        LinearLayout chat = panel(this, PANEL_ALT, 18);
        TextView transcriptLabel = text("ТЫ СКАЗАЛ", 12, CYAN, true);
        transcriptText = text("Пока пусто. Например: «Открой Zona» или «Сделай громче».", 19, TEXT, false);
        transcriptText.setPadding(0, dp(8), 0, dp(16));
        TextView answerLabel = text("AERIAL ОТВЕЧАЕТ", 12, CYAN, true);
        answerText = text("Я готов. Могу открывать приложения, управлять громкостью и воспроизведением, а после подключения ИИ — поддерживать свободный диалог.", 19, TEXT, false);
        answerText.setPadding(0, dp(8), 0, 0);
        chat.addView(transcriptLabel);
        chat.addView(transcriptText);
        chat.addView(answerLabel);
        chat.addView(answerText);
        LinearLayout.LayoutParams chatLp = new LinearLayout.LayoutParams(-1, -2);
        chatLp.bottomMargin = dp(12);
        content.addView(chat, chatLp);

        diagnosticsText = text("Проверяю компоненты…", 14, MUTED, false);
        root.addView(diagnosticsText);

        TextView footer = text("ПУЛЬТ: удерживай микрофон во время фразы и отпусти для отправки · долгое удержание включает диалог", 12, MUTED, false);
        footer.setPadding(0, dp(12), 0, 0);
        root.addView(footer);

        setContentView(root);
        updateFeatureButtons();
        listenButton.requestFocus();
    }

    private void addWeighted(LinearLayout parent, Button button) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(54), 1f);
        lp.setMargins(dp(5), 0, dp(5), 0);
        parent.addView(button, lp);
    }

    private LinearLayout panel(Context context, int color, int padding) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(padding), dp(padding), dp(padding), dp(padding));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(18));
        layout.setBackground(bg);
        return layout;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setIncludeFontPadding(true);
        return view;
    }

    private Button button(String label, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(primary ? 19 : 15);
        button.setAllCaps(false);
        button.setTextColor(TEXT);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setFocusable(true);
        button.setFocusableInTouchMode(true);
        button.setPadding(dp(14), dp(10), dp(14), dp(10));
        int normal = primary ? Color.rgb(12, 117, 170) : Color.rgb(35, 54, 74);
        int focused = primary ? Color.rgb(17, 151, 210) : Color.rgb(51, 82, 111);
        button.setBackground(new GradientDrawable() {{ setColor(normal); setCornerRadius(dp(14)); }});
        button.setOnFocusChangeListener((v, hasFocus) -> {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(hasFocus ? focused : normal);
            bg.setCornerRadius(dp(14));
            bg.setStroke(hasFocus ? dp(2) : dp(1), hasFocus ? Color.WHITE : Color.rgb(65, 88, 110));
            button.setBackground(bg);
        });
        return button;
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void startListening() { startListening(false); }

    private void startListening(boolean handsFree) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_AUDIO);
            return;
        }
        if (speech != null) speech.stopSpeaking();
        if (listenButton != null) listenButton.setText(handsFree ? "●  СЛУШАЮ СВОБОДНО…" : "●  СЛУШАЮ…");
        speech.startListening(handsFree);
    }

    private void stopListening() {
        continuousMode = false;
        remotePttActive = false;
        pendingRemoteStart = false;
        pendingRemoteStop = false;
        handler.removeCallbacks(pendingRemoteStartRunnable);
        handler.removeCallbacks(autoListenRunnable);
        handler.removeCallbacks(activityF5Long);
        if (speech != null) speech.cancel();
        if (listenButton != null) listenButton.setText("●  НАЧАТЬ РАЗГОВОР");
        updateFeatureButtons();
        setStatus("Остановлено", MUTED);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_AUDIO && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startListening();
        } else if (requestCode == REQUEST_AUDIO) {
            setStatus("Нужно разрешить доступ к микрофону", Color.rgb(251, 146, 60));
            setAnswer("Без разрешения на микрофон я не смогу распознавать речь.");
        }
    }

    @Override public void onStatus(String status) {
        setStatus(status, CYAN);
        refreshDiagnostics();
    }
    @Override public void onPartial(String text) { transcriptText.setText(text); }
    @Override public void onTtsReady(boolean ready, String detail) {
        setStatus(detail, ready ? GREEN : Color.rgb(251, 146, 60));
        refreshDiagnostics();
    }

    @Override public void onSpeechDone() {
        if (continuousMode) {
            setStatus("Ответ озвучен. Пытаюсь продолжить слушать автоматически…", GREEN);
            scheduleContinuousIfNeeded();
        } else {
            setStatus("Ответ озвучен", GREEN);
        }
    }

    @Override public void onNoSpeechTimeout() {
        if (listenButton != null) listenButton.setText("●  НАЧАТЬ РАЗГОВОР");
        setStatus("Микрофон RC In не передал речь без удержания F5", Color.rgb(251, 146, 60));
        setAnswer("Телевизор не передаёт звук с микрофона пульта в режиме свободного ожидания. Для следующей реплики удерживай кнопку микрофона. После ответа помощник снова попробует перейти в свободное прослушивание.");
    }

    @Override
    public void onFinal(String recognized) {
        if (listenButton != null) listenButton.setText("●  НАЧАТЬ РАЗГОВОР");
        remotePttActive = false;
        transcriptText.setText(recognized);
        addHistory("user", recognized);

        String normalized = recognized.toLowerCase(java.util.Locale.ROOT).replace('ё', 'е');
        if (normalized.startsWith("запомни ") || normalized.startsWith("запомни что ")) {
            String fact = recognized.replaceFirst("(?i)^\\s*запомни(?:\\s+что)?\\s*", "").trim();
            boolean saved = MemoryStore.add(this, fact);
            String message = saved ? "Запомнил. Сохранил это в долговременную память на телевизоре."
                    : "Этот факт уже сохранён или не удалось записать его в память.";
            setAnswer(message);
            addHistory("assistant", message);
            speech.speak(message);
            refreshDiagnostics();
            return;
        }
        if (normalized.contains("что ты помнишь") || normalized.contains("что ты обо мне помнишь")) {
            List<String> facts = MemoryStore.read(this);
            String message;
            if (facts.isEmpty()) message = "Пока долговременная память пустая. Скажи: запомни, что…";
            else {
                StringBuilder remembered = new StringBuilder("Я помню следующее: ");
                for (int i = 0; i < facts.size(); i++) {
                    if (i > 0) remembered.append("; ");
                    remembered.append(facts.get(i));
                }
                message = remembered.toString();
            }
            setAnswer(message);
            addHistory("assistant", message);
            speech.speak(message);
            return;
        }
        if (normalized.startsWith("забудь все") || normalized.startsWith("забудь всё")
                || normalized.equals("очисти память") || normalized.equals("удали всю память")) {
            MemoryStore.clear(this);
            String message = "Долговременная память очищена.";
            setAnswer(message);
            addHistory("assistant", message);
            speech.speak(message);
            refreshDiagnostics();
            return;
        }
        if (normalized.startsWith("забудь ")) {
            String phrase = recognized.replaceFirst("(?i)^\\s*забудь\\s*", "").trim();
            int removed = MemoryStore.forget(this, phrase);
            String message = removed > 0 ? "Удалил из памяти " + removed + " совпадающих факта."
                    : "Не нашёл в памяти факт с таким текстом.";
            setAnswer(message);
            addHistory("assistant", message);
            speech.speak(message);
            refreshDiagnostics();
            return;
        }
        if (normalized.contains("закончить диалог") || normalized.contains("заверши диалог")
                || normalized.contains("останови диалог") || normalized.contains("выйти из диалога")) {
            continuousMode = false;
            handler.removeCallbacks(autoListenRunnable);
            updateFeatureButtons();
            String message = "Хорошо, диалог завершён.";
            setAnswer(message);
            addHistory("assistant", message);
            speech.speak(message);
            setStatus("Диалог завершён", MUTED);
            return;
        }
        if (normalized.contains("очисти диалог") || normalized.contains("очисти историю")) {
            clearHistory();
            String message = "История диалога очищена.";
            setAnswer(message);
            speech.speak(message);
            setStatus(message, GREEN);
            return;
        }
        if (normalized.equals("повтори") || normalized.contains("повтори ответ")) {
            String previous = answerText == null ? "" : answerText.getText().toString();
            if (!previous.trim().isEmpty()) {
                speech.speak(previous);
                setStatus("Повторяю предыдущий ответ", GREEN);
            }
            return;
        }

        CommandRouter.Result local = CommandRouter.execute(this, recognized);
        if (local.handled) {
            setAnswer(local.message);
            addHistory("assistant", local.message);
            speech.speak(local.message);
            if (local.message.startsWith("Открываю ")) {
                continuousMode = false;
                handler.removeCallbacks(autoListenRunnable);
                updateFeatureButtons();
            } else if (!speech.isTtsReady()) scheduleContinuousIfNeeded();
            return;
        }

        if (!AiClient.isConfigured(this)) {
            String message = "Речь распознана. Локальные команды работают. Чтобы отвечать на свободные вопросы, открой «Настройки ИИ» и подключи совместимый API.";
            setAnswer(message);
            addHistory("assistant", message);
            speech.speak(message);
            setStatus("Распознавание работает · ИИ пока не подключён", MUTED);
            if (!speech.isTtsReady()) scheduleContinuousIfNeeded();
            return;
        }

        setStatus("Отправляю запрос ИИ…", CYAN);
        setAnswer("Думаю над ответом…");
        AiClient.ask(this, history, TvContextProvider.snapshot(this), MemoryStore.read(this), new AiClient.Callback() {
            @Override public void onStatus(String message) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) setStatus(message, CYAN);
                });
            }
            @Override public void onSuccess(String answer) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    setAnswer(answer);
                    addHistory("assistant", answer);
                    setStatus(continuousMode ? "Диалог активен" : "Готов к следующему вопросу", GREEN);
                    speech.speak(answer);
                    if (!speech.isTtsReady()) scheduleContinuousIfNeeded();
                });
            }
            @Override public void onError(String message) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    setAnswer(message);
                    addHistory("assistant", message);
                    setStatus("Не удалось получить ответ ИИ", Color.rgb(251, 146, 60));
                    speech.speak(message);
                    if (!speech.isTtsReady()) scheduleContinuousIfNeeded();
                });
            }
        });
    }

    private void testAiFromHome() {
        if (!AiClient.isConfigured(this)) {
            setStatus("GigaChat не настроен", Color.rgb(251, 146, 60));
            setAnswer("Не найден сохранённый Authorization Key. Открой «Настройки ИИ» и проверь подключение.");
            return;
        }
        setStatus("Запускаю отдельный тест GigaChat…", CYAN);
        setAnswer("Проверяю сохранённый ключ и реальный запрос к модели…");
        JSONArray probe = new JSONArray();
        try {
            probe.put(new JSONObject().put("role", "user")
                    .put("content", "Ответь коротко по-русски: «GigaChat работает»."));
        } catch (Exception e) {
            setAnswer("Не удалось подготовить тестовый запрос: " + e.getMessage());
            return;
        }
        AiClient.ask(this, probe, new AiClient.Callback() {
            @Override public void onStatus(String message) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) setStatus(message, CYAN);
                });
            }
            @Override public void onSuccess(String answer) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    setAnswer("ТЕСТ GIGACHAT: " + answer);
                    setStatus("GigaChat ответил. Проверяю озвучку…", GREEN);
                    speech.speak(answer);
                });
            }
            @Override public void onError(String message) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    setAnswer("ОШИБКА GIGACHAT: " + message);
                    setStatus("Тест GigaChat не пройден", Color.rgb(251, 146, 60));
                });
            }
        });
    }

    @Override
    public void onError(String message) {
        if (listenButton != null) listenButton.setText("●  НАЧАТЬ РАЗГОВОР");
        remotePttActive = false;
        setStatus(message, Color.rgb(251, 146, 60));
        setAnswer(message + " Если проблема повторяется, открой диагностику и проверь RC In.");
        if (continuousMode) {
            setStatus("Ошибка распознавания. Для следующей фразы удерживай F5; свободное прослушивание будет проверено после следующего ответа.", Color.rgb(251, 146, 60));
        }
        refreshDiagnostics();
    }

    private void scheduleContinuousIfNeeded() {
        if (!continuousMode) return;
        handler.removeCallbacks(autoListenRunnable);
        handler.postDelayed(autoListenRunnable, 750L);
    }

    private void loadHistory() {
        try {
            String saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(HISTORY_KEY, "");
            if (saved == null || saved.isEmpty()) return;
            JSONArray stored = new JSONArray(saved);
            for (int i = Math.max(0, stored.length() - MAX_HISTORY_MESSAGES); i < stored.length(); i++) {
                JSONObject item = stored.optJSONObject(i);
                if (item == null) continue;
                String role = item.optString("role", "");
                String content = item.optString("content", "").trim();
                if (("user".equals(role) || "assistant".equals(role)) && !content.isEmpty()) {
                    history.put(new JSONObject().put("role", role).put("content", content));
                }
            }
        } catch (Exception ignored) {
            while (history.length() > 0) history.remove(0);
        }
    }

    private void saveHistory() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(HISTORY_KEY, history.toString()).apply();
    }

    private void addHistory(String role, String content) {
        try {
            history.put(new JSONObject().put("role", role).put("content", content)
                    .put("timestamp", System.currentTimeMillis()));
            while (history.length() > MAX_HISTORY_MESSAGES) history.remove(0);
            saveHistory();
        } catch (Exception ignored) { }
    }

    private void clearHistory() {
        archiveCurrentHistory();
        while (history.length() > 0) history.remove(0);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(HISTORY_KEY).apply();
        if (transcriptText != null) transcriptText.setText("История очищена. Можем начать новый разговор.");
    }

    private void confirmClearHistory() {
        new AlertDialog.Builder(this)
                .setTitle("Очистить историю диалога?")
                .setMessage("Будут удалены сохранённые сообщения, которые ИИ использует как контекст. API-ключ и настройки не затрагиваются.")
                .setPositiveButton("Очистить", (d, w) -> {
                    clearHistory();
                    setAnswer("История диалога очищена.");
                    setStatus("История очищена", GREEN);
                })
                .setNegativeButton("Отмена", null).show();
    }

    private void applyVoiceSettings() {
        if (speech == null) return;
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        speech.setVoiceSettings(prefs.getFloat("tts_rate", 1.0f), prefs.getFloat("tts_pitch", 1.0f));
    }

    private void showAsrSettings() {
        if (speech == null) return;
        String[] names = {"Авто: GigaAM v3 → Vosk", "Только GigaAM v3", "Только Vosk"};
        String[] choices = {AsrSelector.AUTO, AsrSelector.GIGAAM, AsrSelector.VOSK};
        LinearLayout form = new LinearLayout(this);
        form.setPadding(dp(18), dp(12), dp(18), dp(10));
        form.setOrientation(LinearLayout.VERTICAL);
        form.addView(label("Движок распознавания русской речи"));
        Spinner picker = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        picker.setAdapter(adapter);
        String current = speech.getAsrEngine();
        picker.setSelection(AsrSelector.GIGAAM.equals(current) ? 1
                : AsrSelector.VOSK.equals(current) ? 2 : 0);
        form.addView(picker);
        form.addView(label("Аудиоисточник (маршрут RC In обязателен)"));
        Spinner sourcePicker = new Spinner(this);
        String[] audioNames = {"VOICE_RECOGNITION (по умолчанию)", "MIC (для некоторых прошивок)"};
        ArrayAdapter<String> sourceAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, audioNames);
        sourceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sourcePicker.setAdapter(sourceAdapter);
        sourcePicker.setSelection(speech.getAudioSource() == MediaRecorder.AudioSource.MIC ? 1 : 0);
        form.addView(sourcePicker);
        TextView detail = text(speech.getAsrDiagnostics()
                + "\n\nРежим «Авто» предпочитает GigaAM; при ошибке пробует Vosk."
                + "\nРежимы «Только…» не переключаются автоматически.", 14, MUTED, false);
        detail.setPadding(0, dp(12), 0, 0);
        form.addView(detail);
        new AlertDialog.Builder(this)
                .setTitle("Офлайн-распознавание")
                .setView(form)
                .setPositiveButton("Сохранить", (d, w) -> {
                    String next = choices[picker.getSelectedItemPosition()];
                    int source = sourcePicker.getSelectedItemPosition() == 1
                            ? MediaRecorder.AudioSource.MIC : MediaRecorder.AudioSource.VOICE_RECOGNITION;
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putString("asr_engine", next)
                            .putInt("asr_audio_source", source).apply();
                    speech.setAsrEngine(next);
                    speech.setAudioSource(source);
                    refreshDiagnostics();
                    setStatus("Распознавание и аудиоисточник сохранены для следующей записи.", GREEN);
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void showVoiceSettings() {
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(18), dp(8), dp(18), dp(4));
        EditText rate = field("Скорость речи: 0.65–1.4", String.valueOf(prefs.getFloat("tts_rate", 1.0f)),
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        EditText pitch = field("Высота голоса: 0.7–1.3", String.valueOf(prefs.getFloat("tts_pitch", 1.0f)),
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        List<SpeechEngine.EngineChoice> engineOptions = speech == null
                ? new ArrayList<>() : speech.getAvailableTtsEngines();
        List<String> engineLabels = new ArrayList<>();
        engineLabels.add("Автоматически: RHVoice, затем системный");
        engineLabels.add("Системный TTS (без RHVoice)");
        for (SpeechEngine.EngineChoice option : engineOptions) {
            engineLabels.add(option.label + " (" + option.packageName + ")");
        }
        Spinner enginePicker = new Spinner(this);
        ArrayAdapter<String> engineAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, engineLabels);
        engineAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        enginePicker.setAdapter(engineAdapter);
        String selectedEngine = prefs.getString("tts_engine", "auto");
        int selectedIndex = "system".equals(selectedEngine) || selectedEngine.isEmpty() ? 1 : 0;
        for (int i = 0; i < engineOptions.size(); i++) {
            if (engineOptions.get(i).packageName.equals(selectedEngine)) selectedIndex = i + 2;
        }
        enginePicker.setSelection(selectedIndex);
        form.addView(label("Скорость речи (1.0 — обычная)"));
        form.addView(rate);
        form.addView(label("Высота голоса (1.0 — обычная)"));
        form.addView(pitch);
        List<SpeechEngine.VoiceChoice> voiceOptions = speech == null
                ? new ArrayList<>() : speech.getAvailableTtsVoices();
        List<String> voiceLabels = new ArrayList<>();
        voiceLabels.add("Автоматический голос движка");
        for (SpeechEngine.VoiceChoice option : voiceOptions) voiceLabels.add(option.label);
        Spinner voicePicker = new Spinner(this);
        ArrayAdapter<String> voiceAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, voiceLabels);
        voiceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        voicePicker.setAdapter(voiceAdapter);
        String selectedVoice = prefs.getString("tts_voice", "");
        int voiceIndex = 0;
        for (int i = 0; i < voiceOptions.size(); i++) {
            if (voiceOptions.get(i).name.equals(selectedVoice)) voiceIndex = i + 1;
        }
        voicePicker.setSelection(voiceIndex);
        form.addView(label("Голосовой движок"));
        form.addView(enginePicker);
        form.addView(label("Конкретный русский голос"));
        form.addView(voicePicker);
        form.addView(label("Режим «Автоматически» выбирает установленный RHVoice. Если он не поддерживает русский, приложение использует системный движок. При смене TTS сохрани настройку и открой этот экран повторно для выбора нового голоса."));
        Button testVoice = button("Проверить русскую озвучку", false);
        form.addView(testVoice);
        AlertDialog voiceDialog = new AlertDialog.Builder(this).setTitle("Настройки голоса").setView(form)
                .setPositiveButton("Сохранить", (d, w) -> {
                    try {
                        float newRate = Float.parseFloat(rate.getText().toString().replace(',', '.'));
                        float newPitch = Float.parseFloat(pitch.getText().toString().replace(',', '.'));
                        if (newRate < 0.65f || newRate > 1.4f || newPitch < 0.7f || newPitch > 1.3f) {
                            throw new IllegalArgumentException("Значения вне допустимого диапазона.");
                        }
                        int engineIndex = enginePicker.getSelectedItemPosition();
                        String newEngine = engineIndex == 0 ? "auto"
                                : engineIndex == 1 ? "system"
                                : engineIndex - 2 >= engineOptions.size() ? "system"
                                : engineOptions.get(engineIndex - 2).packageName;
                        int voicePosition = voicePicker.getSelectedItemPosition();
                        String newVoice = voicePosition <= 0 || voicePosition - 1 >= voiceOptions.size()
                                ? "" : voiceOptions.get(voicePosition - 1).name;
                        prefs.edit().putFloat("tts_rate", newRate).putFloat("tts_pitch", newPitch)
                                .putString("tts_engine", newEngine).putString("tts_voice", newVoice).apply();
                        speech.setVoiceSettings(newRate, newPitch);
                        speech.setTtsVoice(newVoice);
                        speech.setTtsEngine(newEngine);
                        setStatus("Настройки голоса сохранены. Проверка озвучки доступна после инициализации.", GREEN);
                    } catch (Exception e) {
                        setStatus("Проверь скорость и высоту голоса", Color.rgb(251, 146, 60));
                        setAnswer("Скорость должна быть от 0.65 до 1.4, высота — от 0.7 до 1.3.");
                    }
                })
                .setNegativeButton("Отмена", null).create();
        testVoice.setOnClickListener(v -> {
            setStatus("Проверяю русскую озвучку…", CYAN);
            speech.speak("Проверка русской озвучки. Если ты слышишь эту фразу, синтез речи работает.");
        });
        voiceDialog.show();
    }

    private void setStatus(String value, int color) {
        if (statusText != null) { statusText.setText(value); statusText.setTextColor(color); }
    }

    private void setAnswer(String value) {
        if (answerText != null) answerText.setText(value);
    }

    private void showAiSettings() {
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(18), dp(8), dp(18), dp(4));

        EditText model = field("GigaChat-2", prefs.getString("gigachat_model", "GigaChat-2"), InputType.TYPE_CLASS_TEXT);
        EditText key = field("Authorization Key GigaChat", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        TextView note = text("Провайдер: GigaChat API · доступ для физлиц. Модель по умолчанию GigaChat-2 (Lite). Ключ сохраняется зашифрованным на телевизоре через Android Keystore. Токен доступа приложение получает и обновляет автоматически.", 13, MUTED, false);
        note.setPadding(0, dp(8), 0, dp(10));
        TextView testStatus = text("Вставь Authorization Key и проверь соединение. Для проверки запрос уйдёт в GigaChat.", 14, MUTED, false);
        testStatus.setPadding(0, dp(10), 0, dp(8));
        Button test = button("Проверить GigaChat", true);
        Button deleteKey = button("Удалить сохранённый ключ", false);

        form.addView(label("Модель GigaChat"));
        form.addView(model);
        form.addView(label("Authorization Key"));
        form.addView(key);
        form.addView(note);
        form.addView(test);
        form.addView(testStatus);
        form.addView(deleteKey);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Настройки GigaChat")
                .setView(form)
                .setPositiveButton("Сохранить", null)
                .setNegativeButton("Отмена", null)
                .create();

        test.setOnClickListener(v -> {
            String enteredKey = key.getText().toString().trim();
            if (enteredKey.isEmpty()) {
                try { enteredKey = SecretStore.read(this); }
                catch (Exception e) { testStatus.setText("Не удалось прочитать сохранённый ключ: " + e.getMessage()); return; }
            }
            test.setEnabled(false);
            testStatus.setText("Получаю OAuth-токен и проверяю ответ GigaChat…");
            AiClient.testConnection(this, model.getText().toString().trim(), enteredKey, new AiClient.Callback() {
                @Override public void onSuccess(String answer) {
                    runOnUiThread(() -> {
                        test.setEnabled(true);
                        testStatus.setText(answer);
                        testStatus.setTextColor(GREEN);
                    });
                }
                @Override public void onError(String message) {
                    runOnUiThread(() -> {
                        test.setEnabled(true);
                        testStatus.setText("Ошибка: " + message);
                        testStatus.setTextColor(Color.rgb(251, 146, 60));
                    });
                }
            });
        });

        deleteKey.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Удалить сохранённый ключ?")
                .setMessage("Authorization Key будет удалён из зашифрованного хранилища телевизора.")
                .setPositiveButton("Удалить", (d, w) -> {
                    try {
                        SecretStore.save(this, "");
                        key.setText("");
                        testStatus.setText("Сохранённый ключ удалён.");
                        refreshDiagnostics();
                    } catch (Exception e) {
                        testStatus.setText("Не удалось удалить ключ: " + e.getMessage());
                    }
                }).setNegativeButton("Отмена", null).show());

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String newModel = model.getText().toString().trim();
            if (newModel.isEmpty()) {
                testStatus.setText("Укажи модель GigaChat, например GigaChat-2.");
                testStatus.setTextColor(Color.rgb(251, 146, 60));
                return;
            }
            try {
                prefs.edit().putString("gigachat_model", newModel).apply();
                String newKey = key.getText().toString().trim();
                if (!newKey.isEmpty()) SecretStore.save(this, newKey);
                dialog.dismiss();
                boolean configured = AiClient.isConfigured(this);
                setStatus(configured ? "GigaChat настроен" : "Модель сохранена · Authorization Key не задан", configured ? GREEN : MUTED);
                refreshDiagnostics();
            } catch (Exception e) {
                testStatus.setText("Не удалось сохранить настройки: " + e.getMessage());
                testStatus.setTextColor(Color.rgb(251, 146, 60));
            }
        }));
        dialog.show();
    }

    private TextView label(String value) {
        TextView t = text(value, 13, MUTED, true);
        t.setPadding(0, dp(10), 0, dp(4));
        return t;
    }

    private EditText field(String hint, String value, int inputType) {
        EditText edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setText(value);
        edit.setHint(hint);
        edit.setTextSize(16);
        edit.setInputType(inputType);
        edit.setTextColor(Color.WHITE);
        edit.setHintTextColor(MUTED);
        edit.setSelectAllOnFocus(false);
        edit.setPadding(dp(12), dp(8), dp(12), dp(8));
        edit.setBackgroundColor(Color.rgb(33, 48, 65));
        return edit;
    }

    private void showRemoteMicTest() {
        stopListening();
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(18), dp(8), dp(18), dp(4));
        TextView intro = text("Проверим, может ли Aerial Voice напрямую получить звук с отдельного входа RC In. Тест не сохраняет аудио — только показывает уровень сигнала.", 15, TEXT, false);
        intro.setPadding(0, 0, 0, dp(12));
        form.addView(intro);
        TextView devices = text("Доступные входы:\n" + RemoteMicTester.listInputDevices(this), 13, MUTED, false);
        devices.setPadding(0, 0, 0, dp(12));
        form.addView(devices);
        TextView languages = text("Распознавание выполняют локальные GigaAM v3 или Vosk. Тест микрофона не отправляет звук в интернет.", 13, MUTED, false);
        languages.setPadding(0, 0, 0, dp(12));
        form.addView(languages);
        TextView testStatus = text("Тест не запущен", 17, GREEN, true);
        testStatus.setPadding(0, 0, 0, dp(8));
        form.addView(testStatus);
        TextView level = text("Уровень сигнала: —", 16, TEXT, false);
        level.setPadding(0, 0, 0, dp(12));
        form.addView(level);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button voiceRecognition = button("Тест VOICE_RECOGNITION", true);
        Button mic = button("Тест MIC", false);
        Button stop = button("Остановить", false);
        addWeighted(buttons, voiceRecognition);
        addWeighted(buttons, mic);
        addWeighted(buttons, stop);
        form.addView(buttons);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Тест микрофона пульта")
                .setView(form)
                .setNegativeButton("Закрыть", null)
                .create();
        remoteMicDialog = dialog;
        dialog.setOnDismissListener(d -> {
            stopRemoteMicTest();
            remoteMicDialog = null;
        });
        voiceRecognition.setOnClickListener(v -> startRemoteMicTest(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, "VOICE_RECOGNITION", testStatus, level));
        mic.setOnClickListener(v -> startRemoteMicTest(
                MediaRecorder.AudioSource.MIC, "MIC", testStatus, level));
        stop.setOnClickListener(v -> {
            if (remoteMicTester != null) remoteMicTester.stop();
            testStatus.setText("Останавливаю тест…");
        });
        dialog.show();
        voiceRecognition.requestFocus();
    }

    private void startRemoteMicTest(int source, String sourceName, TextView status, TextView level) {
        stopRemoteMicTest();
        status.setText("Запускаю RC In через " + sourceName + "…");
        level.setText("Зажми кнопку микрофона на пульте и говори обычным голосом.");
        remoteMicTester = new RemoteMicTester(this, new RemoteMicTester.Listener() {
            @Override public void onStatus(String message) { runOnUiThread(() -> status.setText(message)); }
            @Override public void onLevel(String message) { runOnUiThread(() -> level.setText(message)); }
            @Override public void onFinished(String message) { runOnUiThread(() -> status.setText(message)); }
        });
        remoteMicTester.start(source, sourceName);
    }

    private void stopRemoteMicTest() {
        if (remoteMicTester != null) {
            remoteMicTester.stop();
            remoteMicTester = null;
        }
    }

    private void refreshDiagnostics() {
        if (diagnosticsText == null) return;
        String ttsState = speech == null ? "не запущена" : speech.getTtsShortState();
        String giga = speech != null && speech.isGigaReady() ? "файлы есть" : "нет модели";
        String vosk = speech != null && speech.isVoskReady()
                ? "готов" : speech != null && speech.isVoskLoading() ? "загружается" : "не готов";
        String asr = speech == null ? AsrSelector.AUTO : speech.getAsrEngine();
        if (asrButton != null) asrButton.setText("Распознавание: "
                + (AsrSelector.AUTO.equals(asr) ? "Авто (GigaAM → Vosk)"
                : AsrSelector.GIGAAM.equals(asr) ? "GigaAM v3" : "Vosk"));
        diagnosticsText.setText("GigaAM: " + giga + " · Vosk: " + vosk
                + " · TTS: " + ttsState
                + " · ИИ: " + (AiClient.isConfigured(this) ? "настроен" : "не настроен")
                + " · История: " + history.length() + "/" + MAX_HISTORY_MESSAGES
                + " · Память: " + MemoryStore.count(this));
    }

    private void showDiagnostics() {
        StringBuilder out = new StringBuilder();
        out.append("AERIAL VOICE DIAGNOSTICS\n\n");
        out.append("Android API: ").append(Build.VERSION.SDK_INT).append('\n');
        out.append("Recognition: offline GigaAM v3 E2E CTC INT8 / Vosk, 16 kHz mono PCM\n");
        out.append(speech == null ? "Speech engine not initialized" : speech.getAsrDiagnostics()).append("\n\n");
        out.append("Audio source: VOICE_RECOGNITION -> RC In / MIBT.VoiceControl\n");
        out.append("TTS diagnostics: ").append(speech == null ? "not initialized" : speech.getTtsDiagnostics()).append("\n\n");
        out.append("F5 bridge: requires the small HomeGuard patch in Aerial Korobovo.\n\n");
        out.append("Audio input devices:\n");
        AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audio != null) {
            AudioDeviceInfo[] devices = audio.getDevices(AudioManager.GET_DEVICES_INPUTS);
            if (devices.length == 0) out.append("  (none reported by AudioManager)\n");
            for (AudioDeviceInfo device : devices) {
                out.append("  • ").append(device.getProductName()).append(" | type=").append(device.getType())
                        .append(" | id=").append(device.getId()).append('\n');
            }
        }
        out.append("\nNote: remote microphone audio may be routed by Xiaomi's proprietary service and may not appear as a normal Android input.");
        new AlertDialog.Builder(this).setTitle("Диагностика")
                .setMessage(out.toString())
                .setPositiveButton("Закрыть", null)
                .setNeutralButton("Открыть настройки специальных возможностей", (d, w) -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                .show();
    }

    private void updateFeatureButtons() {
        if (dialogueButton != null) dialogueButton.setText(continuousMode ? "Диалог: ВКЛ" : "Диалог: ВЫКЛ");
        if (autoStartButton != null) {
            boolean enabled = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("auto_start_enabled", false);
            autoStartButton.setText(enabled ? "Автозапуск: ВКЛ" : "Автозапуск: ВЫКЛ");
        }
    }

    private void toggleDialogueMode() {
        continuousMode = !continuousMode;
        updateFeatureButtons();
        if (continuousMode) {
            setStatus("Режим диалога включён. После ответа попробую слушать автоматически.", GREEN);
            if (speech != null && !speech.isListening()) startListening(true);
        } else {
            handler.removeCallbacks(autoListenRunnable);
            if (speech != null && speech.isListening() && !remotePttActive) speech.cancel();
            setStatus("Режим диалога выключен", MUTED);
        }
    }

    private void toggleAutoStart() {
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean enabled = !prefs.getBoolean("auto_start_enabled", false);
        prefs.edit().putBoolean("auto_start_enabled", enabled).apply();
        updateFeatureButtons();
        if (enabled) {
            setStatus("Автозапуск включён", GREEN);
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
                setAnswer("Для запуска приложения после включения телевизора установщик должен выдать Aerial Voice разрешение SYSTEM_ALERT_WINDOW через ADB.");
            } else {
                setAnswer("Aerial Voice будет открываться после полной загрузки телевизора.");
            }
        } else {
            setStatus("Автозапуск выключен", MUTED);
        }
    }

    private void showHistory() {
        StringBuilder content = new StringBuilder();
        try {
            JSONArray archives = new JSONArray(getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getString(ARCHIVE_KEY, "[]"));
            for (int sessionIndex = 0; sessionIndex < archives.length(); sessionIndex++) {
                JSONObject session = archives.optJSONObject(sessionIndex);
                if (session == null) continue;
                long timestamp = session.optLong("timestamp", 0L);
                String date = timestamp > 0
                        ? new java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault())
                                .format(new java.util.Date(timestamp))
                        : "Предыдущий диалог";
                content.append("=== ").append(date).append(" ===\n");
                JSONArray messages = session.optJSONArray("messages");
                if (messages != null) appendHistoryMessages(content, messages);
                content.append('\n');
            }
        } catch (Exception ignored) { }
        content.append("=== ТЕКУЩИЙ ДИАЛОГ ===\n");
        appendHistoryMessages(content, history);
        if (content.toString().trim().isEmpty()) content.append("История пока пустая.");
        TextView view = text(content.toString(), 16, TEXT, false);
        view.setPadding(dp(14), dp(10), dp(14), dp(10));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(view);
        new AlertDialog.Builder(this).setTitle("История разговоров")
                .setView(scroll)
                .setPositiveButton("Закрыть", null)
                .setNeutralButton("Очистить текущий диалог", (d, w) -> confirmClearHistory())
                .show();
    }

    private void appendHistoryMessages(StringBuilder out, JSONArray messages) {
        for (int i = 0; i < messages.length(); i++) {
            JSONObject item = messages.optJSONObject(i);
            if (item == null) continue;
            String role = item.optString("role", "");
            String message = item.optString("content", "");
            out.append("user".equals(role) ? "ТЫ: " : "AERIAL: ").append(message).append('\n');
        }
    }

    private void archiveCurrentHistory() {
        if (history.length() == 0) return;
        try {
            android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            JSONArray archives = new JSONArray(prefs.getString(ARCHIVE_KEY, "[]"));
            JSONObject session = new JSONObject()
                    .put("timestamp", System.currentTimeMillis())
                    .put("messages", new JSONArray(history.toString()));
            archives.put(session);
            while (archives.length() > 10) archives.remove(0);
            prefs.edit().putString(ARCHIVE_KEY, archives.toString()).apply();
        } catch (Exception ignored) { }
    }

    private void showMemorySettings() {
        List<String> facts = MemoryStore.read(this);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(16), dp(8), dp(16), dp(8));
        form.addView(text("Факты хранятся зашифрованными на этом телевизоре. В запрос GigaChat они добавляются только как контекст. Нажми на факт, чтобы удалить его.", 14, MUTED, false));
        ListView list = new ListView(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>(facts));
        list.setAdapter(adapter);
        form.addView(list, new LinearLayout.LayoutParams(-1, dp(190)));
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= facts.size()) return;
            String selected = facts.get(position);
            new AlertDialog.Builder(this).setTitle("Удалить факт?")
                    .setMessage(selected)
                    .setPositiveButton("Удалить", (d, w) -> {
                        MemoryStore.forget(this, selected);
                        showMemorySettings();
                        refreshDiagnostics();
                    }).setNegativeButton("Отмена", null).show();
        });
        EditText newFact = field("Например: предпочитаю короткие ответы", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        form.addView(label("Добавить факт в память"));
        form.addView(newFact);
        Button add = button("Запомнить", true);
        form.addView(add);
        Button clear = button("Очистить всю память", false);
        form.addView(clear);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Долговременная память")
                .setView(form).setNegativeButton("Закрыть", null).create();
        add.setOnClickListener(v -> {
            String fact = newFact.getText().toString().trim();
            if (fact.isEmpty()) return;
            if (MemoryStore.add(this, fact)) {
                dialog.dismiss();
                showMemorySettings();
                refreshDiagnostics();
            } else {
                newFact.setError("Факт пустой, уже сохранён или не удалось записать.");
            }
        });
        clear.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("Очистить всю память?")
                .setMessage("История разговоров и API-ключ останутся. Удалятся только сохранённые факты.")
                .setPositiveButton("Очистить", (d, w) -> {
                    MemoryStore.clear(this);
                    dialog.dismiss();
                    showMemorySettings();
                    refreshDiagnostics();
                }).setNegativeButton("Отмена", null).show());
        dialog.show();
    }

    private void showTvContext() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(18), dp(8), dp(18), dp(4));
        TextView snapshot = text(TvContextProvider.snapshot(this), 15, TEXT, false);
        form.addView(snapshot);
        form.addView(label("Доступ к текущему приложению"));
        Button usage = button("Разрешение статистики приложений", false);
        usage.setOnClickListener(v -> {
            try { startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)); }
            catch (Exception e) { setStatus("Настройки статистики приложений недоступны", MUTED); }
        });
        form.addView(usage);
        form.addView(label("Доступ к названию фильма и медиасессии"));
        Button media = button("Разрешение медиасессий", false);
        media.setOnClickListener(v -> {
            try { startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); }
            catch (Exception e) { setStatus("Настройки доступа к уведомлениям недоступны", MUTED); }
        });
        form.addView(media);
        new AlertDialog.Builder(this).setTitle("Контекст телевизора")
                .setView(form).setPositiveButton("Обновить", (d, w) -> showTvContext())
                .setNegativeButton("Закрыть", null).show();
    }

    private void showTvControls() {
        String[] options = {"Выбрать HDMI / TV-вход", "Открыть системные настройки источников", "Открыть настройки телевизора"};
        new AlertDialog.Builder(this).setTitle("Управление телевизором")
                .setItems(options, (d, which) -> {
                    if (which == 0) TvInputController.showPicker(this);
                    else if (which == 1) TvInputController.openSystemInputs(this);
                    else startActivity(new Intent(Settings.ACTION_SETTINGS));
                }).setNegativeButton("Закрыть", null).show();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_F5 &&
                remoteMicDialog != null && remoteMicDialog.isShowing()) {
            return super.dispatchKeyEvent(event);
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_F5) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                remotePttActive = true;
                handler.removeCallbacks(activityF5Long);
                if (!continuousMode) handler.postDelayed(activityF5Long, 650L);
                startListening();
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_UP) {
                handler.removeCallbacks(activityF5Long);
                if (remotePttActive && speech != null) speech.stopListening();
                remotePttActive = false;
                return true;
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateFeatureButtons();
        refreshDiagnostics();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        pendingRemoteStart = false;
        pendingRemoteStop = false;
        stopRemoteMicTest();
        saveHistory();
        if (speech != null) speech.destroy();
        super.onDestroy();
    }
}
