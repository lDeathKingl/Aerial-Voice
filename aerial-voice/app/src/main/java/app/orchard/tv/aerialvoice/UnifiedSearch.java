package app.orchard.tv.aerialvoice;

import android.app.SearchManager;
import android.app.SearchableInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Query Android search suggestion ContentProviders exposed by installed cinema apps. */
public final class UnifiedSearch {
    private static final String TAG = "AerialUnifiedSearch";
    private static final int PER_PROVIDER = 12;
    private static final int MAX_PROVIDERS = 10;
    private static final String[] ZONA = {"mobi.zona", "free.zona", "ru.zona"};
    private static final String[][] KNOWN = {
            {"mobi.zona.TvSearchProvider", "mobi.zona", "Zona"},
            {"ru.zona.app.android.tv.search", "mobi.zona", "Zona"},
            {"free.zona.TvSearchProvider", "free.zona", "Zona"},
            {"ru.kinopoisk.tv.search.kinopoisk.tv", "ru.kinopoisk.tv", "Кинопоиск"},
            {"com.lazycatsoftware.lmd.tvsearch", "com.lazycatsoftware.lmd", "LazyMedia Deluxe"}
    };
    private static final String[] CINEMA_PACKAGES = {
            "mobi.zona", "free.zona", "ru.zona", "top.rootu.lampa",
            "ru.kinopoisk.tv", "com.lazycatsoftware.lmd", "com.skaz.lampa",
            "top.rootu.lumpa", "top.rootu.prisma"
    };

    public static final class Row {
        public final String title, subtitle, source, packageName, authority, action, data, icon, intentQuery;
        public final String year, rating, genre;
        Row(String title, String subtitle, String source, String packageName, String authority,
            String action, String data, String icon, String intentQuery,
            String year, String rating, String genre) {
            this.title = title; this.subtitle = subtitle; this.source = source;
            this.packageName = packageName; this.authority = authority;
            this.action = action; this.data = data; this.icon = icon; this.intentQuery = intentQuery;
            this.year = year; this.rating = rating; this.genre = genre;
        }
    }

    public static final class Provider {
        public final String authority, pkg, label, path, selection, defaultAction, defaultData;
        Provider(String authority, String pkg, String label, String path, String selection,
                 String defaultAction, String defaultData) {
            this.authority = authority; this.pkg = pkg; this.label = label;
            this.path = path; this.selection = selection;
            this.defaultAction = defaultAction; this.defaultData = defaultData;
        }
    }

    public static final class Results {
        public final List<Row> rows;
        public final List<String> diagnostics;
        public final List<String> installedApps;
        Results(List<Row> rows, List<String> diagnostics, List<String> installedApps) {
            this.rows = rows; this.diagnostics = diagnostics; this.installedApps = installedApps;
        }
    }

    private UnifiedSearch() { }

    public static boolean isZona(String pkg) {
        if (pkg == null) return false;
        for (String zona : ZONA) if (zona.equals(pkg)) return true;
        return false;
    }

    public static List<Provider> discover(Context context, boolean zonaOnly) {
        PackageManager pm = context.getPackageManager();
        Map<String, Provider> out = new LinkedHashMap<>();
        for (String[] known : KNOWN) {
            // Older Zona packages may own the same authority under a different APK ID.
            // Trust the actual exported provider owner, not the former package guess.
            ProviderInfo owner = pm.resolveContentProvider(known[0], 0);
            if (owner == null || !isCinema(pm, owner.packageName)) continue;
            if (zonaOnly && !isZona(owner.packageName)) continue;
            register(pm, out, known[0], owner.packageName, label(owner.packageName),
                    null, null, null, null);
        }
        SearchManager manager = (SearchManager) context.getSystemService(Context.SEARCH_SERVICE);
        if (manager != null) {
            try {
                List<SearchableInfo> global = manager.getSearchablesInGlobalSearch();
                if (global != null) for (SearchableInfo info : global) {
                    registerSearchable(pm, out, info, zonaOnly);
                }
                for (ResolveInfo match : pm.queryIntentActivities(new Intent(Intent.ACTION_SEARCH), 0)) {
                    if (match.activityInfo == null) continue;
                    SearchableInfo info = manager.getSearchableInfo(new ComponentName(
                            match.activityInfo.packageName, match.activityInfo.name));
                    if (info != null) registerSearchable(pm, out, info, zonaOnly);
                }
            } catch (RuntimeException error) {
                Log.w(TAG, "Searchable discovery restricted by this firmware", error);
            }
        }
        return new ArrayList<>(out.values());
    }

    private static void registerSearchable(PackageManager pm, Map<String, Provider> out,
                                           SearchableInfo searchable, boolean zonaOnly) {
        String authority = searchable.getSuggestAuthority();
        String pkg = searchable.getSuggestPackage();
        if (pkg == null && searchable.getSearchActivity() != null)
            pkg = searchable.getSearchActivity().getPackageName();
        if (!isCinema(pm,pkg) || zonaOnly && !isZona(pkg)) return;
        register(pm, out, authority, pkg, label(pkg),
                searchable.getSuggestPath(), searchable.getSuggestSelection(),
                searchable.getSuggestIntentAction(), searchable.getSuggestIntentData());
    }

    private static void register(PackageManager pm, Map<String, Provider> out,
                                 String authority, String pkg, String label, String path,
                                 String selection, String action, String data) {
        if (authority == null || authority.trim().isEmpty() || pkg == null) return;
        try {
            ProviderInfo provider = pm.resolveContentProvider(authority, 0);
            // The provider is untrusted: require the expected installed package and export.
            if (provider == null || !provider.exported || !pkg.equals(provider.packageName)) return;
            if (provider.readPermission != null &&
                    pm.checkPermission(provider.readPermission, pm.getNameForUid(android.os.Process.myUid()))
                            != PackageManager.PERMISSION_GRANTED) return;
            out.put(authority, new Provider(authority, pkg, label, path, selection, action, data));
        } catch (RuntimeException error) {
            Log.w(TAG, "Provider unavailable: " + authority, error);
        }
    }

    private static boolean isCinema(PackageManager pm, String pkg) {
        if (pkg == null) return false;
        for (String p : CINEMA_PACKAGES) if (pkg.equals(p)) return true;
        try {
            android.content.pm.ApplicationInfo app = pm.getApplicationInfo(pkg, 0);
            return app.category == android.content.pm.ApplicationInfo.CATEGORY_VIDEO;
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
            return false;
        }
    }

    private static String label(String pkg) {
        if (isZona(pkg)) return "Zona";
        if ("ru.kinopoisk.tv".equals(pkg)) return "Кинопоиск";
        if (pkg.contains("lampa") || pkg.contains("lumpa") || pkg.contains("prisma")) return "Lampa";
        if (pkg.contains("lazycat")) return "LazyMedia";
        return pkg;
    }

    public static Results search(Context context, String requested, boolean zonaOnly) {
        String query = requested == null ? "" : requested.trim();
        List<Row> found = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        List<String> installed = new ArrayList<>();
        PackageManager pm = context.getPackageManager();
        for (String pkg : CINEMA_PACKAGES) {
            if (zonaOnly && !isZona(pkg)) continue;
            if (pm.getLaunchIntentForPackage(pkg) != null) installed.add(pkg);
        }
        List<Provider> providers = discover(context, zonaOnly);
        if (providers.size() > MAX_PROVIDERS) providers = providers.subList(0, MAX_PROVIDERS);
        if (providers.isEmpty()) notes.add("Не обнаружено доступных ContentProvider поиска.");
        if (query.isEmpty()) return new Results(found, notes, installed);

        ExecutorService pool = Executors.newFixedThreadPool(Math.min(4, Math.max(1, providers.size())));
        try {
            Map<Provider, Future<List<Row>>> futures = new LinkedHashMap<>();
            for (Provider provider : providers)
                futures.put(provider, pool.submit(() -> queryProvider(context, provider, query)));
            long deadline = SystemClock.elapsedRealtime() + 6500L;
            for (Map.Entry<Provider, Future<List<Row>>> task : futures.entrySet()) {
                try {
                    long remaining = Math.max(1, deadline - SystemClock.elapsedRealtime());
                    List<Row> rows = task.getValue().get(remaining, TimeUnit.MILLISECONDS);
                    found.addAll(rows);
                    notes.add(task.getKey().label + " / " + task.getKey().authority + ": " + rows.size() + " результатов");
                } catch (Exception error) {
                    task.getValue().cancel(true);
                    notes.add(task.getKey().label + ": таймаут или поиск недоступен");
                    Log.w(TAG, "Provider query: " + task.getKey().authority, error);
                }
            }
        } finally { pool.shutdownNow(); }
        // Same film may be returned by multiple authorities of one app. Keep source-specific
        // versions for opening, but remove exact duplicated cards.
        Map<String, Row> unique = new LinkedHashMap<>();
        for (Row row : found) {
            String key = row.packageName + "|" + row.title.toLowerCase(Locale.ROOT) +
                    "|" + row.data + "|" + row.subtitle;
            if (!unique.containsKey(key)) unique.put(key, row);
        }
        found = new ArrayList<>(unique.values());
        found.sort((a,b) -> {
            int ca = isZona(a.packageName) ? 0 : 1, cb = isZona(b.packageName) ? 0 : 1;
            return ca != cb ? ca - cb : a.title.compareToIgnoreCase(b.title);
        });
        return new Results(found, notes, installed);
    }

    private static List<Row> queryProvider(Context ctx, Provider provider, String query) {
        List<Row> rows = new ArrayList<>();
        CancellationSignal signal = new CancellationSignal();
        Cursor cursor = null;
        try {
            Uri.Builder builder = new Uri.Builder().scheme("content").authority(provider.authority);
            if (provider.path != null && !provider.path.isEmpty()) builder.appendEncodedPath(provider.path);
            builder.appendPath(SearchManager.SUGGEST_URI_PATH_QUERY);
            String[] args = null;
            if (provider.selection == null) builder.appendPath(query);
            else args = new String[]{query};
            builder.appendQueryParameter("limit", Integer.toString(PER_PROVIDER));
            cursor = ctx.getContentResolver().query(builder.build(), null, provider.selection, args, null, signal);
            if (cursor == null) return rows;
            while (!Thread.currentThread().isInterrupted() && cursor.moveToNext() && rows.size() < PER_PROVIDER) {
                String title = get(cursor, SearchManager.SUGGEST_COLUMN_TEXT_1);
                if (title == null || title.trim().isEmpty()) continue;
                String subtitle = get(cursor, SearchManager.SUGGEST_COLUMN_TEXT_2);
                String action = pick(get(cursor, SearchManager.SUGGEST_COLUMN_INTENT_ACTION), provider.defaultAction);
                String data = pick(get(cursor, SearchManager.SUGGEST_COLUMN_INTENT_DATA), provider.defaultData);
                String id = get(cursor, SearchManager.SUGGEST_COLUMN_INTENT_DATA_ID);
                if (data != null && id != null && !id.isEmpty())
                    data = Uri.parse(data).buildUpon().appendPath(id).build().toString();
                // These optional columns are provided by some cinemas. Never infer a
                // rating or year from the film title or a guessed online database.
                String year = firstNonEmpty(get(cursor,"year"),get(cursor,"release_year"),
                        get(cursor,"releaseYear"),get(cursor,"production_year"));
                String rating = firstNonEmpty(get(cursor,"rating"),get(cursor,"rating_kp"),
                        get(cursor,"kp_rating"),get(cursor,"rating_imdb"),get(cursor,"imdb_rating"));
                String genre = firstNonEmpty(get(cursor,"genre"),get(cursor,"genres"));
                rows.add(new Row(limit(title,140),limit(subtitle,210),provider.label,
                        provider.pkg,provider.authority,action,data,
                        get(cursor,SearchManager.SUGGEST_COLUMN_ICON_1),
                        pick(get(cursor,SearchManager.SUGGEST_COLUMN_QUERY),query),
                        limit(year,24),limit(rating,30),limit(genre,60)));
            }
        } catch (Exception err) {
            Log.w(TAG, "Query failed: " + provider.authority, err);
        } finally {
            if (cursor != null) cursor.close();
            signal.cancel();
        }
        return rows;
    }

    public static boolean open(Context context, Row row) {
        if (row == null) return false;
        if (row.data != null && !row.data.isEmpty()) {
            try {
                Uri uri = Uri.parse(row.data);
                String scheme = uri.getScheme();
                if (scheme != null && !"javascript".equalsIgnoreCase(scheme) && !"file".equalsIgnoreCase(scheme)) {
                    Intent view = new Intent(row.action != null ? row.action : Intent.ACTION_VIEW)
                            .setPackage(row.packageName).setData(uri)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    if (view.resolveActivity(context.getPackageManager()) != null) {
                        context.startActivity(view);
                        return true;
                    }
                }
            } catch (RuntimeException error) { Log.w(TAG, "Unable to open search result", error); }
        }
        try {
            Intent search = new Intent(Intent.ACTION_SEARCH).setPackage(row.packageName)
                    .putExtra(SearchManager.QUERY, row.intentQuery)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (search.resolveActivity(context.getPackageManager()) != null) {
                context.startActivity(search); return true;
            }
        } catch (RuntimeException ignored) { }
        return false;
    }

    public static boolean openApp(Context context, String pkg) {
        try {
            Intent intent = context.getPackageManager().getLaunchIntentForPackage(pkg);
            if (intent == null) return false;
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (RuntimeException error) { return false; }
    }
    private static String get(Cursor c, String name) {
        int i = c.getColumnIndex(name);
        return i >= 0 && !c.isNull(i) ? c.getString(i) : null;
    }
    private static String limit(String s, int max) {
        return s == null ? "" : s.substring(0, Math.min(max, s.length()));
    }
    private static String firstNonEmpty(String... values) {
        for (String value : values) if (value != null && !value.trim().isEmpty()) return value.trim();
        return "";
    }
    private static String pick(String first, String alternative) {
        return first == null || first.isEmpty() ? alternative : first;
    }
}
