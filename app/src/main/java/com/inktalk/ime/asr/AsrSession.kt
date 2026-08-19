package com.inktalk.ime.asr

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.inktalk.ime.settings.Prefs
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 一次语音输入会话：连接 ASR → 采集上送 → 增量/定稿结果回调。
 *
 * 中文和数字使用双向流式优化版（bigmodel_async）+ 二遍识别；英文使用支持固定
 * language=en-US 的 bigmodel_nostream。两条链路共享同一套采集、提交与收尾状态机。
 */
class AsrSession(
    private val context: Context,
    private val listener: Listener,
) {
    enum class State { IDLE, CONNECTING, STARTING, STREAMING, FINISHING }

    interface Listener {
        fun onStateChanged(state: State)
        /** 定稿的一句（应直接 commitText 上屏）。 */
        fun onCommitted(text: String)
        /** 未定的增量文本（应作为 composing 显示）。 */
        fun onInterim(text: String)
        /** 丢弃未定文本，不得把 composing 内容提交到宿主编辑器。 */
        fun onInterimDiscarded()
        fun onError(message: String)
        fun onAmplitude(level: Int)
        /** 服务端返回的最终完整文本（会话结束时）。 */
        fun onSessionEnd()
    }

    /** 可选的本地会话上限；默认不限制，由用户、编辑器生命周期或服务端结束会话。 */
    var maxDurationMs: Long? = null

    private val main = Handler(Looper.getMainLooper())
    private val client = VolcAsrClient(ClientListener())
    private val capturer = AudioCapturer()
    private val terminalEvent = AtomicBoolean(false)

    @Volatile
    var state: State = State.IDLE
        private set

    private val committedKeys = HashSet<String>()
    private var lastInterim = ""
    private var audioStartAt = 0L
    private var inputMode = SpeechInputMode.CHINESE
    private var englishRecognitionStrategy = EnglishRecognitionStrategy.REALTIME_BILINGUAL
    private var usesLanguageSpecificEndpoint = false
    private val startAckTimeout = Runnable {
        if (state == State.STARTING) failSession("识别服务未确认开始请求，请重试")
    }
    private val finalResultTimeout = Runnable {
        if (state == State.FINISHING) failSession("未收到最终识别结果，请重试")
    }

    fun start(mode: SpeechInputMode = SpeechInputMode.CHINESE) {
        if (state != State.IDLE) return
        englishRecognitionStrategy = EnglishRecognitionStrategy.fromPreference(
            Prefs.get(
                context,
                Prefs.KEY_ENGLISH_RECOGNITION_STRATEGY,
                EnglishRecognitionStrategy.REALTIME_BILINGUAL.preferenceValue,
            )
        )
        inputMode = mode.normalizedFor(englishRecognitionStrategy)
        usesLanguageSpecificEndpoint = mode.usesLanguageSpecificEndpoint(englishRecognitionStrategy)
        setState(State.CONNECTING)
        terminalEvent.set(false)
        committedKeys.clear()
        lastInterim = ""
        client.connect(
            VolcAsrClient.Config(
                endpoint = if (usesLanguageSpecificEndpoint) {
                    Prefs.ASR_ENDPOINT_NOSTREAM
                } else {
                    Prefs.ASR_ENDPOINT_ASYNC
                },
                resourceId = Prefs.resourceId(context),
                apiKey = Prefs.get(context, Prefs.KEY_API_KEY),
                appKey = Prefs.get(context, Prefs.KEY_APP_KEY),
                accessKey = Prefs.get(context, Prefs.KEY_ACCESS_KEY),
            )
        )
    }

    /** 用户主动结束：停采集、发尾包、等最终结果。 */
    fun stop() {
        if (state == State.IDLE || state == State.FINISHING) return
        if (state == State.CONNECTING || state == State.STARTING) { teardown(); return }
        setState(State.FINISHING)
        capturer.stop()
        val sent = try { client.sendAudio(ByteArray(0), last = true) } catch (_: Throwable) { false }
        if (!sent) {
            failSession("结束音频发送失败，请检查网络后重试")
            return
        }
        main.postDelayed(finalResultTimeout, FINAL_RESULT_TIMEOUT_MS)
    }

    fun destroy() {
        terminalEvent.set(true)
        main.removeCallbacks(startAckTimeout)
        main.removeCallbacks(finalResultTimeout)
        capturer.stop()
        client.destroy()
        state = State.IDLE
    }

    private fun setState(s: State) {
        state = s
        main.post { listener.onStateChanged(s) }
    }

    private fun buildStartPayload(): String {
        return AsrRequestPayload.build(
            context = context,
            inputMode = inputMode,
            englishRecognitionStrategy = englishRecognitionStrategy,
            usesLanguageSpecificEndpoint = usesLanguageSpecificEndpoint,
        )
    }

    private fun handleResponse(msg: SaucProtocol.ServerMessage.Response) {
        if (state == State.STARTING) {
            if (!AsrStartConfirmation.accepts(msg)) {
                failSession("识别服务在开始确认时提前结束会话")
                return
            }
            main.post { beginStreaming() }
            return
        }
        if (state != State.STREAMING && state != State.FINISHING) return
        val result = msg.json.optJSONObject("result")
        val utterances = result?.optJSONArray("utterances")

        val interimSb = StringBuilder()
        if (utterances != null) {
            for (i in 0 until utterances.length()) {
                val u = utterances.optJSONObject(i) ?: continue
                val text = u.optString("text", "")
                if (text.isEmpty()) continue
                val definite = u.optBoolean("definite", false)
                if (definite) {
                    val key = u.optLong("start_time").toString() + "-" +
                        u.optLong("end_time").toString() + "-" + text
                    if (committedKeys.add(key)) {
                        val transformed = inputMode.transformResult(text)
                        if (transformed.isNotEmpty()) {
                            main.post { listener.onCommitted(transformed) }
                        }
                    }
                } else {
                    interimSb.append(inputMode.transformResult(text))
                }
            }
        } else if (result != null) {
            // 未返回 utterances 时退化为整体文本作为增量
            interimSb.append(inputMode.transformResult(result.optString("text", "")))
        }

        val interim = interimSb.toString()
        if (interim != lastInterim) {
            lastInterim = interim
            val t = interim
            main.post { listener.onInterim(t) }
        }
        if (msg.isLast && state == State.FINISHING) {
            finishSession(commitInterimFallback = usesLanguageSpecificEndpoint)
        }
    }

    private fun beginStreaming() {
        if (state != State.STARTING) return
        main.removeCallbacks(startAckTimeout)
        setState(State.STREAMING)
        audioStartAt = System.currentTimeMillis()
        capturer.start(object : AudioCapturer.Listener {
            override fun onChunk(pcm: ByteArray) {
                if (state != State.STREAMING) return
                val sent = try { client.sendAudio(pcm) } catch (_: Throwable) { false }
                if (!sent) {
                    failSession("音频发送失败，请检查网络后重试")
                    return
                }
                maxDurationMs?.takeIf { it > 0 }?.let { limit ->
                    if (System.currentTimeMillis() - audioStartAt > limit) {
                        main.post { stop() }
                    }
                }
            }

            override fun onLevel(level: Int) {
                main.post { listener.onAmplitude(level) }
            }

            override fun onError(message: String) {
                failSession(message)
            }
        })
    }

    private fun finishSession(commitInterimFallback: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { finishSession(commitInterimFallback) }
            return
        }
        if (!terminalEvent.compareAndSet(false, true)) return
        main.removeCallbacks(finalResultTimeout)
        if (lastInterim.isNotEmpty() && commitInterimFallback) {
            val t = lastInterim
            lastInterim = ""
            main.post { listener.onCommitted(t) }
        } else if (lastInterim.isNotEmpty()) {
            lastInterim = ""
            main.post { listener.onInterimDiscarded() }
        }
        main.post { listener.onSessionEnd() }
        teardown()
    }

    private fun failSession(message: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { failSession(message) }
            return
        }
        if (!terminalEvent.compareAndSet(false, true)) return
        main.removeCallbacks(startAckTimeout)
        main.removeCallbacks(finalResultTimeout)
        if (lastInterim.isNotEmpty()) {
            lastInterim = ""
            main.post { listener.onInterimDiscarded() }
        }
        main.post { listener.onError(message) }
        teardown()
    }

    private fun teardown() {
        capturer.stop()
        client.close()
        main.removeCallbacks(startAckTimeout)
        main.removeCallbacks(finalResultTimeout)
        setState(State.IDLE)
    }

    private inner class ClientListener : VolcAsrClient.Listener {
        override fun onOpen(logId: String?) {
            // 兜底：等待连接期间用户可能已取消（stop/teardown），此时直接丢弃
            if (state != State.CONNECTING) { teardown(); return }
            setState(State.STARTING)
            try {
                if (!client.sendFullRequest(buildStartPayload())) {
                    failSession("开始请求发送失败，请检查网络后重试")
                    return
                }
            } catch (t: Throwable) {
                failSession("构造请求失败：" + t.message)
                return
            }
            main.postDelayed(startAckTimeout, START_ACK_TIMEOUT_MS)
        }

        override fun onMessage(msg: SaucProtocol.ServerMessage) {
            when (msg) {
                is SaucProtocol.ServerMessage.Response -> handleResponse(msg)
                is SaucProtocol.ServerMessage.Error -> {
                    failSession("服务端错误 " + msg.code + "：" + msg.message)
                }
            }
        }

        override fun onFailure(t: Throwable) {
            failSession("连接失败：" + (t.message ?: t.javaClass.simpleName))
        }

        override fun onClosed() {
            if (state != State.IDLE) failSession("识别连接意外关闭，请重试")
        }
    }

    private companion object {
        const val START_ACK_TIMEOUT_MS = 5_000L
        const val FINAL_RESULT_TIMEOUT_MS = 4_000L
    }
}
