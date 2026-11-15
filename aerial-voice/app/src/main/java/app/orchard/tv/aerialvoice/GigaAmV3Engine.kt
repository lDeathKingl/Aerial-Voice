package app.orchard.tv.aerialvoice

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.io.File

/**
 * GigaAM v3 E2E RNNT, sherpa-onnx 2.0.1 CPU backend.
 * Everything runs locally: no API, download, or network requests.
 * Must not be called on the main/UI thread.
 */
object GigaAmV3Engine {
    private const val TAG = "AerialVoiceGigaAM"
    private const val SAMPLE_RATE = 16000
    const val MODEL_DIR = "gigaam-v3"
    private var recognizer: OfflineRecognizer? = null
    private var loadedPath: String = ""

    @JvmStatic
    fun modelDirectory(context: Context): File {
        val external = context.getExternalFilesDir(null)
        return File(external ?: context.filesDir, "models/$MODEL_DIR")
    }

    @JvmStatic
    fun isInstalled(context: Context): Boolean {
        val root = modelDirectory(context)
        return root.isDirectory &&
            File(root, "encoder.int8.onnx").length() > 150_000_000L &&
            File(root, "decoder.onnx").length() > 100_000L &&
            File(root, "joiner.onnx").length() > 100_000L &&
            File(root, "tokens.txt").length() > 1000L
    }

    @JvmStatic
    @Synchronized
    fun transcribe(context: Context, audio: ShortArray, sampleCount: Int): String {
        check(isInstalled(context)) { "GigaAM v3 не установлена. Установи полный офлайн-комплект с компьютера." }
        require(sampleCount in 1..audio.size) { "Недопустимая длина аудиофрагмента" }
        val root = modelDirectory(context)
        if (recognizer == null || loadedPath != root.absolutePath) {
            recognizer?.release()
            recognizer = null
            loadedPath = ""
            val model = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = File(root, "encoder.int8.onnx").absolutePath,
                    decoder = File(root, "decoder.onnx").absolutePath,
                    joiner = File(root, "joiner.onnx").absolutePath
                ),
                tokens = File(root, "tokens.txt").absolutePath,
                modelType = "nemo_transducer",
                numThreads = 2,
                provider = "cpu",
                debug = false
            )
            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 64, dither = 0f),
                modelConfig = model,
                decodingMethod = "greedy_search"
            )
            Log.i(TAG, "Loading GigaAM v3 from local files, no network")
            recognizer = OfflineRecognizer(config = config)
            loadedPath = root.absolutePath
        }
        val samples = FloatArray(sampleCount) { i -> audio[i] / 32768.0f }
        val stream = recognizer!!.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            recognizer!!.decode(stream)
            return recognizer!!.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    @JvmStatic
    @Synchronized
    fun release() {
        try {
            recognizer?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Could not unload GigaAM v3 engine", e)
        } finally {
            recognizer = null
            loadedPath = ""
        }
    }
}
