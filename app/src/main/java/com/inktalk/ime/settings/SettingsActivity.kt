package com.inktalk.ime.settings

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.inktalk.ime.R
import com.inktalk.ime.ai.AiProcessor
import com.inktalk.ime.asr.EnglishRecognitionStrategy
import com.inktalk.ime.asr.AsrRequestPayload
import com.inktalk.ime.asr.AsrStartConfirmation
import com.inktalk.ime.asr.SaucProtocol
import com.inktalk.ime.asr.SpeechInputMode
import com.inktalk.ime.asr.VolcAsrClient
import com.inktalk.ime.update.UpdateManager
import okhttp3.Call
import java.util.concurrent.atomic.AtomicBoolean

/** 设置页：引导启用输入法、配置火山引擎凭据与 AI 服务。 */
class SettingsActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var textStatus: TextView
    private lateinit var settingsScroll: ScrollView
    private var updateCall: Call? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        textStatus = findViewById(R.id.textStatus)
        settingsScroll = findViewById(R.id.settingsScroll)

        findViewById<View>(R.id.btnSettingsBack).setSystemHapticClick { finish() }
        findViewById<Button>(R.id.btnEnableIme).setSystemHapticClick {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        findViewById<Button>(R.id.btnPickIme).setSystemHapticClick {
            getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
        }
        findViewById<Button>(R.id.btnMicPermission).setSystemHapticClick {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                showStatus("录音权限已授予 ✓")
            } else {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            }
        }
        findViewById<Button>(R.id.btnOpenSpeechConsole).setSystemHapticClick {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SPEECH_CONSOLE_URL)))
            } catch (error: RuntimeException) {
                showStatus(getString(R.string.settings_console_failed, error.safeMessage()))
            }
        }
        findViewById<Button>(R.id.btnEditHotwords).setSystemHapticClick {
            savePrefs()
            startActivityForResult(
                Intent(this, HotwordSettingsActivity::class.java),
                REQ_EDIT_HOTWORDS,
            )
        }
        findViewById<Button>(R.id.btnOpenBackup).setSystemHapticClick {
            savePrefs()
            startActivityForResult(
                Intent(this, BackupSettingsActivity::class.java),
                REQ_BACKUP_SETTINGS,
            )
        }
        findViewById<Button>(R.id.btnCheckUpdate).setSystemHapticClick { checkForUpdate() }
        loadPrefs()
        findViewById<Switch>(R.id.switchExtremeHeight).setOnCheckedChangeListener {
                _, enabled ->
            Prefs.putBool(this, Prefs.KEY_EXTREME_HEIGHT_MODE, enabled)
        }
        findViewById<Switch>(R.id.switchFullKeyboard).setOnCheckedChangeListener { _, enabled ->
            Prefs.putBool(this, Prefs.KEY_ENABLE_FULL_KEYBOARD, enabled)
        }

        findViewById<View>(R.id.btnSave).setSystemHapticClick {
            savePrefs()
            showStatus("已保存 ✓")
        }
        findViewById<Button>(R.id.btnTestAsr).setSystemHapticClick {
            savePrefs()
            testAsr()
        }
        findViewById<Button>(R.id.btnTestAi).setSystemHapticClick {
            savePrefs()
            testAi()
        }
    }

    override fun onDestroy() {
        updateCall?.cancel()
        super.onDestroy()
    }

    private fun checkForUpdate() {
        showStatus(getString(R.string.update_checking))
        updateCall?.cancel()
        updateCall = UpdateManager.check(this) { result ->
            updateCall = null
            if (isFinishing || isDestroyed) return@check
            when (result) {
                UpdateManager.CheckResult.UpToDate -> showStatus(getString(R.string.update_up_to_date))
                is UpdateManager.CheckResult.Failed -> showStatus(getString(R.string.update_failed, result.message))
                is UpdateManager.CheckResult.Available -> showUpdateDialog(result.manifest)
            }
        }
    }

    private fun showUpdateDialog(manifest: com.inktalk.ime.update.UpdateManifest) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_available_title, manifest.versionName))
            .setMessage(manifest.releaseNotes.ifBlank { getString(R.string.settings_update_description) })
            .setNegativeButton(R.string.update_later, null)
            .setPositiveButton(R.string.update_download) { _, _ ->
                if (!UpdateManager.canInstallPackages(this)) {
                    showStatus(getString(R.string.update_install_permission))
                    try { UpdateManager.openInstallPermission(this) }
                    catch (error: RuntimeException) {
                        showStatus(getString(R.string.update_failed, error.safeMessage()))
                    }
                } else {
                    try {
                        UpdateManager.enqueueDownload(this, manifest)
                        showStatus(getString(R.string.update_download_started))
                    } catch (error: RuntimeException) {
                        showStatus(getString(R.string.update_failed, error.safeMessage()))
                    }
                }
            }
            .show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) {
            showStatus(
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
                    "录音权限已授予 ✓" else "录音权限被拒绝，语音输入将无法工作"
            )
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_EDIT_HOTWORDS -> showStatus(getString(R.string.hotwords_saved))
            REQ_BACKUP_SETTINGS -> {
                loadPrefs()
                showStatus(getString(R.string.settings_import_applied))
            }
        }
    }

    private val resourceLabels = listOf(
        "豆包流式识别 2.0 · 小时版",
        "豆包流式识别 2.0 · 并发版",
        "豆包流式识别 1.0 · 小时版",
        "豆包流式识别 1.0 · 并发版",
    )

    private val englishRecognitionLabels = listOf(
        "实时中英混合（推荐）",
        "英文优先定稿",
    )

    private fun loadPrefs() {
        val spinner = findViewById<Spinner>(R.id.spinnerResource)
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            resourceLabels,
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinner.setSelection(Prefs.RESOURCE_IDS.indexOf(Prefs.resourceId(this)).coerceAtLeast(0))
        val englishSpinner = findViewById<Spinner>(R.id.spinnerEnglishRecognition)
        englishSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            englishRecognitionLabels,
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        val englishStrategy = EnglishRecognitionStrategy.fromPreference(
            Prefs.get(this, Prefs.KEY_ENGLISH_RECOGNITION_STRATEGY)
        )
        englishSpinner.setSelection(EnglishRecognitionStrategy.entries.indexOf(englishStrategy))
        findViewById<EditText>(R.id.editApiKey).setText(Prefs.get(this, Prefs.KEY_API_KEY))
        findViewById<EditText>(R.id.editAppKey).setText(Prefs.get(this, Prefs.KEY_APP_KEY))
        findViewById<EditText>(R.id.editAccessKey).setText(Prefs.get(this, Prefs.KEY_ACCESS_KEY))
        findViewById<Switch>(R.id.switchDdc).isChecked =
            Prefs.getBool(this, Prefs.KEY_ENABLE_DDC, false)
        findViewById<Switch>(R.id.switchPunc).isChecked =
            Prefs.getBool(this, Prefs.KEY_ENABLE_PUNC, true)
        findViewById<Switch>(R.id.switchItn).isChecked =
            Prefs.getBool(this, Prefs.KEY_ENABLE_ITN, true)
        findViewById<Switch>(R.id.switchExtremeHeight).isChecked =
            Prefs.getBool(this, Prefs.KEY_EXTREME_HEIGHT_MODE, false)
        findViewById<Switch>(R.id.switchFullKeyboard).isChecked =
            Prefs.getBool(this, Prefs.KEY_ENABLE_FULL_KEYBOARD, false)
        findViewById<EditText>(R.id.editAiBaseUrl).setText(
            Prefs.get(this, Prefs.KEY_AI_BASE_URL, Prefs.DEFAULT_AI_BASE_URL)
        )
        findViewById<EditText>(R.id.editAiApiKey).setText(Prefs.get(this, Prefs.KEY_AI_API_KEY))
        findViewById<EditText>(R.id.editAiModel).setText(
            Prefs.get(this, Prefs.KEY_AI_MODEL, Prefs.DEFAULT_AI_MODEL)
        )
        findViewById<Switch>(R.id.switchNoThinking).isChecked =
            Prefs.getBool(this, Prefs.KEY_AI_NO_THINKING, false)
        findViewById<Switch>(R.id.switchReplaceOriginal).isChecked =
            Prefs.getBool(this, Prefs.KEY_AI_REPLACE_ORIGINAL, false)
    }

    private fun savePrefs() {
        Prefs.put(this, Prefs.KEY_API_KEY, findViewById<EditText>(R.id.editApiKey).text.toString().trim())
        Prefs.put(this, Prefs.KEY_APP_KEY, findViewById<EditText>(R.id.editAppKey).text.toString().trim())
        Prefs.put(this, Prefs.KEY_ACCESS_KEY, findViewById<EditText>(R.id.editAccessKey).text.toString().trim())
        Prefs.putBool(this, Prefs.KEY_ENABLE_DDC, findViewById<Switch>(R.id.switchDdc).isChecked)
        Prefs.putBool(this, Prefs.KEY_ENABLE_PUNC, findViewById<Switch>(R.id.switchPunc).isChecked)
        Prefs.putBool(this, Prefs.KEY_ENABLE_ITN, findViewById<Switch>(R.id.switchItn).isChecked)
        Prefs.putBool(
            this,
            Prefs.KEY_EXTREME_HEIGHT_MODE,
            findViewById<Switch>(R.id.switchExtremeHeight).isChecked,
        )
        Prefs.putBool(
            this,
            Prefs.KEY_ENABLE_FULL_KEYBOARD,
            findViewById<Switch>(R.id.switchFullKeyboard).isChecked,
        )
        val idx = findViewById<Spinner>(R.id.spinnerResource).selectedItemPosition
        Prefs.put(this, Prefs.KEY_RESOURCE_ID, Prefs.RESOURCE_IDS[idx.coerceIn(0, 3)])
        val englishIdx = findViewById<Spinner>(R.id.spinnerEnglishRecognition)
            .selectedItemPosition
            .coerceIn(0, EnglishRecognitionStrategy.entries.lastIndex)
        Prefs.put(
            this,
            Prefs.KEY_ENGLISH_RECOGNITION_STRATEGY,
            EnglishRecognitionStrategy.entries[englishIdx].preferenceValue,
        )
        Prefs.put(this, Prefs.KEY_AI_BASE_URL, findViewById<EditText>(R.id.editAiBaseUrl).text.toString().trim())
        Prefs.put(this, Prefs.KEY_AI_API_KEY, findViewById<EditText>(R.id.editAiApiKey).text.toString().trim())
        Prefs.put(this, Prefs.KEY_AI_MODEL, findViewById<EditText>(R.id.editAiModel).text.toString().trim())
        Prefs.putBool(this, Prefs.KEY_AI_NO_THINKING, findViewById<Switch>(R.id.switchNoThinking).isChecked)
        Prefs.putBool(
            this,
            Prefs.KEY_AI_REPLACE_ORIGINAL,
            findViewById<Switch>(R.id.switchReplaceOriginal).isChecked,
        )
    }

    /** 验证 WebSocket 握手、开始请求编码以及服务端首包确认，不访问麦克风。 */
    private fun testAsr() {
        if (!Prefs.hasAsrCredentials(this)) {
            showStatus("请先填写 API Key，或旧版的 App ID + Access Token")
            return
        }
        showStatus("正在连接并验证 ASR 协议…")
        val completed = AtomicBoolean(false)
        var handshakeLogId: String? = null
        lateinit var client: VolcAsrClient

        fun fail(message: String) {
            if (!completed.compareAndSet(false, true)) return
            val hint = if (message.contains("401")) "\n\n401 排查：① API Key 是否来自「语音技术」控制台（不是方舟/LLM 的 Key）且完整无空格 ② 是否已开通豆包流式语音识别大模型 ③ 上方模型与计费版本是否和开通的一致 ④ 旧版控制台请改填 App ID + Access Token（API Key 留空）" else ""
            main.post {
                showStatus("ASR 连接失败：$message$hint")
                client.destroy()
            }
        }

        client = VolcAsrClient(object : VolcAsrClient.Listener {
            override fun onOpen(logId: String?) {
                handshakeLogId = logId
                val payload = try {
                    AsrRequestPayload.build(
                        context = this@SettingsActivity,
                        inputMode = SpeechInputMode.CHINESE,
                        englishRecognitionStrategy = EnglishRecognitionStrategy.REALTIME_BILINGUAL,
                        usesLanguageSpecificEndpoint = false,
                    )
                } catch (error: RuntimeException) {
                    fail("开始请求构造失败：${error.safeMessage()}")
                    return
                }
                if (!client.sendFullRequest(payload)) fail("开始请求发送失败")
            }

            override fun onMessage(msg: SaucProtocol.ServerMessage) {
                when (msg) {
                    is SaucProtocol.ServerMessage.Response -> {
                        if (!AsrStartConfirmation.accepts(msg)) {
                            fail("服务端在开始确认时提前结束会话")
                        } else if (completed.compareAndSet(false, true)) {
                            main.post {
                                showStatus(
                                    "ASR 协议验证成功 ✓ logid=" + (handshakeLogId ?: "-"),
                                )
                                client.destroy()
                            }
                        }
                    }
                    is SaucProtocol.ServerMessage.Error ->
                        fail("服务端错误 ${msg.code}：${msg.message}")
                }
            }

            override fun onFailure(t: Throwable) {
                fail(t.message ?: t.javaClass.simpleName)
            }

            override fun onClosed() {
                fail("服务端在首包确认前关闭了连接")
            }
        })
        client.connect(
            VolcAsrClient.Config(
                endpoint = Prefs.ASR_ENDPOINT,
                resourceId = Prefs.resourceId(this),
                apiKey = Prefs.get(this, Prefs.KEY_API_KEY),
                appKey = Prefs.get(this, Prefs.KEY_APP_KEY),
                accessKey = Prefs.get(this, Prefs.KEY_ACCESS_KEY),
            )
        )
        main.postDelayed({ fail("首包确认超时") }, 10_000)
    }

    private fun testAi() {
        if (!Prefs.hasAiConfig(this)) {
            showStatus("请先填写 AI 服务的 Base URL / API Key / 模型")
            return
        }
        showStatus("正在调用 AI 服务…")
        AiProcessor.process(this, AiProcessor.Mode.SUMMARIZE, "今天天气不错，我们去公园散步了。",
            object : AiProcessor.Callback {
                override fun onResult(text: String) {
                    showStatus("AI 连接成功 ✓ 返回：" + text.take(60))
                }

                override fun onError(message: String) {
                    showStatus("AI 调用失败：" + message)
                }
            })
    }

    private fun showStatus(msg: String) {
        textStatus.visibility = View.VISIBLE
        textStatus.text = msg
        textStatus.post { settingsScroll.smoothScrollTo(0, textStatus.bottom) }
    }

    private fun View.setSystemHapticClick(action: () -> Unit) {
        setOnClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            action()
        }
    }

    private fun Throwable.safeMessage(): String =
        localizedMessage?.takeIf { it.isNotBlank() } ?: javaClass.simpleName

    companion object {
        private const val REQ_MIC = 42
        private const val REQ_EDIT_HOTWORDS = 43
        private const val REQ_BACKUP_SETTINGS = 44
        private const val SPEECH_CONSOLE_URL =
            "https://console.volcengine.com/speech/new/overview?projectName=default"
    }
}
