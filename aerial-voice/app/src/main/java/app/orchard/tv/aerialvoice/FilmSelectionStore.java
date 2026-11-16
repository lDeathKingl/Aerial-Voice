package app.orchard.tv.aerialvoice;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Local short-lived result list for voice commands: "открой второй результат".
 * Never assumes that opening a film card starts video playback.
 */
final class FilmSelectionStore {
    private static final String PREFS="aerial_voice_settings";
    private static final String LAST_SEARCH="last_film_search_results";
    private static final String LAST_OPENED="last_selected_film_context";
    private static final long MAX_AGE_MS=30L*60L*1000L;
    private static final int MAX_RESULTS=20;

    private FilmSelectionStore(){}

    static void saveResults(Context ctx,String query,List<UnifiedSearch.Row> rows) {
        try{
            JSONArray list=new JSONArray();
            for(int i=0;i<Math.min(MAX_RESULTS,rows.size());i++){
                UnifiedSearch.Row r=rows.get(i);
                JSONObject o=new JSONObject();
                o.put("title",r.title);o.put("source",r.source);
                o.put("package",r.packageName);o.put("authority",r.authority);
                o.put("action",r.action);o.put("data",r.data);
                o.put("icon",r.icon);o.put("query",r.intentQuery);
                o.put("subtitle",r.subtitle);
                o.put("year",r.year);o.put("rating",r.rating);o.put("genre",r.genre);
                list.put(o);
            }
            JSONObject saved=new JSONObject();
            saved.put("timestamp",System.currentTimeMillis());
            saved.put("query",query);
            saved.put("results",list);
            ctx.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit()
                    .putString(LAST_SEARCH,saved.toString()).apply();
        }catch(Exception ignored){}
    }

    static CommandRouter.Result handleVoice(Context ctx,String spoken) {
        int index=selectedIndex(spoken);
        if(index<0)return null;
        try{
            String raw=ctx.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(LAST_SEARCH,"");
            if(raw.isEmpty())return new CommandRouter.Result(true,
                    "Сначала найди фильм. Список результатов ещё пуст.");
            JSONObject saved=new JSONObject(raw);
            if(System.currentTimeMillis()-saved.optLong("timestamp",0)>MAX_AGE_MS)
                return new CommandRouter.Result(true,"Список поиска устарел. Найди фильм ещё раз.");
            JSONArray rows=saved.optJSONArray("results");
            if(rows==null||index>=rows.length())return new CommandRouter.Result(true,
                    "В списке нет результата номер "+(index+1)+".");
            JSONObject o=rows.getJSONObject(index);
            UnifiedSearch.Row row=new UnifiedSearch.Row(
                    o.optString("title",""),o.optString("subtitle",""),
                    o.optString("source",""),o.optString("package",""),
                    o.optString("authority",""),o.optString("action",""),
                    o.optString("data",""),o.optString("icon",""),
                    o.optString("query",""),o.optString("year",""),
                    o.optString("rating",""),o.optString("genre",""));
            if(!UnifiedSearch.open(ctx,row))return new CommandRouter.Result(true,
                    "Кинотеатр не дал ссылку для открытия этой карточки. Выбери её в приложении.");
            rememberOpened(ctx,row);
            return new CommandRouter.Result(true,"Открываю карточку «"+row.title+
                    "» в "+row.source+". Воспроизведение не подтверждено.",true);
        }catch(Exception error){
            return new CommandRouter.Result(true,"Не могу открыть выбранный результат. Повтори поиск.");
        }
    }

    static void rememberOpened(Context ctx,UnifiedSearch.Row row){
        try{
            JSONObject data=new JSONObject();
            data.put("title",row.title);data.put("source",row.source);
            data.put("package",row.packageName);data.put("timestamp",System.currentTimeMillis());
            ctx.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit()
                    .putString(LAST_OPENED,data.toString()).apply();
        }catch(Exception ignored){}
    }

    static String currentFilmContext(Context ctx){
        try{
            String raw=ctx.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(LAST_OPENED,"");
            if(raw.isEmpty())return "";
            JSONObject data=new JSONObject(raw);
            long age=System.currentTimeMillis()-data.optLong("timestamp",0);
            if(age<0||age>12L*60L*60L*1000L)return "";
            return "Недавно из поиска Aerial Voice открывали карточку «"+
                    data.optString("title")+"» в "+data.optString("source")+
                    ". Это не подтверждает, что фильм воспроизводится.";
        }catch(Exception ignored){return "";}
    }

    static int selectedIndex(String spoken){
        if(spoken==null)return -1;
        String q=spoken.toLowerCase(Locale.ROOT).replace('ё','е').trim();
        if(!q.matches("^(?:открой|покажи|выбери|запусти|включи)\\s+.+"))return -1;
        Matcher numeric=Pattern.compile("^(?:открой|покажи|выбери|запусти|включи)\\s+(?:результат\\s+|вариант\\s+|фильм\\s+)?(?:номер\\s+)?(\\d{1,2})(?:\\b|$)").matcher(q);
        if(numeric.find())try{
            int n=Integer.parseInt(numeric.group(1));return n>=1&&n<=MAX_RESULTS?n-1:-1;
        }catch(NumberFormatException ignored){}
        String tail=q.replaceFirst("^(?:открой|покажи|выбери|запусти|включи)\\s+","")
                .replaceFirst("^(?:результат|вариант|фильм)\\s+","");
        String[] names={"первый","второй","третий","четвертый","пятый",
                "шестой","седьмой","восьмой","девятый","десятый"};
        for(int i=0;i<names.length;i++)if(tail.startsWith(names[i]) &&
                (tail.length()==names[i].length()||Character.isWhitespace(tail.charAt(names[i].length()))))
            return i;
        return -1;
    }
}
