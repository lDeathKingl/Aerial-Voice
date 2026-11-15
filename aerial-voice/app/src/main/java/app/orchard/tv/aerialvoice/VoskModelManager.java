package app.orchard.tv.aerialvoice;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.IOException;

/**
 * Offline-only Vosk model lookup. Never downloads a model from the TV.
 * Installer places a complete model in external app-specific storage.
 * Existing model in internal storage is retained for safe updates.
 */
public final class VoskModelManager {
    public interface Listener {
        void onProgress(String message);
        void onReady(File modelDirectory);
        void onError(String message);
    }

    public static final String MODEL_NAME = "vosk-model-small-ru-0.22";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final String TAG = "AerialVoiceVosk";

    private VoskModelManager() { }

    public static File offlineModelPath(Context context) {
        File external = context.getExternalFilesDir(null);
        return external == null
                ? new File(context.getFilesDir(), MODEL_NAME)
                : new File(new File(external, "models"), MODEL_NAME);
    }

    public static void prepare(Context context, Listener listener) {
        Context app = context.getApplicationContext();
        Thread worker = new Thread(() -> {
            try {
                File external = offlineModelPath(app);
                File internal = new File(app.getFilesDir(), MODEL_NAME);
                File selected = isModelValid(external) ? external
                        : isModelValid(internal) ? internal : null;
                if (selected == null) {
                    throw new IOException("Офлайн-модель Vosk не найдена. "
                            + "Установи полный ZIP с компьютера через install-and-check.bat; "
                            + "телевизору не потребуется интернет.");
                }
                MAIN.post(() -> listener.onProgress("Офлайн-модель Vosk найдена: "
                        + (selected.equals(external) ? "комплект установщика" : "сохранённая модель")));
                MAIN.post(() -> listener.onReady(selected));
            } catch (Exception e) {
                Log.e(TAG, "Offline Vosk model is missing", e);
                String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                MAIN.post(() -> listener.onError(detail));
            }
        }, "AerialVoice-Offline-Vosk");
        worker.start();
    }

    public static boolean isModelValid(File directory) {
        return directory != null && directory.isDirectory()
                && new File(directory, "am/final.mdl").isFile()
                && new File(directory, "conf/mfcc.conf").isFile()
                && new File(directory, "graph/Gr.fst").isFile();
    }
}
