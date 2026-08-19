package com.inktalk.ime.ai

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.inktalk.ime.settings.Prefs
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * AI 文本处理：总结 / 翻译 / 整理。
 * 走任意 OpenAI 兼容的 /chat/completions 接口，配置来自设置页。
 */
object AiProcessor {

    enum class Mode(val prompt: String) {
        SUMMARIZE("请用一两句话总结下面这段文字的核心内容，直接输出总结，不要解释：\n\n"),
        TRANSLATE("请翻译下面这段文字：如果是中文就翻译成英文，否则翻译成中文。直接输出译文，不要解释：\n\n"),
        POLISH("请整理下面这段文字：去除口语赘词和重复，修正明显错误，理顺语句并适当分段。保持原意，直接输出整理后的文字，不要解释：\n\n"),
    }

    interface Callback {
        fun onResult(text: String)
        fun onError(message: String)
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val main = Handler(Looper.getMainLooper())

    fun process(context: Context, mode: Mode, text: String, callback: Callback): Call {
        return processMessages(
            context = context,
            messages = JSONArray().put(
                JSONObject().put("role", "user").put("content", mode.prompt + text)
            ),
            callback = callback,
        )
    }

    fun processInstruction(
        context: Context,
        prompt: InstructionPrompt,
        callback: Callback,
    ): Call = processMessages(
        context = context,
        messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", prompt.systemMessage))
            .put(JSONObject().put("role", "user").put("content", prompt.userMessage)),
        callback = callback,
    )

    private fun processMessages(
        context: Context,
        messages: JSONArray,
        callback: Callback,
    ): Call {
        val baseUrl = Prefs.get(context, Prefs.KEY_AI_BASE_URL).trimEnd('/')
        val apiKey = Prefs.get(context, Prefs.KEY_AI_API_KEY)
        val model = Prefs.get(context, Prefs.KEY_AI_MODEL)

        val payload = JSONObject()
            .put("model", model)
            .put("messages", messages)

        // 关闭思考模式：覆盖主流 OpenAI 兼容提供方的三种参数写法。
        // 注意：若提供方对未知参数报 400，请关掉此开关。
        if (Prefs.getBool(context, Prefs.KEY_AI_NO_THINKING, false)) {
            payload.put("enable_thinking", false)                              // 通义 Qwen / DashScope
            payload.put("thinking", JSONObject().put("type", "disabled"))      // 火山方舟豆包
            payload.put("reasoning_effort", "minimal")                         // OpenAI gpt-5 系
        }

        val body = payload.toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url(baseUrl + "/chat/completions")
            .header("Authorization", "Bearer " + apiKey)
            .post(body)
            .build()

        val call = client.newCall(request)
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                if (call.isCanceled()) return
                main.post { callback.onError("网络错误：" + e.message) }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    val raw = it.body?.string().orEmpty()
                    if (!it.isSuccessful) {
                        main.post { callback.onError("HTTP " + it.code + "：" + raw.take(200)) }
                        return
                    }
                    try {
                        val content = JSONObject(raw)
                            .getJSONArray("choices")
                            .getJSONObject(0)
                            .getJSONObject("message")
                            .getString("content")
                            .trim()
                        main.post { callback.onResult(content) }
                    } catch (t: Throwable) {
                        main.post { callback.onError("解析响应失败：" + t.message) }
                    }
                }
            }
        })
        return call
    }
}
