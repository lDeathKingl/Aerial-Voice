package app.orchard.tv.aerialvoice

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import java.io.File

/** Local GigaAM v3 E2E CTC. No network, no model downloads. */
class GigaAmEngine(context: Context) {
    private val dir = File(context.getExternalFilesDir(null), "offline-models/gigaam-v3")
    private var recognizer: OfflineRecognizer? = null

    fun isInstalled(): Boolean =
        File(dir, "model.int8.onnx").let { it.isFile && it.length() == 224893668L } &&
            File(dir, "tokens.txt").let { it.isFile && it.length() == 2007L }

    @Synchronized
    fun transcribe(pcm16: ShortArray, count: Int): String {
        require(count > 0 && count <= pcm16.size) { "Invalid audio samples" }
        check(isInstalled()) { "GigaAM model is missing or has unexpected size" }
        if (recognizer == null) {
            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 64),
                modelConfig = OfflineModelConfig(
                    nemo = OfflineNemoEncDecCtcModelConfig(File(dir, "model.int8.onnx").absolutePath),
                    tokens = File(dir, "tokens.txt").absolutePath,
                    numThreads = 2,
                    provider = "cpu"
                )
            )
            recognizer = OfflineRecognizer(config = config)
        }
        val floats = FloatArray(count) { index -> pcm16[index].toFloat() / 32768.0f }
        val stream = recognizer!!.createStream()
        try {
            stream.acceptWaveform(floats, 16000)
            recognizer!!.decode(stream)
            return recognizer!!.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    @Synchronized
    fun close() {
        try { recognizer?.release() }
        catch (e: Exception) { Log.w("GigaAM", "Could not release GigaAM", e) }
        recognizer = null
    }
}
