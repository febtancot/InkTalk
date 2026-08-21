package com.inktalk.ime.asr

import android.content.Context
import com.inktalk.ime.settings.Prefs
import org.json.JSONArray
import org.json.JSONObject

/** 构造运行会话与设置页连接测试共用的 ASR 开始请求。 */
object AsrRequestPayload {
    fun build(
        context: Context,
        inputMode: SpeechInputMode,
        englishRecognitionStrategy: EnglishRecognitionStrategy,
        usesLanguageSpecificEndpoint: Boolean,
    ): String {
        val hotwords = Prefs.requestHotwords(context)
        val appVersion = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }
        val root = JSONObject()
        root.put(
            "user",
                JSONObject()
                    .put("uid", Prefs.asrUid(context))
                    .put("platform", "Android")
                    .put("sdk_version", "inktalk-$appVersion")
                    .put("app_version", appVersion),
        )
        val audio = JSONObject()
            .put("format", "pcm")
            .put("codec", "raw")
            .put("rate", 16000)
            .put("bits", 16)
            .put("channel", 1)
        inputMode.languageCode(englishRecognitionStrategy)?.let { audio.put("language", it) }
        root.put("audio", audio)

        val request = JSONObject()
            .put("enable_punc", Prefs.getBool(context, Prefs.KEY_ENABLE_PUNC, true))
            .put("model_name", "bigmodel")
            .put("enable_itn", Prefs.getBool(context, Prefs.KEY_ENABLE_ITN, true))
            .put("enable_ddc", Prefs.getBool(context, Prefs.KEY_ENABLE_DDC, false))
            .put("show_utterances", true)
            .put("result_type", "single")
            .put("end_window_size", 800)
            .put("force_to_speech_time", 1000)
        if (!usesLanguageSpecificEndpoint) request.put("enable_nonstream", true)
        if (hotwords.isNotEmpty()) {
            val array = JSONArray()
            hotwords.forEach { array.put(JSONObject().put("word", it)) }
            request.put(
                "corpus",
                JSONObject().put("context", JSONObject().put("hotwords", array).toString()),
            )
        }
        root.put("request", request)
        return root.toString()
    }
}
