package app.orchard.tv.aerialvoice;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.provider.Settings;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Lists passthrough HDMI/TV inputs exposed by Android TV and opens the selected source. */
public final class TvInputController {
    private TvInputController() { }

    public static final class InputChoice {
        public final String id;
        public final String label;
        InputChoice(String id, String label) { this.id = id; this.label = label; }
    }

    public static List<InputChoice> inputs(Context context) {
        List<InputChoice> result = new ArrayList<>();
        try {
            TvInputManager manager = (TvInputManager) context.getSystemService(Context.TV_INPUT_SERVICE);
            if (manager == null) return result;
            for (TvInputInfo info : manager.getTvInputList()) {
                if (!info.isPassthroughInput()) continue;
                CharSequence label = info.loadLabel(context.getPackageManager());
                result.add(new InputChoice(info.getId(), label == null ? info.getId() : label.toString()));
            }
        } catch (Exception ignored) { }
        return result;
    }

    public static boolean openByQuery(Activity activity, String query) {
        List<InputChoice> choices = inputs(activity);
        String needle = query == null ? "" : query.toLowerCase(java.util.Locale.ROOT).replace(" ", "");
        for (InputChoice choice : choices) {
            String label = choice.label.toLowerCase(java.util.Locale.ROOT).replace(" ", "");
            String id = choice.id.toLowerCase(java.util.Locale.ROOT).replace(" ", "");
            if (label.contains(needle) || id.contains(needle)) {
                open(activity, choice);
                return true;
            }
        }
        return false;
    }

    public static void showPicker(Activity activity) {
        List<InputChoice> choices = inputs(activity);
        if (choices.isEmpty()) {
            new AlertDialog.Builder(activity)
                    .setTitle("Источники телевизора")
                    .setMessage("Android TV не предоставил приложению список HDMI/TV-входов. Можно открыть системные настройки источников.")
                    .setPositiveButton("Настройки", (d, w) -> openSystemInputs(activity))
                    .setNegativeButton("Закрыть", null)
                    .show();
            return;
        }
        String[] labels = new String[choices.size()];
        for (int i = 0; i < choices.size(); i++) labels[i] = choices.get(i).label;
        new AlertDialog.Builder(activity)
                .setTitle("Выбери источник")
                .setItems(labels, (d, which) -> open(activity, choices.get(which)))
                .setNegativeButton("Закрыть", null)
                .show();
    }

    private static void open(Activity activity, InputChoice choice) {
        try {
            Intent intent = new Intent(activity, TvInputActivity.class)
                    .putExtra(TvInputActivity.EXTRA_INPUT_ID, choice.id)
                    .putExtra(TvInputActivity.EXTRA_LABEL, choice.label);
            activity.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(activity, "Не удалось открыть источник " + choice.label, Toast.LENGTH_LONG).show();
        }
    }

    public static void openSystemInputs(Activity activity) {
        String[] actions = {"android.settings.TV_INPUT", "android.settings.HDMI_CEC_SETTINGS"};
        for (String action : actions) {
            try {
                activity.startActivity(new Intent(action));
                return;
            } catch (Exception ignored) { }
        }
        try { activity.startActivity(new Intent(Settings.ACTION_SETTINGS)); }
        catch (Exception ignored) { }
    }
}
