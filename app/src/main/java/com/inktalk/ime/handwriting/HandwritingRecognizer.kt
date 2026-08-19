package com.inktalk.ime.handwriting

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.Ink
import com.google.mlkit.vision.digitalink.recognition.RecognitionContext
import com.google.mlkit.vision.digitalink.recognition.WritingArea
import com.inktalk.ime.ui.HandwritingPadView

/** 管理按语言下载的数字墨迹模型，并把轨迹识别为候选文本。 */
class HandwritingRecognizer {

    interface Callback {
        fun onModelReady()
        fun onCandidates(candidates: List<String>)
        fun onError(message: String)
    }

    private data class ModelClient(
        val model: DigitalInkRecognitionModel,
        val recognizer: DigitalInkRecognizer,
    )

    private val modelManager = RemoteModelManager.getInstance()
    private val clients = mutableMapOf<HandwritingLanguage, ModelClient>()

    fun prepare(language: HandwritingLanguage, callback: Callback) {
        val client = clientFor(language)
        modelManager.isModelDownloaded(client.model)
            .addOnSuccessListener { downloaded ->
                if (downloaded) {
                    callback.onModelReady()
                } else {
                    modelManager.download(client.model, DownloadConditions.Builder().build())
                        .addOnSuccessListener { callback.onModelReady() }
                        .addOnFailureListener { callback.onError(it.userMessage()) }
                }
            }
            .addOnFailureListener { callback.onError(it.userMessage()) }
    }

    fun recognize(
        strokes: List<List<HandwritingPadView.Point>>,
        width: Float,
        height: Float,
        language: HandwritingLanguage,
        preContext: String,
        callback: Callback,
    ) {
        val client = clientFor(language)
        val inkBuilder = Ink.builder()
        strokes.filter { it.isNotEmpty() }.forEach { points ->
            val strokeBuilder = Ink.Stroke.builder()
            points.forEach { point ->
                strokeBuilder.addPoint(Ink.Point.create(point.x, point.y, point.timestamp))
            }
            inkBuilder.addStroke(strokeBuilder.build())
        }
        val context = RecognitionContext.builder()
            .setPreContext(preContext.takeLast(MAX_PRE_CONTEXT_CHARS))
            .setWritingArea(WritingArea(width.coerceAtLeast(1f), height.coerceAtLeast(1f)))
            .build()
        client.recognizer.recognize(inkBuilder.build(), context)
            .addOnSuccessListener { result ->
                callback.onCandidates(
                    result.candidates.map { it.text }.filter { it.isNotBlank() }.distinct()
                )
            }
            .addOnFailureListener { callback.onError(it.userMessage()) }
    }

    fun close() {
        clients.values.forEach { it.recognizer.close() }
        clients.clear()
    }

    private fun clientFor(language: HandwritingLanguage): ModelClient {
        clients[language]?.let { return it }
        val identifier = when (language) {
            HandwritingLanguage.SIMPLIFIED_CHINESE ->
                DigitalInkRecognitionModelIdentifier.ZH_HANI_CN
            HandwritingLanguage.ENGLISH_US ->
                DigitalInkRecognitionModelIdentifier.EN_US
        }
        val model = DigitalInkRecognitionModel.builder(identifier).build()
        return ModelClient(
            model = model,
            recognizer = DigitalInkRecognition.getClient(
                DigitalInkRecognizerOptions.builder(model).build()
            ),
        ).also { clients[language] = it }
    }

    private fun Exception.userMessage(): String =
        localizedMessage?.takeIf { it.isNotBlank() } ?: "手写识别暂时不可用"

    private companion object {
        const val MAX_PRE_CONTEXT_CHARS = 20
    }
}
