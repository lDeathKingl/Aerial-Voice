package app.orchard.tv.aerialvoice;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.app.SearchManager;

import java.util.List;

/** Backward-compatible entry into the unified search screen, Zona remains first choice. */
public final class ZonaSearch {
    private ZonaSearch() { }
    public static final class Result {
        public final boolean opened;
        public final boolean searchSent;
        public final String message;
        Result(boolean opened, boolean searchSent, String message) {
            this.opened=opened;this.searchSent=searchSent;this.message=message;
        }
    }
    public static Result search(Context context, String rawTitle, boolean onlyZona) {
        return search(context,rawTitle,onlyZona,false);
    }
    public static Result search(Context context, String rawTitle, boolean onlyZona, boolean autoOpenUnique) {
        String title=rawTitle==null?"":rawTitle.trim();
        if(title.isEmpty()) return new Result(false,false,"Название фильма не распознано.");
        try {
            Intent show=new Intent(context,SearchResultsActivity.class)
                    .putExtra(SearchResultsActivity.EXTRA_QUERY,title)
                    .putExtra(SearchResultsActivity.EXTRA_ZONA_ONLY,onlyZona)
                    .putExtra(SearchResultsActivity.EXTRA_AUTO_OPEN,autoOpenUnique)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(show);
            return new Result(true,false,"Ищу «"+title+"» в "+(onlyZona?"Zona":"установленных кинотеатрах")+".");
        } catch(RuntimeException error){
            return new Result(false,false,"Не удалось открыть экран поиска: "+error.getClass().getSimpleName());
        }
    }
    public static String diagnostics(Context context) {
        PackageManager pm=context.getPackageManager();
        StringBuilder out=new StringBuilder();
        for(String pkg:new String[]{"mobi.zona","free.zona","ru.zona"}){
            try{
                PackageInfo info=pm.getPackageInfo(pkg,0);
                List<UnifiedSearch.Provider> sources=UnifiedSearch.discover(context,true);
                out.append(pkg).append(" / ").append(info.versionName)
                    .append("; providers=").append(sources.size());
                for(UnifiedSearch.Provider provider:sources)
                    out.append("\n  ").append(provider.authority);
                return out.toString();
            }catch(PackageManager.NameNotFoundException ignored){}
        }
        return "Zona не установлена или не видна PackageManager";
    }
}
