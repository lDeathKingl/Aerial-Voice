package app.orchard.tv.aerialvoice;

import android.Manifest;
import android.app.Activity;
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
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;



public final class MainActivity extends Activity implements SpeechEngine.Listener {
    private static final int REQUEST_AUDIO = 4101;
    private static final String PREFS = "aerial_voice_settings";
    private static final String EXTRA_START_LISTENING = "app.orchard.tv.aerialvoice.START_LISTENING";
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
    private LinearLayout root;
    private TextView statusText;
    private TextView transcriptText;
    private TextView answerText;
    private TextView diagnosticsText;
    private Button listenButton;
    private RemoteMicTester remoteMicTester;
    private AlertDialog remoteMicDialog;
    private boolean continuousMode;
    private boolean remotePttActive;
    private final Runnable activityF5Long = () -> {
        continuousMode = true;
        setStatus("Режим диалога включён. Отпусти кнопку после фразы.", GREEN);
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        speech = new SpeechEngine(this, this);
        buildUi();
        refreshDiagnostics();
        handleLaunchIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleLaunchIntent(intent);
    }

    private void handleLaunchIntent(Intent intent) {
        if (intent == null) return;
        boolean start = intent.getBooleanExtra(EXTRA_START_LISTENING, false);
        continuousMode = intent.getBooleanExtra(EXTRA_CONTINUOUS, false);
        if (start) handler.postDelayed(this::startListening, 450L);
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
        TextView version = text("ДИАГНОСТИКА МИКРОФОНА · 0.2.0", 13, MUTED, true);
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
        TextView heroSub = text("Нажми кнопку ниже, затем удерживай кнопку микрофона пульта и говори.\nОтпусти кнопку — отправить фразу. Долгое удержание включает режим диалога.", 17, MUTED, false);
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

        Button micTest = button("Проверить микрофон пульта · RC In", false);
        micTest.setOnClickListener(v -> showRemoteMicTest());
        LinearLayout.LayoutParams micTestLp = new LinearLayout.LayoutParams(-1, dp(50));
        micTestLp.bottomMargin = dp(12);
        content.addView(micTest, micTestLp);

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

        TextView footer = text("ПУЛЬТ: короткое нажатие — один запрос · удержание — диалог · F5 внутри приложения — слушать", 12, MUTED, false);
        footer.setPadding(0, dp(12), 0, 0);
        root.addView(footer);

        setContentView(root);
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

    private void startListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_AUDIO);
            return;
        }
        if (listenButton != null) listenButton.setText("●  СЛУШАЮ…");
        speech.startListening();
    }

    private void stopListening() {
        continuousMode = false;
        remotePttActive = false;
        handler.removeCallbacks(activityF5Long);
        if (speech != null) speech.cancel();
        if (listenButton != null) listenButton.setText("●  НАЧАТЬ РАЗГОВОР");
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
        if (continuousMode) setStatus("Готов к следующему вопросу. Удерживай кнопку микрофона.", GREEN);
    }

    @Override
    public void onFinal(String recognized) {
        if (listenButton != null) listenButton.setText("●  НАЧАТЬ РАЗГОВОР");
        remotePttActive = false;
        transcriptText.setText(recognized);
        addHistory("user", recognized);

        CommandRouter.Result local = CommandRouter.execute(this, recognized);
        if (local.handled) {
            setAnswer(local.message);
            addHistory("assistant", local.message);
            speech.speak(local.message);
            if (local.message.startsWith("Открываю ")) continuousMode = false;
            else if (!speech.isTtsReady()) scheduleContinuousIfNeeded();
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
        AiClient.ask(this, history, new AiClient.Callback() {
            @Override public void onSuccess(String answer) {
                runOnUiThread(() -> {
                    setAnswer(answer);
                    addHistory("assistant", answer);
                    setStatus("Готов к следующему вопросу", GREEN);
                    speech.speak(answer);
                    if (!speech.isTtsReady()) scheduleContinuousIfNeeded();
                });
            }
            @Override public void onError(String message) {
                runOnUiThread(() -> {
                    setAnswer(message);
                    addHistory("assistant", message);
                    setStatus("Не удалось получить ответ ИИ", Color.rgb(251, 146, 60));
                    speech.speak(message);
                    if (!speech.isTtsReady()) scheduleContinuousIfNeeded();
                });
            }
        });
    }

    @Override
    public void onError(String message) {
        if (listenButton != null) listenButton.setText("●  НАЧАТЬ РАЗГОВОР");
        remotePttActive = false;
        setStatus(message, Color.rgb(251, 146, 60));
        setAnswer("Проверь, что модель Vosk загружена и микрофон пульта подключён. Для отправки фразы отпусти кнопку микрофона.");
        if (continuousMode) scheduleContinuousIfNeeded();
        refreshDiagnostics();
    }

    private void scheduleContinuousIfNeeded() {
        if (continuousMode) setStatus("Готов к следующему вопросу. Удерживай кнопку микрофона.", GREEN);
    }

    private void addHistory(String role, String content) {
        try {
            history.put(new JSONObject().put("role", role).put("content", content));
            while (history.length() > 12) history.remove(0);
        } catch (Exception ignored) { }
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
        EditText endpoint = field("API endpoint", prefs.getString("endpoint", "https://api.openai.com/v1/chat/completions"), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText model = field("Модель", prefs.getString("model", "gpt-4o-mini"), InputType.TYPE_CLASS_TEXT);
        EditText key = field("API-ключ (оставь пустым, чтобы не менять)", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        form.addView(label("Совместимый с OpenAI Chat Completions API сервер"));
        form.addView(endpoint);
        form.addView(label("Название модели"));
        form.addView(model);
        form.addView(label("Секретный ключ"));
        form.addView(key);
        new AlertDialog.Builder(this)
                .setTitle("Настройки Aerial AI")
                .setView(form)
                .setPositiveButton("Сохранить", (dialog, which) -> {
                    prefs.edit().putString("endpoint", endpoint.getText().toString().trim())
                            .putString("model", model.getText().toString().trim()).apply();
                    String newKey = key.getText().toString().trim();
                    if (!newKey.isEmpty()) {
                        try { SecretStore.save(this, newKey); }
                        catch (Exception e) { setAnswer("Не удалось безопасно сохранить API-ключ: " + e.getMessage()); }
                    }
                    setStatus(AiClient.isConfigured(this) ? "ИИ настроен" : "Сохранены настройки · API-ключ не задан", GREEN);
                    refreshDiagnostics();
                })
                .setNeutralButton("Удалить API-ключ", (dialog, which) -> {
                    try { SecretStore.save(this, ""); } catch (Exception ignored) { }
                    setStatus("API-ключ удалён", MUTED);
                    refreshDiagnostics();
                })
                .setNegativeButton("Отмена", null)
                .show();
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
        TextView languages = text("Распознавание выполняет локальная русская модель Vosk. Google RecognitionService не используется.", 13, MUTED, false);
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
        String modelState = speech == null ? "не запущена"
                : speech.isModelReady() ? "готова"
                : speech.isModelLoading() ? "загружается"
                : "ошибка";
        diagnosticsText.setText("Русская модель Vosk: " + modelState +
                "   ·   Кнопка пульта: нужен минимальный bridge-патч Aerial" +
                "   ·   ИИ: " + (AiClient.isConfigured(this) ? "настроен" : "не настроен"));
    }

    private void showDiagnostics() {
        StringBuilder out = new StringBuilder();
        out.append("AERIAL VOICE DIAGNOSTICS\n\n");
        out.append("Android API: ").append(Build.VERSION.SDK_INT).append('\n');
        out.append("Vosk Russian model: ").append(speech != null && speech.isModelReady() ? "ready" : speech != null && speech.isModelLoading() ? "loading" : "not ready").append('\n');
        out.append("Recognition: offline Vosk, 16 kHz mono PCM\n");
        out.append("Audio source: VOICE_RECOGNITION -> RC In / MIBT.VoiceControl\n\n");
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
        refreshDiagnostics();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        stopRemoteMicTest();
        if (speech != null) speech.destroy();
        super.onDestroy();
    }
}
