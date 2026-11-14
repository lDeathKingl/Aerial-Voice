package app.orchard.tv.aerialvoice;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Downloads and installs the Russian Vosk model into app-private storage. */
public final class VoskModelManager {
    public interface Listener {
        void onProgress(String message);
        void onReady(File modelDirectory);
        void onError(String message);
    }

    public static final String MODEL_NAME = "vosk-model-small-ru-0.22";
    private static final String MODEL_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip";
    private static final String TAG = "AerialVoiceVosk";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private VoskModelManager() { }

    public static void prepare(Context context, Listener listener) {
        Context app = context.getApplicationContext();
        Thread worker = new Thread(() -> {
            try {
                File modelDirectory = ensureModel(app, listener);
                MAIN.post(() -> listener.onReady(modelDirectory));
            } catch (Exception e) {
                String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                Log.e(TAG, "Could not prepare Russian Vosk model", e);
                MAIN.post(() -> listener.onError("Не удалось подготовить русскую модель Vosk: " + message));
            }
        }, "AerialVoice-Vosk-Model");
        worker.start();
    }

    private static File ensureModel(Context context, Listener listener) throws Exception {
        File root = context.getFilesDir();
        File modelDirectory = new File(root, MODEL_NAME);
        if (isModelValid(modelDirectory)) {
            notifyProgress(listener, "Русская модель Vosk уже установлена.");
            return modelDirectory;
        }

        if (root.getUsableSpace() < 140L * 1024L * 1024L) {
            throw new IOException("На внутреннем хранилище телевизора нужно не менее 140 МБ свободного места.");
        }

        File archive = new File(root, MODEL_NAME + ".zip");
        File partial = new File(root, MODEL_NAME + ".zip.part");
        File temporary = new File(root, MODEL_NAME + ".extracting");
        deleteRecursively(partial);
        deleteRecursively(temporary);

        notifyProgress(listener, "Подключаюсь к серверу русской модели Vosk…");
        download(MODEL_URL, partial, listener);
        if (archive.exists() && !archive.delete()) {
            throw new IOException("Не удалось заменить старый архив модели.");
        }
        if (!partial.renameTo(archive)) {
            throw new IOException("Не удалось сохранить загруженный архив модели.");
        }

        notifyProgress(listener, "Распаковываю русскую модель Vosk…");
        if (!temporary.mkdirs() && !temporary.isDirectory()) {
            throw new IOException("Не удалось создать папку для распаковки модели.");
        }
        unzipSafely(archive, temporary);

        File extracted = new File(temporary, MODEL_NAME);
        if (!isModelValid(extracted)) {
            throw new IOException("В архиве отсутствуют обязательные файлы русской модели.");
        }

        deleteRecursively(modelDirectory);
        if (!extracted.renameTo(modelDirectory)) {
            throw new IOException("Не удалось установить распакованную модель.");
        }
        deleteRecursively(temporary);
        deleteRecursively(archive);

        if (!isModelValid(modelDirectory)) {
            throw new IOException("Проверка установленной модели не пройдена.");
        }
        notifyProgress(listener, "Русская модель Vosk установлена.");
        return modelDirectory;
    }

    private static void download(String address, File destination, Listener listener) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "AerialVoice-AndroidTV/0.3.1");
            Log.i(TAG, "Connecting to model server: " + address);
            int response = connection.getResponseCode();
            Log.i(TAG, "Model server HTTP " + response);
            if (response != HttpURLConnection.HTTP_OK) {
                throw new IOException("Сервер модели вернул HTTP " + response + ".");
            }

            long total = connection.getContentLengthLong();
            Log.i(TAG, "Model archive content length: " + total + " bytes");
            notifyProgress(listener, total > 0
                    ? "Соединение установлено. Архив: " + (total / (1024L * 1024L)) + " МБ. Начинаю загрузку…"
                    : "Соединение установлено. Размер архива не указан. Начинаю загрузку…");
            long downloaded = 0L;
            long lastReportedBytes = 0L;
            int lastPercent = -1;
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(destination))) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                    downloaded += count;
                    if (total > 0) {
                        int percent = (int) Math.min(100, downloaded * 100L / total);
                        if (downloaded - lastReportedBytes >= 1024L * 1024L || percent == 100) {
                            lastPercent = percent;
                            lastReportedBytes = downloaded;
                            notifyProgress(listener, "Скачиваю русскую модель Vosk: " + percent + "% ("
                                    + (downloaded / (1024L * 1024L)) + " из "
                                    + Math.max(1L, total / (1024L * 1024L)) + " МБ)");
                        }
                    } else if (downloaded - lastReportedBytes >= 1024L * 1024L) {
                        lastReportedBytes = downloaded;
                        notifyProgress(listener, "Скачано " + (downloaded / (1024L * 1024L)) + " МБ модели Vosk…");
                    }
                }
                output.flush();
            }
            Log.i(TAG, "Model archive downloaded: " + destination.length() + " bytes");
            if (destination.length() < 10L * 1024L * 1024L) {
                throw new IOException("Загруженный архив слишком маленький или повреждён.");
            }
        } catch (java.net.SocketTimeoutException e) {
            throw new IOException("Истекло время ожидания ответа сервера модели. Проверь интернет на телевизоре и доступ к alphacephei.com.", e);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static void unzipSafely(File archive, File destination) throws IOException {
        String root = destination.getCanonicalPath() + File.separator;
        try (ZipInputStream input = new ZipInputStream(new BufferedInputStream(
                new java.io.FileInputStream(archive)))) {
            ZipEntry entry;
            byte[] buffer = new byte[64 * 1024];
            while ((entry = input.getNextEntry()) != null) {
                String entryName = entry.getName().replace('\\', '/');
                File outputFile = new File(destination, entryName);
                String canonical = outputFile.getCanonicalPath();
                if (!canonical.startsWith(root)) {
                    throw new IOException("Архив модели содержит недопустимый путь.");
                }
                if (entry.isDirectory()) {
                    if (!outputFile.mkdirs() && !outputFile.isDirectory()) {
                        throw new IOException("Не удалось создать папку модели.");
                    }
                } else {
                    File parent = outputFile.getParentFile();
                    if (parent == null || (!parent.mkdirs() && !parent.isDirectory())) {
                        throw new IOException("Не удалось создать папку файла модели.");
                    }
                    try (BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(outputFile))) {
                        int count;
                        while ((count = input.read(buffer)) != -1) {
                            output.write(buffer, 0, count);
                        }
                    }
                }
                input.closeEntry();
            }
        }
    }

    private static boolean isModelValid(File directory) {
        return directory.isDirectory()
                && new File(directory, "am/final.mdl").isFile()
                && new File(directory, "conf/mfcc.conf").isFile()
                && new File(directory, "graph/Gr.fst").isFile();
    }

    private static void notifyProgress(Listener listener, String message) {
        MAIN.post(() -> listener.onProgress(message));
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursively(child);
            }
        }
        file.delete();
    }
}
