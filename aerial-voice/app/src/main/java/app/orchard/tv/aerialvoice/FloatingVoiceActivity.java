package app.orchard.tv.aerialvoice;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;

import java.util.List;
import java.util.Locale;

/**
 * Small translucent, dismissible voice UI over the last TV app.
 * It does NOT require SYSTEM_ALERT_WINDOW and does not replace Aerial Korobovo.
 * The optional system assistant / key service can launch it explicitly.
 */
public final class FloatingVoiceActivity extends Activity implements SpeechEngine.Listener {
    public static final String EXTRA_START = "app.orchard.tv.aerialvoice.START_LISTENING";
    public static final String EXTRA_STOP = "app.orchard.tv.aerialvoice.STOP_LISTENING";
    public static final String EXTRA_DIALOGUE = "app.orchard.tv.aerialvoice.CONTINUOUS_MODE";
    public static final String EXTRA_PTT_BRIDGE = "app.orchard.tv.aerialvoice.PTT_BRIDGE";
    private static final int REQUEST_MIC = 3301;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private SpeechEngine speech;
    private TextView status, transcript, reply;
    private Button listen, dialogue;
    private boolean continuous;
    private boolean remotePressed;
    private boolean processing;
    private long lastPttDownAt;
    private long sessionSerial;
    private boolean disposed;
    private final Runnable resumeAfterSpeech = () -> {
        if (continuous && !remotePressed && !processing && speech != null &&
                !speech.isListening() && !isFinishing()) {
            beginListening();
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        getWindow().setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);

        LinearLayout panel=new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(22),dp(14),dp(22),dp(16));
        GradientDrawable bg=new GradientDrawable();
        bg.setColor(Color.argb(245,12,27,46));
        bg.setCornerRadius(dp(20));
        bg.setStroke(dp(2),Color.rgb(44,115,151));
        panel.setBackground(bg);

        TextView header=label("AERIAL VOICE · 0.9.1",22,Color.rgb(56,189,248),true);
        panel.addView(header);
        status=label("Готов к разговору",15,Color.rgb(175,201,223),false);
        status.setPadding(0,dp(5),0,dp(9));
        panel.addView(status);
        transcript=label("Нажми «Слушать» или кнопку микрофона пульта.",17,Color.WHITE,false);
        transcript.setMaxLines(3);
        panel.addView(transcript);
        reply=label("",16,Color.rgb(143,226,201),false);
        reply.setMaxLines(5);
        reply.setPadding(0,dp(8),0,dp(8));
        panel.addView(reply);
        LinearLayout actions=new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        listen=button("Слушать");
        listen.setOnClickListener(v -> {
            if(speech==null)return;
            if(speech.isListening()) {
                speech.stopListening();status.setText("Отправляю фразу…");
            } else beginListening();
        });
        actions.addView(listen,new LinearLayout.LayoutParams(0,dp(53),1.1f));
        dialogue=button("Диалог: ВЫКЛ");
        dialogue.setOnClickListener(v -> {
            continuous=!continuous;
            updateDialogue();
            if(continuous && speech!=null && !speech.isListening() && !processing)
                beginListening();
        });
        actions.addView(dialogue,new LinearLayout.LayoutParams(0,dp(53),1.2f));
        Button full=button("Настройки");
        full.setOnClickListener(v -> {
            startActivity(new Intent(this,MainActivity.class));
            finish();
        });
        actions.addView(full,new LinearLayout.LayoutParams(0,dp(53),1.05f));
        Button close=button("✕");
        close.setOnClickListener(v -> finish());
        actions.addView(close,new LinearLayout.LayoutParams(0,dp(53),0.45f));
        panel.addView(actions);
        setContentView(panel);

        WindowManager.LayoutParams params=getWindow().getAttributes();
        params.width=Math.min((int)(getResources().getDisplayMetrics().widthPixels*0.90f),dp(860));
        params.height=WindowManager.LayoutParams.WRAP_CONTENT;
        params.y=dp(22);
        params.dimAmount=0f;
        getWindow().setAttributes(params);

        android.content.SharedPreferences prefs=getSharedPreferences(
                SharedConversationHistory.PREFS,MODE_PRIVATE);
        speech=new SpeechEngine(this,this,prefs.getString("tts_engine","auto"));
        speech.setAudioSource(prefs.getInt("asr_audio_source",MediaRecorder.AudioSource.VOICE_RECOGNITION));
        speech.setSilenceTimeoutMs(prefs.getLong("silence_timeout_ms",1350L));
        speech.setTtsVoice(prefs.getString("tts_voice",""));
        speech.setVoiceSettings(prefs.getFloat("tts_rate",1f),prefs.getFloat("tts_pitch",1f));
        dispatchIncoming(getIntent());
    }

    private TextView label(String value,int size,int color,boolean bold) {
        TextView v=new TextView(this);v.setText(value);v.setTextColor(color);v.setTextSize(size);
        if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        return v;
    }
    private Button button(String title){
        Button b=new Button(this);b.setAllCaps(false);b.setText(title);b.setTextColor(Color.WHITE);
        b.setTextSize(13f);b.setFocusable(true);
        return b;
    }
    private int dp(float v){return Math.round(getResources().getDisplayMetrics().density*v);}

    @Override protected void onNewIntent(Intent intent){
        super.onNewIntent(intent);setIntent(intent);dispatchIncoming(intent);
    }
    private void dispatchIncoming(Intent intent) {
        if(intent==null)return;
        boolean start=intent.getBooleanExtra(EXTRA_START,false);
        boolean stop=intent.getBooleanExtra(EXTRA_STOP,false);
        if(intent.hasExtra(EXTRA_DIALOGUE)) {
            continuous=intent.getBooleanExtra(EXTRA_DIALOGUE,false);
            updateDialogue();
        }
        if(Intent.ACTION_ASSIST.equals(intent.getAction()) ||
                Intent.ACTION_VOICE_COMMAND.equals(intent.getAction())) {
            continuous=true;updateDialogue();start=true;
        }
        if(start) {
            remotePressed=intent.getBooleanExtra(EXTRA_PTT_BRIDGE,false);
            lastPttDownAt=android.os.SystemClock.uptimeMillis();
            beginListening();
        }
        if(stop) {
            remotePressed=false;
            // A fast F5 down/up can arrive before an Activity has obtained the audio route.
            // Report the condition instead of claiming a successful recording.
            if(speech!=null && speech.isListening()) speech.stopListening();
            else status.setText("Кнопка отпущена до начала записи — удерживай чуть дольше.");
        }
    }
    private void updateDialogue(){
        if(dialogue!=null)dialogue.setText(continuous?"Диалог: ВКЛ":"Диалог: ВЫКЛ");
    }
    private void beginListening() {
        ui.removeCallbacks(resumeAfterSpeech);
        if(disposed||speech==null||speech.isListening()||processing)return;
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            status.setText("Нужно разрешить микрофон для Aerial Voice.");
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},REQUEST_MIC);
            return;
        }
        speech.stopSpeaking();
        listen.setText("Отправить");
        status.setText("Слушаю микрофон пульта · RC In…");
        speech.startListening(true);
    }
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] grants){
        super.onRequestPermissionsResult(code,permissions,grants);
        if(code==REQUEST_MIC && grants.length>0 && grants[0]==PackageManager.PERMISSION_GRANTED)
            beginListening();
        else if(code==REQUEST_MIC)status.setText("Нет разрешения на микрофон.");
    }
    @Override public void onStatus(String text){
        if(!disposed && status!=null)status.setText(text);
    }
    @Override public void onPartial(String text){
        if(!disposed && transcript!=null)transcript.setText(text);
    }
    @Override public void onError(String message){
        if(disposed)return;
        processing=false;
        listen.setText("Слушать");
        status.setText("Ошибка: "+message);
        // Do not spin endlessly when firmware does not expose RC In.
        ui.removeCallbacks(resumeAfterSpeech);
    }
    @Override public void onNoSpeechTimeout(){
        if(disposed)return;
        processing=false;
        listen.setText("Слушать");
        continuous=false;updateDialogue();
        status.setText("Нет звука RC In без кнопки пульта. Удерживай микрофон.");
    }
    @Override public void onTtsReady(boolean ready,String detail){
        if(!disposed && status!=null)status.setText(ready?"RHVoice/TTS готов":detail);
    }
    @Override public void onSpeechDone(){
        if(disposed)return;
        processing=false;
        listen.setText("Слушать");
        if(continuous){
            status.setText("Ответ озвучен · начинаю слушать снова…");
            ui.postDelayed(resumeAfterSpeech,700L);
        }else status.setText("Ответ озвучен.");
    }
    @Override public void onFinal(String recognized){
        if(disposed)return;
        listen.setText("Слушать");
        remotePressed=false;
        processing=true;
        transcript.setText("Вы: "+recognized);
        SharedConversationHistory.append(this,"user",recognized);
        String normalized=recognized.toLowerCase(Locale.ROOT).replace('ё','е');
        if(normalized.startsWith("запомни ")){
            String value=recognized.replaceFirst("(?i)^\\s*запомни(?:\\s+что)?\\s*","").trim();
            respond(MemoryStore.add(this,value)?"Запомнил.":"Факт уже сохранён или не удалось записать.");
            return;
        }
        if(normalized.contains("что ты помнишь")){
            List<String> facts=MemoryStore.read(this);
            respond(facts.isEmpty()?"Память пока пустая.":"Я помню: "+android.text.TextUtils.join("; ",facts));
            return;
        }
        if(normalized.startsWith("забудь ")){
            String value=recognized.replaceFirst("(?i)^\\s*забудь\\s*","").trim();
            respond("Удалено фактов: "+MemoryStore.forget(this,value));
            return;
        }
        if(normalized.contains("закончить диалог")||normalized.contains("останови диалог")||
                normalized.contains("заверши диалог")){
            continuous=false;updateDialogue();respond("Диалог завершён.");
            return;
        }
        CommandRouter.Result local=CommandRouter.execute(this,recognized);
        if(local.handled){
            SharedConversationHistory.append(this,"assistant",local.message);
            reply.setText(local.message);
            processing=false;
            if(local.leavesAssistant){
                // SearchResultsActivity or another app has already been launched.
                // Do not reopen RC In over playback.
                finish();return;
            }
            say(local.message);return;
        }
        if(!AiClient.isConfigured(this)){
            respond("Ключ GigaChat не настроен. Открой настройки Aerial Voice.");
            return;
        }
        status.setText("Спрашиваю GigaChat…");
        JSONArray current=SharedConversationHistory.load(this);
        final long thisCall=++sessionSerial;
        AiClient.ask(this,current,TvContextProvider.snapshot(this),MemoryStore.read(this),new AiClient.Callback(){
            @Override public void onStatus(String state){
                ui.post(()->{if(!disposed&&thisCall==sessionSerial)status.setText(state);});
            }
            @Override public void onSuccess(String answer){
                ui.post(()->{if(!disposed&&thisCall==sessionSerial)respond(answer);});
            }
            @Override public void onError(String error){
                ui.post(()->{if(!disposed&&thisCall==sessionSerial)respond(error);});
            }
        });
    }
    private void respond(String text){
        if(disposed)return;
        SharedConversationHistory.append(this,"assistant",text);
        reply.setText(text);
        status.setText("Отвечаю…");
        say(text);
    }
    private void say(String text){
        boolean accepted=speech!=null && speech.speak(text);
        if(!accepted){
            processing=false;
            continuous=false;updateDialogue();
            status.setText("Текст ответа готов; озвучка RHVoice недоступна или отклонена Android.");
            // Do not loop automatically when TTS failed.
        }
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event){
        int code=event.getKeyCode();
        if(code==KeyEvent.KEYCODE_F5 || code==KeyEvent.KEYCODE_VOICE_ASSIST) {
            if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0){
                remotePressed=true;beginListening();return true;
            }
            if(event.getAction()==KeyEvent.ACTION_UP){
                remotePressed=false;
                if(speech!=null&&speech.isListening())speech.stopListening();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }
    @Override protected void onStop(){
        ui.removeCallbacks(resumeAfterSpeech);
        if(speech!=null && speech.isListening())speech.cancel();
        super.onStop();
    }
    @Override protected void onDestroy(){
        disposed=true;sessionSerial++;
        ui.removeCallbacksAndMessages(null);
        if(speech!=null)speech.destroy();
        super.onDestroy();
    }
}
