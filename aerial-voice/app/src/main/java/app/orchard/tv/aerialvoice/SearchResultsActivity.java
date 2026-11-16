package app.orchard.tv.aerialvoice;

import android.app.Activity;
import android.content.Intent;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Leanback-friendly search cards sourced from real installed Android search providers. */
public final class SearchResultsActivity extends Activity {
    public static final String EXTRA_QUERY = "search_query";
    public static final String EXTRA_ZONA_ONLY = "zona_only";
    public static final String EXTRA_AUTO_OPEN = "auto_open_unique";
    private static final int BG = Color.rgb(7,17,31);
    private static final int PANEL = Color.rgb(21,41,62);
    private static final int TEXT = Color.rgb(238,245,252);
    private static final int MUTED = Color.rgb(151,171,192);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private ExecutorService tasks;
    private TextView status;
    private LinearLayout cards;
    private EditText queryInput;
    private boolean zonaOnly;
    private long serial;
    private volatile boolean destroyed;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        tasks = Executors.newFixedThreadPool(3);
        zonaOnly = getIntent().getBooleanExtra(EXTRA_ZONA_ONLY, false);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        LinearLayout frame = new LinearLayout(this);
        frame.setOrientation(LinearLayout.VERTICAL);
        frame.setBackgroundColor(BG);
        frame.setPadding(dp(24),dp(15),dp(24),dp(15));
        TextView header = text("AERIAL VOICE  ·  ПОИСК ФИЛЬМОВ", 23, Color.rgb(56,189,248),true);
        frame.addView(header);
        TextView subtitle = text(zonaOnly ? "Поиск только в Zona" :
                "Единый поиск в кинотеатрах, установленных на телевизоре",15,MUTED,false);
        subtitle.setPadding(0,dp(5),0,dp(12));
        frame.addView(subtitle);
        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        queryInput = new EditText(this);
        queryInput.setSingleLine(true);
        queryInput.setTextColor(TEXT);
        queryInput.setHintTextColor(MUTED);
        queryInput.setTextSize(18f);
        queryInput.setHint("Название фильма или сериала");
        queryInput.setBackgroundColor(PANEL);
        queryInput.setPadding(dp(14),dp(8),dp(14),dp(8));
        String initial = getIntent().getStringExtra(EXTRA_QUERY);
        if (initial != null) queryInput.setText(initial);
        searchRow.addView(queryInput,new LinearLayout.LayoutParams(0,dp(54),1f));
        Button go = new Button(this);
        go.setAllCaps(false);
        go.setText("Искать");
        go.setTextColor(TEXT);
        go.setBackgroundColor(Color.rgb(15,110,163));
        go.setOnClickListener(v -> runSearch(queryInput.getText().toString()));
        LinearLayout.LayoutParams goLp=new LinearLayout.LayoutParams(dp(120),dp(54));
        goLp.leftMargin=dp(10);
        searchRow.addView(go,goLp);
        frame.addView(searchRow);
        status = text("Подготавливаю поиск…",16,MUTED,false);
        status.setPadding(0,dp(12),0,dp(8));
        frame.addView(status);
        ScrollView scroller=new ScrollView(this);
        cards = new LinearLayout(this);
        cards.setOrientation(LinearLayout.VERTICAL);
        scroller.addView(cards);
        frame.addView(scroller,new LinearLayout.LayoutParams(-1,0,1f));
        Button back=new Button(this);
        back.setText("← Вернуться к предыдущему экрану");
        back.setAllCaps(false);
        back.setOnClickListener(v->finish());
        frame.addView(back);
        setContentView(frame);
        if (initial != null && !initial.trim().isEmpty()) {
            go.requestFocus();
            runSearch(initial);
        } else {queryInput.requestFocus();}
    }

    private void runSearch(String raw) {
        String query=raw==null?"":raw.trim();
        if(query.isEmpty()){status.setText("Введи название фильма.");return;}
        ((InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(queryInput.getWindowToken(),0);
        long request=++serial;
        status.setText("Ищу «"+query+"» через провайдеры установленных кинотеатров…");
        cards.removeAllViews();
        tasks.execute(()->{
            UnifiedSearch.Results results=UnifiedSearch.search(getApplicationContext(),query,zonaOnly);
            ui.post(()->{
                if(destroyed||request!=serial)return;
                render(query,results);
            });
        });
    }

    private void render(String query, UnifiedSearch.Results result){
        int total=result.rows.size();
        FilmSelectionStore.saveResults(this,query,result.rows);
        if(getIntent().getBooleanExtra(EXTRA_AUTO_OPEN,false) && total==1){
            UnifiedSearch.Row only=result.rows.get(0);
            if(UnifiedSearch.open(this,only)){
                FilmSelectionStore.rememberOpened(this,only);
                finish();
                return;
            }
        }
        status.setText(total>0?"Найдено "+total+" результатов. Выбери карточку кнопкой OK или скажи: открой первый результат."
                :"Карточки не получены. Попробуй открыть поиск в установленном приложении.");
        int shown=Math.min(48,total);
        for(int i=0;i<shown;i+=2){
            LinearLayout line=new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setPadding(0,dp(4),0,dp(4));
            for(int j=i;j<Math.min(shown,i+2);j++){
                UnifiedSearch.Row row=result.rows.get(j);
                View tile=card(row);
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(155),1f);
                lp.rightMargin=dp(8);
                line.addView(tile,lp);
            }
            if(i+1>=shown)line.addView(new View(this),new LinearLayout.LayoutParams(0,1,1f));
            cards.addView(line);
        }
        if(total>shown)cards.addView(text("Показаны первые "+shown+" результатов.",14,MUTED,false));
        StringBuilder debug=new StringBuilder("Диагностика источников:\n");
        for(String line:result.diagnostics)debug.append("• ").append(line).append("\n");
        if(result.installedApps.isEmpty())debug.append("Кинотеатры не обнаружены.\n");
        TextView notes=text(debug.toString(),13,MUTED,false);
        notes.setPadding(0,dp(12),0,dp(5));
        cards.addView(notes);
        if(!result.installedApps.isEmpty()){
            cards.addView(text("Открыть приложение вручную:",16,TEXT,true));
            LinearLayout appBar=new LinearLayout(this);
            appBar.setOrientation(LinearLayout.HORIZONTAL);
            for(String pkg:result.installedApps){
                Button launch=new Button(this);
                launch.setAllCaps(false);
                launch.setText(pkg.contains("zona")?"Zona":pkg.contains("kinopoisk")?"Кинопоиск":"Кинотеатр");
                launch.setOnClickListener(v->{
                    if(!UnifiedSearch.openApp(this,pkg))
                        Toast.makeText(this,"Приложение недоступно",Toast.LENGTH_LONG).show();
                });
                appBar.addView(launch);
                if(appBar.getChildCount()>=4)break;
            }
            cards.addView(appBar);
        }
    }

    private View card(UnifiedSearch.Row result){
        LinearLayout shell=new LinearLayout(this);
        shell.setOrientation(LinearLayout.HORIZONTAL);
        shell.setGravity(Gravity.CENTER_VERTICAL);
        shell.setPadding(dp(12),dp(10),dp(12),dp(10));
        shell.setFocusable(true);
        shell.setClickable(true);
        shell.setBackground(background(false));
        ImageView poster=new ImageView(this);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setBackgroundColor(Color.rgb(30,48,69));
        shell.addView(poster,new LinearLayout.LayoutParams(dp(85),dp(128)));
        loadPoster(poster,result.icon);
        LinearLayout details=new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setPadding(dp(12),0,0,0);
        TextView source=text(result.source,13,Color.rgb(52,211,153),true);
        details.addView(source);
        TextView name=text(result.title,19,TEXT,true);
        name.setMaxLines(2);
        details.addView(name);
        StringBuilder facts=new StringBuilder();
        if(result.year!=null&&!result.year.isEmpty())facts.append(result.year);
        if(result.rating!=null&&!result.rating.isEmpty()){
            if(facts.length()>0)facts.append(" · ");
            facts.append("Рейтинг: ").append(result.rating);
        }
        if(result.genre!=null&&!result.genre.isEmpty()){
            if(facts.length()>0)facts.append(" · ");
            facts.append(result.genre);
        }
        if(facts.length()>0){
            TextView attrs=text(facts.toString(),13,Color.rgb(237,204,124),false);
            attrs.setMaxLines(2);
            details.addView(attrs);
        }
        if(result.subtitle!=null&&!result.subtitle.isEmpty()){
            TextView secondary=text(result.subtitle,14,MUTED,false);
            secondary.setMaxLines(3);
            details.addView(secondary);
        }
        details.addView(text("OK — открыть карточку",12,MUTED,false));
        shell.addView(details,new LinearLayout.LayoutParams(0,-2,1f));
        shell.setOnFocusChangeListener((v,focus)->shell.setBackground(background(focus)));
        shell.setOnClickListener(v->{
            if(UnifiedSearch.open(this,result)){
                FilmSelectionStore.rememberOpened(this,result);
            } else {
                new android.app.AlertDialog.Builder(this)
                    .setTitle("Переход к фильму не поддерживается")
                    .setMessage("Источник «"+result.source+"» вернул карточку, но не предоставил доступный Android Intent для её открытия.")
                    .setPositiveButton("Открыть приложение",(d,w)->UnifiedSearch.openApp(this,result.packageName))
                    .setNegativeButton("Назад",null).show();
            }
        });
        return shell;
    }

    private void loadPoster(ImageView view,String icon){
        if(icon==null||icon.trim().isEmpty())return;
        final Uri uri;
        try{uri=Uri.parse(icon);}catch(Exception ignored){return;}
        String scheme=uri.getScheme();
        if(!"content".equals(scheme)&&!"android.resource".equals(scheme)
                &&!"https".equals(scheme))return;
        tasks.execute(()->{
            try(InputStream input="https".equals(scheme)
                    ? secureImage(uri.toString())
                    : getContentResolver().openInputStream(uri)){
                if(input==null)return;
                BitmapFactory.Options opts=new BitmapFactory.Options();
                opts.inSampleSize=2;
                Bitmap bmp=BitmapFactory.decodeStream(input,null,opts);
                if(bmp!=null)ui.post(()->{
                    if(!destroyed) view.setImageBitmap(bmp);
                    else bmp.recycle();
                });
            }catch(Exception ignored){}
        });
    }
    private InputStream secureImage(String url)throws java.io.IOException{
        java.net.HttpURLConnection conn=(java.net.HttpURLConnection)new URL(url).openConnection();
        conn.setConnectTimeout(2500);conn.setReadTimeout(3000);
        conn.setInstanceFollowRedirects(false);
        conn.setRequestProperty("Accept","image/*");
        if(conn.getContentLengthLong()>2000000){conn.disconnect();throw new java.io.IOException("Image too large");}
        return conn.getInputStream();
    }
    private GradientDrawable background(boolean focus){
        GradientDrawable shape=new GradientDrawable();
        shape.setColor(focus?Color.rgb(36,82,115):PANEL);
        shape.setCornerRadius(dp(12));
        shape.setStroke(dp(focus?2:1),focus?Color.WHITE:Color.rgb(43,65,89));
        return shape;
    }
    private TextView text(String value,int size,int color,boolean bold){
        TextView view=new TextView(this);
        view.setText(value);view.setTextSize(size);view.setTextColor(color);
        if(bold)view.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        return view;
    }
    private int dp(float v){return Math.round(v*getResources().getDisplayMetrics().density);}
    @Override protected void onDestroy(){
        destroyed=true;serial++;tasks.shutdownNow();ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
