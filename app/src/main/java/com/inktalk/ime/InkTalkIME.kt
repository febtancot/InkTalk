package com.inktalk.ime

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ImageButton
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.inktalk.ime.ai.AiProcessor
import com.inktalk.ime.ai.InstructionApplyPolicy
import com.inktalk.ime.ai.InstructionContextResolver
import com.inktalk.ime.ai.InstructionDocumentSnapshot
import com.inktalk.ime.ai.InstructionPromptBuilder
import com.inktalk.ime.ai.InstructionTargetScope
import com.inktalk.ime.ai.TextReplacementPolicy
import com.inktalk.ime.asr.AsrSession
import com.inktalk.ime.asr.EnglishRecognitionStrategy
import com.inktalk.ime.asr.SpeechInputMode
import com.inktalk.ime.handwriting.HandwritingLanguage
import com.inktalk.ime.handwriting.HandwritingRecognizer
import com.inktalk.ime.history.InputHistoryStore
import com.inktalk.ime.history.InputSource
import com.inktalk.ime.settings.Prefs
import com.inktalk.ime.settings.SettingsActivity
import com.inktalk.ime.ui.HandwritingPadView
import com.inktalk.ime.ui.InputPanelSizing
import com.inktalk.ime.ui.WaveformView
import okhttp3.Call

/**
 * InkTalk 语音输入法主服务。
 * 点击麦克风开始说话，识别结果实时上屏：定稿直接提交，增量作为 composing 显示。
 */
class InkTalkIME : InputMethodService(), AsrSession.Listener {

    private lateinit var btnMic: ImageButton
    private lateinit var btnCmd: ImageButton
    private lateinit var inputRoot: View
    private lateinit var btnInstruction: ImageButton
    private lateinit var btnHandwritingMode: ImageButton
    private lateinit var textStatus: TextView
    private lateinit var textPreview: TextView
    private lateinit var textInstructionScope: TextView
    private lateinit var waveform: WaveformView
    private lateinit var pageVoice: View
    private lateinit var pageKeys: View
    private lateinit var inputModeGroup: View
    private lateinit var previewContainer: View
    private lateinit var handwritingPanel: View
    private lateinit var handwritingPad: HandwritingPadView
    private lateinit var handwritingCandidateScroll: HorizontalScrollView
    private lateinit var handwritingCandidates: LinearLayout
    private lateinit var btnHandwritingChinese: TextView
    private lateinit var btnHandwritingEnglish: TextView
    private lateinit var voicePurposeControls: View
    private lateinit var voicePurposeThumb: View
    private lateinit var instructionReviewActions: View
    private lateinit var btnInstructionCancel: TextView
    private lateinit var btnInstructionRetry: TextView
    private lateinit var btnInstructionApply: TextView
    private lateinit var aiButtons: List<TextView>
    private lateinit var modeButtons: Map<SpeechInputMode, TextView>

    private var session: AsrSession? = null
    private var sessionText = StringBuilder()
    private var voiceHistoryBuffer = StringBuilder()
    private var interim = ""
    private var aiBusy = false
    private var shortcutPageVisible = false
    private var handwritingEnabled = false
    private var selectedHandwritingLanguage = HandwritingLanguage.SIMPLIFIED_CHINESE
    private var handwritingReadyLanguage: HandwritingLanguage? = null
    private var handwritingComposingText = ""
    private var handwritingOperationId = 0L
    private var inputMode = SpeechInputMode.CHINESE
    private var englishRecognitionStrategy = EnglishRecognitionStrategy.REALTIME_BILINGUAL
    private var pendingInputMode: SpeechInputMode? = null
    private var pendingInstructionActivation = false
    private var editorSessionId = 0L
    private var instructionState: InstructionState = InstructionState.Off
    private var instructionText = StringBuilder()
    private var instructionInterim = ""
    private var instructionCall: Call? = null
    private var instructionOperationId = 0L
    private var discardAsrUntilIdle = false
    private var preserveAsrErrorOnIdle = false
    private var asrKeepScreenOn = false
    private val deleteRepeatHandler = Handler(Looper.getMainLooper())
    private val handwritingHandler = Handler(Looper.getMainLooper())
    private val handwritingRecognizer = HandwritingRecognizer()
    private val inputHistoryStore by lazy { InputHistoryStore.get(applicationContext) }
    private var pendingHandwritingRecognition: Runnable? = null
    private var deleteRepeatActive = false
    private var suppressDeleteClick = false
    private var deleteButton: View? = null
    private val deleteRepeatAction = object : Runnable {
        override fun run() {
            if (!deleteRepeatActive) return
            deleteButton?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            deleteOnce()
            deleteRepeatHandler.postDelayed(this, DELETE_REPEAT_INTERVAL_MS)
        }
    }

    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.ime_voice_panel, null)
        inputRoot = view
        inputRoot.keepScreenOn = asrKeepScreenOn
        applyMaximumPanelHeight(view)
        btnMic = view.findViewById(R.id.btnMic)
        btnCmd = view.findViewById(R.id.btnCmd)
        btnInstruction = view.findViewById(R.id.btnInstruction)
        btnHandwritingMode = view.findViewById(R.id.btnHandwritingMode)
        textStatus = view.findViewById(R.id.textStatus)
        textPreview = view.findViewById(R.id.textPreview)
        textInstructionScope = view.findViewById(R.id.textInstructionScope)
        waveform = view.findViewById(R.id.waveform)
        pageVoice = view.findViewById(R.id.pageVoice)
        pageKeys = view.findViewById(R.id.pageKeys)
        inputModeGroup = view.findViewById(R.id.inputModeGroup)
        previewContainer = view.findViewById(R.id.previewContainer)
        handwritingPanel = view.findViewById(R.id.handwritingPanel)
        handwritingPad = view.findViewById(R.id.handwritingPad)
        handwritingCandidateScroll = view.findViewById(R.id.handwritingCandidateScroll)
        handwritingCandidates = view.findViewById(R.id.handwritingCandidates)
        btnHandwritingChinese = view.findViewById(R.id.btnHandwritingChinese)
        btnHandwritingEnglish = view.findViewById(R.id.btnHandwritingEnglish)
        voicePurposeControls = view.findViewById(R.id.voicePurposeControls)
        voicePurposeThumb = view.findViewById(R.id.voicePurposeThumb)
        instructionReviewActions = view.findViewById(R.id.instructionReviewActions)
        btnInstructionCancel = view.findViewById(R.id.btnInstructionCancel)
        btnInstructionRetry = view.findViewById(R.id.btnInstructionRetry)
        btnInstructionApply = view.findViewById(R.id.btnInstructionApply)
        aiButtons = listOf(
            view.findViewById(R.id.btnSummarize),
            view.findViewById(R.id.btnTranslate),
            view.findViewById(R.id.btnPolish),
        )
        modeButtons = mapOf(
            SpeechInputMode.CHINESE to view.findViewById(R.id.btnModeChinese),
            SpeechInputMode.NUMBER to view.findViewById(R.id.btnModeNumber),
            SpeechInputMode.ENGLISH to view.findViewById(R.id.btnModeEnglish),
        )
        reloadSpeechModePreferences()

        btnMic.setSystemHapticClick { toggleSession() }
        textStatus.setSystemHapticClick { maybeOpenSettings() }

        bindRepeatingDelete(view.findViewById(R.id.btnDelete))
        bindKeyboardSwitcher(view.findViewById(R.id.btnSwitchKeyboard))
        view.findViewById<View>(R.id.btnEnter).setSystemHapticClick {
            if (handwritingEnabled) finishHandwritingComposition()
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
        view.findViewById<View>(R.id.btnSettings).setSystemHapticClick { openSettings() }
        btnCmd.setSystemHapticClick { toggleShortcutPage() }
        view.findViewById<View>(R.id.btnClearHandwriting).setSystemHapticClick {
            clearHandwriting(cancelComposition = true)
        }
        handwritingPad.onStrokeStarted = { onHandwritingStrokeStarted() }
        handwritingPad.onStrokeFinished = { scheduleHandwritingRecognition() }
        btnHandwritingChinese.setSystemHapticClick(HapticFeedbackConstants.CONTEXT_CLICK) {
            selectHandwritingLanguage(HandwritingLanguage.SIMPLIFIED_CHINESE)
        }
        btnHandwritingEnglish.setSystemHapticClick(HapticFeedbackConstants.CONTEXT_CLICK) {
            selectHandwritingLanguage(HandwritingLanguage.ENGLISH_US)
        }
        btnInstruction.setSystemHapticClick(HapticFeedbackConstants.CONTEXT_CLICK) {
            toggleInstructionMode()
        }
        btnHandwritingMode.setSystemHapticClick(HapticFeedbackConstants.CONTEXT_CLICK) {
            toggleHandwritingMode()
        }
        btnInstructionCancel.setSystemHapticClick { cancelInstructionMode() }
        btnInstructionRetry.setSystemHapticClick { retryInstruction() }
        btnInstructionApply.setSystemHapticClick { applyInstructionResult() }
        modeButtons.forEach { (mode, button) ->
            button.setSystemHapticClick(HapticFeedbackConstants.CONTEXT_CLICK) {
                selectInputMode(mode)
            }
        }

        bindShortcutKeys(view)

        view.findViewById<TextView>(R.id.btnSummarize).setSystemHapticClick {
            runAi(AiProcessor.Mode.SUMMARIZE)
        }
        view.findViewById<TextView>(R.id.btnTranslate).setSystemHapticClick {
            runAi(AiProcessor.Mode.TRANSLATE)
        }
        view.findViewById<TextView>(R.id.btnPolish).setSystemHapticClick {
            runAi(AiProcessor.Mode.POLISH)
        }
        updateInputModeVisual()
        updateHandwritingLanguageVisual()
        return view
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::inputRoot.isInitialized) {
            inputRoot.post { applyMaximumPanelHeight(inputRoot) }
        }
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        applyMaximumPanelHeight(inputRoot)
        // 每次弹出面板时重置会话状态
        editorSessionId += 1
        cancelInstructionMode(refresh = false)
        session?.takeIf { it.state != AsrSession.State.IDLE }?.let {
            discardAsrUntilIdle = true
            it.stop()
        }
        sessionText = StringBuilder()
        interim = ""
        textPreview.text = ""
        waveform.clear()
        pendingInputMode = null
        pendingInstructionActivation = false
        resetHandwritingMode(commitComposition = false)
        shortcutPageVisible = false
        pageVoice.animate().cancel()
        pageKeys.animate().cancel()
        pageVoice.visibility = View.VISIBLE
        pageVoice.alpha = 1f
        pageVoice.translationX = 0f
        pageKeys.visibility = View.GONE
        pageKeys.alpha = 1f
        pageKeys.translationX = 0f
        btnCmd.background = getDrawable(R.drawable.bg_icon_button)
        reloadSpeechModePreferences()
        updateInputModeVisual()
        renderInstructionState()
        refreshIdleHint()
        updateMicVisual(false)
    }

    override fun onDestroy() {
        setAsrKeepScreenOn(false)
        stopDeleteRepeat()
        flushVoiceHistory()
        cancelPendingHandwritingRecognition()
        handwritingRecognizer.close()
        cancelInstructionMode(refresh = false)
        session?.destroy()
        super.onDestroy()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        stopDeleteRepeat()
        resetHandwritingMode(commitComposition = true)
        flushVoiceHistory()
        editorSessionId += 1
        session?.takeIf { it.state != AsrSession.State.IDLE }?.let {
            discardAsrUntilIdle = true
            it.stop()
        }
        cancelInstructionMode(refresh = false)
        super.onFinishInputView(finishingInput)
    }

    // ---------- ⌘ 快捷键面板 ----------

    private fun toggleShortcutPage() {
        if (!shortcutPageVisible && handwritingEnabled) {
            resetHandwritingMode(commitComposition = true)
        }
        shortcutPageVisible = !shortcutPageVisible
        animateShortcutPage(shortcutPageVisible)
        btnCmd.background = getDrawable(
            if (shortcutPageVisible) R.drawable.bg_icon_button_selected else R.drawable.bg_icon_button
        )
    }

    private fun animateShortcutPage(showShortcuts: Boolean) {
        val incoming = if (showShortcuts) pageKeys else pageVoice
        val outgoing = if (showShortcuts) pageVoice else pageKeys
        val distance = resources.displayMetrics.widthPixels * PAGE_SLIDE_DISTANCE_RATIO

        incoming.animate().cancel()
        outgoing.animate().cancel()
        incoming.visibility = View.VISIBLE
        incoming.alpha = 0f
        incoming.translationX = if (showShortcuts) distance else -distance
        incoming.animate()
            .alpha(1f)
            .translationX(0f)
            .setDuration(PAGE_SLIDE_DURATION_MS)
            .start()
        outgoing.animate()
            .alpha(0f)
            .translationX(if (showShortcuts) -distance else distance)
            .setDuration(PAGE_SLIDE_DURATION_MS)
            .withEndAction {
                outgoing.visibility = View.GONE
                outgoing.alpha = 1f
                outgoing.translationX = 0f
            }
            .start()
    }

    // ---------- 手写输入 ----------

    private fun toggleHandwritingMode() {
        if (handwritingEnabled) {
            resetHandwritingMode(commitComposition = true)
            refreshIdleHint()
            return
        }
        if (shortcutPageVisible) {
            shortcutPageVisible = false
            animateShortcutPage(showShortcuts = false)
            btnCmd.background = getDrawable(R.drawable.bg_icon_button)
        }
        if (instructionState !is InstructionState.Off) cancelInstructionMode(refresh = false)
        session?.takeIf { it.state != AsrSession.State.IDLE }?.let {
            discardAsrUntilIdle = true
            it.stop()
        }
        flushVoiceHistory()
        handwritingEnabled = true
        handwritingOperationId += 1
        handwritingReadyLanguage = null
        inputModeGroup.visibility = View.GONE
        previewContainer.visibility = View.GONE
        handwritingPanel.visibility = View.VISIBLE
        waveform.visibility = View.GONE
        renderPurposeModeVisual()
        updateHandwritingLanguageVisual()
        textStatus.text = getString(R.string.handwriting_preparing)
        textStatus.setTextColor(getColor(R.color.text_hint))
        prepareHandwritingModel()
    }

    private fun resetHandwritingMode(commitComposition: Boolean) {
        cancelPendingHandwritingRecognition()
        handwritingOperationId += 1
        if (commitComposition) finishHandwritingComposition() else cancelHandwritingComposition()
        handwritingEnabled = false
        handwritingReadyLanguage = null
        if (::handwritingPad.isInitialized) {
            handwritingPad.clear()
            handwritingCandidates.removeAllViews()
            handwritingCandidateScroll.visibility = View.GONE
            handwritingPanel.visibility = View.GONE
            previewContainer.visibility = View.VISIBLE
            inputModeGroup.visibility = View.VISIBLE
            waveform.visibility = View.VISIBLE
            renderPurposeModeVisual()
        }
    }

    private fun prepareHandwritingModel() {
        val language = selectedHandwritingLanguage
        val requestId = ++handwritingOperationId
        handwritingRecognizer.prepare(language, object : HandwritingRecognizer.Callback {
            override fun onModelReady() {
                if (!isCurrentHandwritingRequest(requestId, language)) return
                handwritingReadyLanguage = language
                textStatus.text = getString(R.string.handwriting_ready)
                textStatus.setTextColor(getColor(R.color.status_ok))
                if (handwritingPad.hasInk()) scheduleHandwritingRecognition()
            }

            override fun onCandidates(candidates: List<String>) = Unit

            override fun onError(message: String) {
                if (!isCurrentHandwritingRequest(requestId, language)) return
                textStatus.text = getString(R.string.handwriting_error, message)
                textStatus.setTextColor(getColor(R.color.error_red))
            }
        })
    }

    private fun selectHandwritingLanguage(language: HandwritingLanguage) {
        if (selectedHandwritingLanguage == language) return
        clearHandwriting(cancelComposition = true)
        selectedHandwritingLanguage = language
        handwritingReadyLanguage = null
        updateHandwritingLanguageVisual()
        textStatus.text = getString(R.string.handwriting_preparing)
        textStatus.setTextColor(getColor(R.color.text_hint))
        prepareHandwritingModel()
    }

    private fun updateHandwritingLanguageVisual() {
        if (!::btnHandwritingChinese.isInitialized) return
        listOf(
            HandwritingLanguage.SIMPLIFIED_CHINESE to btnHandwritingChinese,
            HandwritingLanguage.ENGLISH_US to btnHandwritingEnglish,
        ).forEach { (language, button) ->
            val selected = language == selectedHandwritingLanguage
            button.isSelected = selected
            button.background = getDrawable(
                if (selected) R.drawable.bg_mode_selected else R.drawable.bg_mode_idle
            )
            val description = getString(
                if (language == HandwritingLanguage.SIMPLIFIED_CHINESE) {
                    R.string.a11y_handwriting_chinese
                } else {
                    R.string.a11y_handwriting_english
                }
            )
            button.contentDescription = if (selected) {
                getString(R.string.a11y_mode_selected, description)
            } else {
                description
            }
        }
    }

    private fun onHandwritingStrokeStarted() {
        cancelPendingHandwritingRecognition()
        handwritingOperationId += 1
        if (handwritingComposingText.isNotEmpty()) {
            finishHandwritingComposition()
            handwritingPad.clear()
            handwritingCandidates.removeAllViews()
            handwritingCandidateScroll.visibility = View.GONE
        }
    }

    private fun scheduleHandwritingRecognition() {
        cancelPendingHandwritingRecognition()
        val action = Runnable { recognizeHandwriting() }
        pendingHandwritingRecognition = action
        handwritingHandler.postDelayed(action, HANDWRITING_IDLE_DELAY_MS)
    }

    private fun recognizeHandwriting() {
        pendingHandwritingRecognition = null
        if (!handwritingEnabled || !handwritingPad.hasInk()) return
        val language = selectedHandwritingLanguage
        if (handwritingReadyLanguage != language) {
            textStatus.text = getString(R.string.handwriting_preparing)
            textStatus.setTextColor(getColor(R.color.text_hint))
            prepareHandwritingModel()
            return
        }
        val strokes = handwritingPad.snapshot()
        val requestId = ++handwritingOperationId
        textStatus.text = getString(R.string.handwriting_recognizing)
        textStatus.setTextColor(getColor(R.color.text_hint))
        val preContext = currentInputConnection
            ?.getTextBeforeCursor(HANDWRITING_PRE_CONTEXT_CHARS, 0)
            ?.toString()
            .orEmpty()
        handwritingRecognizer.recognize(
            strokes = strokes,
            width = handwritingPad.width.toFloat(),
            height = handwritingPad.height.toFloat(),
            language = language,
            preContext = preContext,
            callback = object : HandwritingRecognizer.Callback {
                override fun onModelReady() = Unit

                override fun onCandidates(candidates: List<String>) {
                    if (!isCurrentHandwritingRequest(requestId, language)) return
                    if (candidates.isEmpty()) {
                        textStatus.text = getString(R.string.handwriting_no_result)
                        textStatus.setTextColor(getColor(R.color.error_red))
                        showHandwritingCandidates(emptyList())
                        return
                    }
                    setHandwritingComposition(candidates.first())
                    showHandwritingCandidates(candidates.take(HANDWRITING_CANDIDATE_LIMIT))
                    textStatus.text = getString(R.string.handwriting_candidate, candidates.first())
                    textStatus.setTextColor(getColor(R.color.status_ok))
                }

                override fun onError(message: String) {
                    if (!isCurrentHandwritingRequest(requestId, language)) return
                    textStatus.text = getString(R.string.handwriting_error, message)
                    textStatus.setTextColor(getColor(R.color.error_red))
                }
            },
        )
    }

    private fun showHandwritingCandidates(candidates: List<String>) {
        handwritingCandidates.removeAllViews()
        handwritingCandidateScroll.visibility = if (candidates.isEmpty()) View.GONE else View.VISIBLE
        candidates.forEachIndexed { index, candidate ->
            val button = TextView(this).apply {
                text = candidate
                textSize = 16f
                gravity = android.view.Gravity.CENTER
                minWidth = (52 * resources.displayMetrics.density).toInt()
                setPadding(
                    (14 * resources.displayMetrics.density).toInt(), 0,
                    (14 * resources.displayMetrics.density).toInt(), 0,
                )
                setTextColor(getColor(R.color.text_primary))
                background = getDrawable(
                    if (index == 0) R.drawable.bg_mode_selected else R.drawable.bg_action_chip
                )
                contentDescription = getString(R.string.handwriting_candidate, candidate)
                setSystemHapticClick {
                    setHandwritingComposition(candidate)
                    finishHandwritingComposition()
                    clearHandwriting(cancelComposition = false)
                }
            }
            val margin = (3 * resources.displayMetrics.density).toInt()
            handwritingCandidates.addView(
                button,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                ).apply { setMargins(margin, 2, margin, 2) },
            )
        }
    }

    private fun setHandwritingComposition(text: String) {
        if (currentInputConnection?.setComposingText(text, 1) == true) {
            handwritingComposingText = text
        }
    }

    private fun finishHandwritingComposition() {
        val text = handwritingComposingText
        if (text.isEmpty()) return
        currentInputConnection?.finishComposingText()
        sessionText.append(text)
        recordInput(InputSource.HANDWRITING, text)
        handwritingComposingText = ""
        refreshPreview()
    }

    private fun cancelHandwritingComposition() {
        if (handwritingComposingText.isEmpty()) return
        currentInputConnection?.setComposingText("", 1)
        currentInputConnection?.finishComposingText()
        handwritingComposingText = ""
    }

    private fun clearHandwriting(cancelComposition: Boolean) {
        cancelPendingHandwritingRecognition()
        handwritingOperationId += 1
        if (cancelComposition) cancelHandwritingComposition()
        handwritingPad.clear()
        handwritingCandidates.removeAllViews()
        handwritingCandidateScroll.visibility = View.GONE
        if (handwritingEnabled) {
            val ready = handwritingReadyLanguage == selectedHandwritingLanguage
            textStatus.text = getString(
                if (ready) R.string.handwriting_ready else R.string.handwriting_preparing
            )
            textStatus.setTextColor(getColor(R.color.text_hint))
        }
    }

    private fun cancelPendingHandwritingRecognition() {
        pendingHandwritingRecognition?.let { handwritingHandler.removeCallbacks(it) }
        pendingHandwritingRecognition = null
    }

    private fun isCurrentHandwritingRequest(
        requestId: Long,
        language: HandwritingLanguage,
    ): Boolean =
        handwritingEnabled && handwritingOperationId == requestId &&
            selectedHandwritingLanguage == language

    private fun applyMaximumPanelHeight(root: View) {
        val height = InputPanelSizing.heightPx(
            screenHeightPx = resources.displayMetrics.heightPixels,
            density = resources.displayMetrics.density,
            desiredHeightDp = PANEL_DESIRED_HEIGHT_DP,
            maximumScreenFraction = PANEL_MAXIMUM_SCREEN_FRACTION,
        )
        val params = root.layoutParams ?: ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            height,
        )
        params.width = ViewGroup.LayoutParams.MATCH_PARENT
        params.height = height
        root.layoutParams = params
    }

    private fun bindShortcutKeys(view: View) {
        // ESC / Tab / 方向键：直接发按键事件
        mapOf(
            R.id.keyEsc to KeyEvent.KEYCODE_ESCAPE,
            R.id.keyTab to KeyEvent.KEYCODE_TAB,
            R.id.keyLeft to KeyEvent.KEYCODE_DPAD_LEFT,
            R.id.keyUp to KeyEvent.KEYCODE_DPAD_UP,
            R.id.keyDown to KeyEvent.KEYCODE_DPAD_DOWN,
            R.id.keyRight to KeyEvent.KEYCODE_DPAD_RIGHT,
        ).forEach { (id, code) ->
            view.findViewById<View>(id).setSystemHapticClick { sendDownUpKeyEvents(code) }
        }
        // 全选 / 复制 / 粘贴：走编辑菜单动作，兼容性最好
        view.findViewById<TextView>(R.id.keySelectAll).setSystemHapticClick {
            currentInputConnection?.performContextMenuAction(android.R.id.selectAll)
        }
        view.findViewById<TextView>(R.id.keyCopy).setSystemHapticClick {
            currentInputConnection?.performContextMenuAction(android.R.id.copy)
        }
        view.findViewById<TextView>(R.id.keyPaste).setSystemHapticClick {
            currentInputConnection?.performContextMenuAction(android.R.id.paste)
        }
        // 撤销：发送 Ctrl+Z（是否生效取决于目标 App 是否实现了撤销）
        view.findViewById<TextView>(R.id.keyUndo).setSystemHapticClick {
            sendCtrlKey(KeyEvent.KEYCODE_Z)
        }
    }

    private fun sendCtrlKey(keyCode: Int) {
        val ic = currentInputConnection ?: return
        val now = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, KeyEvent.META_CTRL_ON))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, KeyEvent.META_CTRL_ON))
    }

    private fun bindRepeatingDelete(button: View) {
        deleteButton = button
        // 无障碍服务触发 performClick 时仍能完成一次删除。
        button.setOnClickListener { view ->
            if (!suppressDeleteClick) {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                deleteOnce()
            }
        }
        button.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.isPressed = true
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    deleteOnce()
                    deleteRepeatActive = true
                    deleteRepeatHandler.removeCallbacks(deleteRepeatAction)
                    deleteRepeatHandler.postDelayed(
                        deleteRepeatAction,
                        ViewConfiguration.getLongPressTimeout().toLong(),
                    )
                    true
                }
                MotionEvent.ACTION_UP -> {
                    stopDeleteRepeat()
                    view.isPressed = false
                    // 通知无障碍框架发生了点击，但避免再删除一次。
                    suppressDeleteClick = true
                    view.performClick()
                    suppressDeleteClick = false
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    stopDeleteRepeat()
                    view.isPressed = false
                    true
                }
                else -> true
            }
        }
    }

    private fun bindKeyboardSwitcher(button: View) {
        button.setSystemHapticClick {
            if (!switchToNextKeyboard()) {
                getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
            }
        }
        button.setOnLongClickListener { switcher ->
            switcher.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
            true
        }
    }

    @Suppress("DEPRECATION")
    private fun switchToNextKeyboard(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return switchToNextInputMethod(false)
        }
        val token = window?.window?.decorView?.windowToken ?: return false
        return getSystemService(InputMethodManager::class.java)
            ?.switchToNextInputMethod(token, false) == true
    }

    private fun deleteOnce() {
        if (handwritingEnabled &&
            (handwritingPad.hasInk() || handwritingComposingText.isNotEmpty())
        ) {
            clearHandwriting(cancelComposition = true)
            return
        }
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
    }

    private fun stopDeleteRepeat() {
        deleteRepeatActive = false
        deleteRepeatHandler.removeCallbacks(deleteRepeatAction)
        deleteButton?.isPressed = false
    }

    // ---------- 会话控制 ----------

    private fun toggleSession() {
        if (handwritingEnabled) resetHandwritingMode(commitComposition = true)
        if (instructionState is InstructionState.Processing ||
            instructionState is InstructionState.Reviewing
        ) return
        val s = session ?: AsrSession(applicationContext, this).also { session = it }
        when (s.state) {
            AsrSession.State.IDLE -> {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    textStatus.text = getString(R.string.hint_no_permission)
                    textStatus.setTextColor(getColor(R.color.error_red))
                    return
                }
                if (!Prefs.hasAsrCredentials(this)) {
                    textStatus.text = getString(R.string.hint_no_credentials)
                    textStatus.setTextColor(getColor(R.color.error_red))
                    return
                }
                val armed = instructionState as? InstructionState.Armed
                if (armed != null) {
                    instructionText = StringBuilder()
                    instructionInterim = ""
                    instructionState = InstructionState.Listening(armed.document)
                    renderInstructionState()
                } else {
                    flushVoiceHistory()
                    sessionText = StringBuilder()
                    voiceHistoryBuffer = StringBuilder()
                    interim = ""
                    textPreview.text = ""
                }
                waveform.clear()
                preserveAsrErrorOnIdle = false
                s.start(inputMode)
            }
            AsrSession.State.STREAMING,
            AsrSession.State.CONNECTING,
            AsrSession.State.STARTING -> s.stop()
            AsrSession.State.FINISHING -> Unit
        }
    }

    private fun refreshIdleHint() {
        if (instructionState !is InstructionState.Off) {
            renderInstructionState()
            return
        }
        textStatus.text = getString(when (inputMode) {
            SpeechInputMode.CHINESE -> if (
                englishRecognitionStrategy == EnglishRecognitionStrategy.REALTIME_BILINGUAL
            ) {
                R.string.hint_tap_bilingual
            } else {
                R.string.hint_tap_to_talk
            }
            SpeechInputMode.NUMBER -> R.string.hint_tap_number
            SpeechInputMode.ENGLISH -> R.string.hint_tap_english
        })
        textStatus.setTextColor(getColor(R.color.text_hint))
    }

    private fun updateMicVisual(recording: Boolean) {
        btnMic.background = getDrawable(
            if (recording) R.drawable.bg_mic_button_recording else R.drawable.bg_mic_button
        )
        btnMic.setImageResource(if (recording) R.drawable.ic_stop_white else R.drawable.ic_mic_white)
        btnMic.contentDescription = getString(
            when {
                recording && instructionState is InstructionState.Listening ->
                    R.string.a11y_stop_instruction_recording
                !recording && instructionState is InstructionState.Armed ->
                    R.string.a11y_start_instruction_recording
                recording -> R.string.a11y_stop_recording
                else -> R.string.a11y_start_recording
            }
        )
    }

    private fun selectInputMode(mode: SpeechInputMode) {
        val normalizedMode = mode.normalizedFor(englishRecognitionStrategy)
        if (normalizedMode == inputMode && pendingInputMode == null) return
        val activeSession = session
        if (activeSession != null && activeSession.state != AsrSession.State.IDLE) {
            pendingInputMode = normalizedMode
            textStatus.text = getString(R.string.hint_switching_mode)
            textStatus.setTextColor(getColor(R.color.text_hint))
            activeSession.stop()
            return
        }
        applyInputMode(normalizedMode)
    }

    private fun applyInputMode(mode: SpeechInputMode) {
        inputMode = mode.normalizedFor(englishRecognitionStrategy)
        pendingInputMode = null
        Prefs.put(this, Prefs.KEY_INPUT_MODE, mode.preferenceValue)
        updateInputModeVisual()
        refreshIdleHint()
    }

    private fun updateInputModeVisual() {
        if (!::modeButtons.isInitialized) return
        val visibleModes = SpeechInputMode.visibleModes(englishRecognitionStrategy)
        modeButtons.forEach { (mode, button) ->
            val visible = mode in visibleModes
            button.visibility = if (visible) View.VISIBLE else View.GONE
            val selected = mode == inputMode
            button.isSelected = visible && selected
            button.background = getDrawable(
                if (visible && selected) R.drawable.bg_mode_selected else R.drawable.bg_mode_idle
            )
            val modeDescription = getString(when (mode) {
                SpeechInputMode.CHINESE -> if (
                    englishRecognitionStrategy == EnglishRecognitionStrategy.REALTIME_BILINGUAL
                ) {
                    R.string.a11y_mode_bilingual
                } else {
                    R.string.a11y_mode_chinese
                }
                SpeechInputMode.NUMBER -> R.string.a11y_mode_number
                SpeechInputMode.ENGLISH -> R.string.a11y_mode_english
            })
            if (mode == SpeechInputMode.CHINESE) {
                button.text = getString(
                    if (englishRecognitionStrategy == EnglishRecognitionStrategy.REALTIME_BILINGUAL) {
                        R.string.mode_bilingual
                    } else {
                        R.string.mode_chinese
                    }
                )
            }
            button.contentDescription = if (selected) {
                getString(R.string.a11y_mode_selected, modeDescription)
            } else {
                modeDescription
            }
        }
    }

    private fun reloadSpeechModePreferences() {
        englishRecognitionStrategy = EnglishRecognitionStrategy.fromPreference(
            Prefs.get(
                this,
                Prefs.KEY_ENGLISH_RECOGNITION_STRATEGY,
                EnglishRecognitionStrategy.REALTIME_BILINGUAL.preferenceValue,
            )
        )
        val storedMode = SpeechInputMode.fromPreference(
            Prefs.get(this, Prefs.KEY_INPUT_MODE, SpeechInputMode.CHINESE.preferenceValue)
        )
        inputMode = storedMode.normalizedFor(englishRecognitionStrategy)
        if (inputMode != storedMode) {
            Prefs.put(this, Prefs.KEY_INPUT_MODE, inputMode.preferenceValue)
        }
    }

    private fun maybeOpenSettings() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED || !Prefs.hasAsrCredentials(this)
        ) openSettings()
    }

    private fun openSettings() {
        startActivity(
            Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    // ---------- 自由语音指令 ----------

    private fun toggleInstructionMode() {
        if (handwritingEnabled) resetHandwritingMode(commitComposition = true)
        if (instructionState !is InstructionState.Off) {
            cancelInstructionMode()
            return
        }
        if (aiBusy) {
            toast(getString(R.string.ai_processing))
            return
        }
        if (!Prefs.hasAiConfig(this)) {
            toast(getString(R.string.ai_not_configured))
            openSettings()
            return
        }
        val activeSession = session
        if (activeSession != null && activeSession.state != AsrSession.State.IDLE) {
            pendingInstructionActivation = true
            textStatus.text = getString(R.string.hint_switching_mode)
            textStatus.setTextColor(getColor(R.color.text_hint))
            activeSession.stop()
            return
        }
        activateInstructionMode()
    }

    private fun activateInstructionMode() {
        when (val resolved = resolveInstructionDocument()) {
            is InstructionContextResolver.Result.Success -> {
                instructionText = StringBuilder()
                instructionInterim = ""
                instructionState = InstructionState.Armed(resolved.snapshot)
                renderInstructionState()
            }
            is InstructionContextResolver.Result.Failed -> {
                val message = instructionFailureMessage(resolved.reason)
                textStatus.text = message
                textStatus.setTextColor(getColor(R.color.error_red))
                toast(message)
            }
        }
    }

    private fun resolveInstructionDocument(): InstructionContextResolver.Result =
        InstructionContextResolver.resolve(
            inputConnection = currentInputConnection,
            editorInfo = currentInputEditorInfo,
            editorSessionId = editorSessionId,
        )

    private fun instructionFailureMessage(reason: InstructionContextResolver.Failure): String =
        when (reason) {
            InstructionContextResolver.Failure.SENSITIVE_FIELD ->
                getString(R.string.instruction_sensitive_field)
            InstructionContextResolver.Failure.TOO_LONG ->
                getString(
                    R.string.instruction_too_long,
                    InstructionContextResolver.MAX_DOCUMENT_CHARS,
                )
            InstructionContextResolver.Failure.INVALID_SELECTION ->
                getString(R.string.instruction_invalid_selection)
            InstructionContextResolver.Failure.UNAVAILABLE,
            InstructionContextResolver.Failure.PARTIAL_CONTEXT ->
                getString(R.string.instruction_not_supported)
        }

    private fun cancelInstructionMode(refresh: Boolean = true) {
        if (instructionState is InstructionState.Listening &&
            session?.state?.let { it != AsrSession.State.IDLE } == true
        ) {
            discardAsrUntilIdle = true
            session?.stop()
        }
        instructionOperationId += 1
        instructionCall?.cancel()
        instructionCall = null
        if (instructionState is InstructionState.Processing) aiBusy = false
        pendingInstructionActivation = false
        instructionText = StringBuilder()
        instructionInterim = ""
        instructionState = InstructionState.Off
        if (::btnInstruction.isInitialized) {
            renderInstructionState()
            if (refresh) refreshIdleHint()
        }
    }

    private fun retryInstruction() {
        val reviewing = instructionState as? InstructionState.Reviewing ?: return
        val current = (resolveInstructionDocument() as? InstructionContextResolver.Result.Success)
            ?.snapshot
        if (!InstructionApplyPolicy.canApply(reviewing.document, current)) {
            invalidateInstructionMode(getString(R.string.instruction_context_changed))
            return
        }
        instructionCall?.cancel()
        instructionCall = null
        aiBusy = false
        instructionText = StringBuilder()
        instructionInterim = ""
        instructionState = InstructionState.Armed(reviewing.document)
        renderInstructionState()
    }

    private fun startInstructionProcessing(document: InstructionDocumentSnapshot) {
        val instruction = (instructionText.toString() + instructionInterim).trim()
        instructionInterim = ""
        if (instruction.isEmpty()) {
            instructionState = InstructionState.Armed(document)
            renderInstructionState(getString(R.string.instruction_empty_speech), error = true)
            return
        }
        val current = (resolveInstructionDocument() as? InstructionContextResolver.Result.Success)
            ?.snapshot
        if (!InstructionApplyPolicy.canApply(document, current)) {
            invalidateInstructionMode(getString(R.string.instruction_context_changed))
            return
        }

        val operationId = ++instructionOperationId
        instructionState = InstructionState.Processing(document, instruction, operationId)
        aiBusy = true
        renderInstructionState()
        val prompt = InstructionPromptBuilder.build(document, instruction)
        instructionCall = AiProcessor.processInstruction(this, prompt, object : AiProcessor.Callback {
            override fun onResult(text: String) {
                val current = instructionState as? InstructionState.Processing ?: return
                if (current.operationId != operationId) return
                instructionCall = null
                aiBusy = false
                val result = text.trim()
                if (result.isEmpty()) {
                    instructionState = InstructionState.Armed(document)
                    renderInstructionState(getString(R.string.instruction_result_empty), error = true)
                    return
                }
                instructionState = InstructionState.Reviewing(
                    document = document,
                    instruction = instruction,
                    result = result,
                    operationId = operationId,
                )
                renderInstructionState()
            }

            override fun onError(message: String) {
                val current = instructionState as? InstructionState.Processing ?: return
                if (current.operationId != operationId) return
                instructionCall = null
                aiBusy = false
                instructionState = InstructionState.Armed(document)
                renderInstructionState(message, error = true)
            }
        })
    }

    private fun applyInstructionResult() {
        val reviewing = instructionState as? InstructionState.Reviewing ?: return
        val current = (resolveInstructionDocument() as? InstructionContextResolver.Result.Success)
            ?.snapshot
        if (!InstructionApplyPolicy.canApply(reviewing.document, current)) {
            renderInstructionState(getString(R.string.instruction_context_changed), error = true)
            toast(getString(R.string.instruction_context_changed))
            return
        }
        val connection = currentInputConnection ?: run {
            renderInstructionState(getString(R.string.instruction_context_changed), error = true)
            return
        }

        val applied = connection.beginBatchEdit().let {
            try {
                connection.finishComposingText()
                when (reviewing.document.targetScope) {
                    InstructionTargetScope.SELECTION ->
                        if (reviewing.document.offsetsAreAbsolute) {
                            connection.setSelection(
                                reviewing.document.targetStart,
                                reviewing.document.targetEnd,
                            ) && connection.commitText(reviewing.result, 1)
                        } else {
                            connection.commitText(reviewing.result, 1)
                        }
                    InstructionTargetScope.FULL_FIELD ->
                        connection.setSelection(0, reviewing.document.fullText.length) &&
                            connection.commitText(reviewing.result, 1)
                    InstructionTargetScope.INSERT ->
                        connection.setSelection(
                            reviewing.document.targetStart,
                            reviewing.document.targetEnd,
                        ) && connection.commitText(reviewing.result, 1)
                }
            } finally {
                connection.endBatchEdit()
            }
        }
        if (!applied) {
            renderInstructionState(getString(R.string.instruction_context_changed), error = true)
            toast(getString(R.string.instruction_context_changed))
            return
        }

        sessionText = StringBuilder()
        interim = ""
        recordInput(InputSource.INSTRUCTION, reviewing.result)
        cancelInstructionMode()
        toast(getString(R.string.instruction_applied))
    }

    private fun invalidateInstructionMode(message: String) {
        cancelInstructionMode(refresh = false)
        textPreview.text = ""
        textStatus.text = message
        textStatus.setTextColor(getColor(R.color.error_red))
        toast(message)
    }

    private fun renderInstructionState(statusOverride: String? = null, error: Boolean = false) {
        val state = instructionState
        val document = state.documentOrNull()
        val active = state !is InstructionState.Off
        renderPurposeModeVisual()
        textInstructionScope.visibility = if (active && document != null) View.VISIBLE else View.GONE
        if (document != null) {
            textInstructionScope.text = instructionScopeText(document)
            btnInstruction.contentDescription = getString(
                R.string.a11y_instruction_active,
                instructionScopeText(document),
            )
        } else {
            btnInstruction.contentDescription = getString(R.string.a11y_instruction)
        }

        setAiEnabled(!active && !aiBusy)
        when (state) {
            InstructionState.Off -> {
                instructionReviewActions.visibility = View.GONE
                voicePurposeControls.visibility = View.VISIBLE
                btnMic.visibility = View.VISIBLE
                waveform.visibility = View.VISIBLE
                textInstructionScope.visibility = View.GONE
                refreshPreview()
            }
            is InstructionState.Armed -> {
                instructionReviewActions.visibility = View.GONE
                voicePurposeControls.visibility = View.VISIBLE
                btnMic.visibility = View.VISIBLE
                waveform.visibility = View.VISIBLE
                updateMicVisual(false)
                waveform.clear()
                textPreview.text = ""
                textStatus.text = getString(R.string.instruction_hint_armed)
                textStatus.setTextColor(getColor(R.color.text_hint))
            }
            is InstructionState.Listening -> {
                instructionReviewActions.visibility = View.GONE
                voicePurposeControls.visibility = View.VISIBLE
                btnMic.visibility = View.VISIBLE
                waveform.visibility = View.VISIBLE
                refreshInstructionPreview()
            }
            is InstructionState.Processing -> {
                voicePurposeControls.visibility = View.GONE
                btnMic.visibility = View.GONE
                waveform.visibility = View.GONE
                instructionReviewActions.visibility = View.VISIBLE
                btnInstructionCancel.visibility = View.VISIBLE
                btnInstructionCancel.text = getString(R.string.instruction_cancel_processing)
                btnInstructionRetry.visibility = View.GONE
                btnInstructionApply.visibility = View.GONE
                textPreview.text = getString(
                    R.string.instruction_processing,
                    state.instruction.take(INSTRUCTION_PREVIEW_LIMIT),
                )
                textStatus.text = getString(R.string.ai_processing)
                textStatus.setTextColor(getColor(R.color.text_hint))
            }
            is InstructionState.Reviewing -> {
                voicePurposeControls.visibility = View.GONE
                btnMic.visibility = View.GONE
                waveform.visibility = View.GONE
                instructionReviewActions.visibility = View.VISIBLE
                btnInstructionCancel.visibility = View.VISIBLE
                btnInstructionCancel.text = getString(R.string.instruction_cancel)
                btnInstructionRetry.visibility = View.VISIBLE
                btnInstructionApply.visibility = View.VISIBLE
                textPreview.text = state.result
                textStatus.text = when (state.document.targetScope) {
                    InstructionTargetScope.SELECTION -> getString(
                        R.string.instruction_review_selection,
                        state.document.targetText.length,
                    )
                    InstructionTargetScope.FULL_FIELD ->
                        getString(R.string.instruction_review_full)
                    InstructionTargetScope.INSERT ->
                        getString(R.string.instruction_review_insert)
                }
                textStatus.setTextColor(getColor(R.color.text_hint))
            }
        }
        if (statusOverride != null) {
            textStatus.text = statusOverride
            textStatus.setTextColor(getColor(if (error) R.color.error_red else R.color.text_hint))
        }
    }

    private fun renderPurposeModeVisual() {
        if (!::btnInstruction.isInitialized) return
        val instructionActive = instructionState !is InstructionState.Off
        val specialModeActive = instructionActive || handwritingEnabled
        btnInstruction.isSelected = instructionActive
        btnHandwritingMode.isSelected = handwritingEnabled
        voicePurposeThumb.animate().cancel()
        voicePurposeThumb.visibility = if (specialModeActive) View.VISIBLE else View.INVISIBLE
        if (specialModeActive) {
            voicePurposeThumb.animate()
                .translationX(if (handwritingEnabled) purposeThumbTranslationPx() else 0f)
                .setDuration(PURPOSE_SLIDE_DURATION_MS)
                .start()
        } else {
            voicePurposeThumb.translationX = 0f
        }
        btnHandwritingMode.contentDescription = getString(
            if (handwritingEnabled) R.string.a11y_disable_handwriting
            else R.string.a11y_enable_handwriting
        )
    }

    private fun instructionScopeText(document: InstructionDocumentSnapshot): String =
        when (document.targetScope) {
            InstructionTargetScope.SELECTION -> getString(
                if (document.contextIsComplete) {
                    R.string.instruction_scope_selection
                } else {
                    R.string.instruction_scope_selection_nearby
                },
                document.targetText.length,
                document.fullText.length,
            )
            InstructionTargetScope.FULL_FIELD -> getString(
                R.string.instruction_scope_full,
                document.fullText.length,
            )
            InstructionTargetScope.INSERT -> getString(R.string.instruction_scope_insert)
        }

    private fun refreshInstructionPreview() {
        val text = instructionText.toString() + instructionInterim
        textPreview.text = if (text.isEmpty()) "" else "你说：$text"
    }

    // ---------- AI 功能 ----------

    private fun runAi(mode: AiProcessor.Mode) {
        if (aiBusy) return
        val selectedText = currentInputConnection
            ?.getSelectedText(0)
            ?.toString()
            ?.takeIf { it.isNotBlank() }
        val originalText = sessionText.toString() + interim
        val promptText = TextReplacementPolicy.promptText(selectedText, originalText)
        if (promptText.isEmpty()) {
            toast(getString(R.string.ai_empty_text)); return
        }
        if (!Prefs.hasAiConfig(this)) {
            toast(getString(R.string.ai_not_configured)); return
        }
        val replaceOriginal = Prefs.getBool(this, Prefs.KEY_AI_REPLACE_ORIGINAL, false)
        if (selectedText == null && replaceOriginal &&
            session?.state?.let { it != AsrSession.State.IDLE } == true
        ) {
            toast(getString(R.string.ai_finish_recording_before_replace))
            return
        }
        if (selectedText == null && replaceOriginal) currentInputConnection?.finishComposingText()
        aiBusy = true
        setAiEnabled(false)
        textStatus.text = getString(R.string.ai_processing)
        textStatus.setTextColor(getColor(R.color.text_hint))
        AiProcessor.process(this, mode, promptText, object : AiProcessor.Callback {
            override fun onResult(text: String) {
                val result = text
                aiBusy = false
                setAiEnabled(true)
                if (selectedText != null) {
                    if (!writeSelectedTextResult(selectedText, result, replaceOriginal)) {
                        textStatus.text = getString(R.string.ai_replace_failed)
                        textStatus.setTextColor(getColor(R.color.error_red))
                        toast(getString(R.string.ai_replace_failed))
                        return
                    }
                    sessionText = StringBuilder(result)
                } else if (replaceOriginal) {
                    if (!replaceCurrentSessionText(originalText, result)) {
                        textStatus.text = getString(R.string.ai_replace_failed)
                        textStatus.setTextColor(getColor(R.color.error_red))
                        toast(getString(R.string.ai_replace_failed))
                        return
                    }
                    sessionText = StringBuilder(result)
                } else {
                    val committed = currentInputConnection?.let { ic ->
                        ic.finishComposingText()
                        ic.commitText("\n" + result, 1)
                    } == true
                    if (!committed) {
                        textStatus.text = getString(R.string.ai_replace_failed)
                        textStatus.setTextColor(getColor(R.color.error_red))
                        return
                    }
                    sessionText.append("\n").append(result)
                }
                recordInput(InputSource.AI_ACTION, result)
                interim = ""
                refreshPreview()
                refreshIdleHint()
            }

            override fun onError(message: String) {
                aiBusy = false
                setAiEnabled(true)
                textStatus.text = message
                textStatus.setTextColor(getColor(R.color.error_red))
            }
        })
    }

    private fun writeSelectedTextResult(
        originalSelection: String,
        result: String,
        replaceOriginal: Boolean,
    ): Boolean {
        val ic = currentInputConnection ?: return false
        if (!TextReplacementPolicy.canApplyToSelection(
                ic.getSelectedText(0),
                originalSelection,
            )
        ) return false

        val output = TextReplacementPolicy.outputForSelection(
            originalSelection,
            result,
            replaceOriginal,
        )
        return ic.commitText(output, 1)
    }

    private fun replaceCurrentSessionText(originalText: String, result: String): Boolean {
        val ic = currentInputConnection ?: return false
        val textBeforeCursor = ic.getTextBeforeCursor(originalText.length, 0)
        if (!TextReplacementPolicy.canReplace(textBeforeCursor, originalText)) return false

        ic.beginBatchEdit()
        return try {
            val deleted = ic.deleteSurroundingText(originalText.length, 0)
            val committed = deleted && ic.commitText(result, 1)
            if (deleted && !committed) ic.commitText(originalText, 1)
            committed
        } finally {
            ic.endBatchEdit()
        }
    }

    private fun setAiEnabled(enabled: Boolean) {
        aiButtons.forEach { it.isEnabled = enabled; it.alpha = if (enabled) 1f else 0.4f }
    }

    // ---------- AsrSession.Listener ----------

    override fun onStateChanged(state: AsrSession.State) {
        setAsrKeepScreenOn(state != AsrSession.State.IDLE)
        if (handwritingEnabled) {
            if (state == AsrSession.State.IDLE) {
                updateMicVisual(false)
                discardAsrUntilIdle = false
            }
            return
        }
        when (state) {
            AsrSession.State.IDLE -> {
                updateMicVisual(false)
                flushVoiceHistory()
                if (discardAsrUntilIdle) discardAsrUntilIdle = false
                val pending = pendingInputMode
                when {
                    pending != null -> applyInputMode(pending)
                    pendingInstructionActivation -> {
                        pendingInstructionActivation = false
                        activateInstructionMode()
                    }
                    instructionState is InstructionState.Listening -> {
                        val listening = instructionState as InstructionState.Listening
                        instructionState = InstructionState.Armed(listening.document)
                        renderInstructionState()
                    }
                    instructionState is InstructionState.Armed -> Unit
                    instructionState !is InstructionState.Off -> renderInstructionState()
                    preserveAsrErrorOnIdle -> preserveAsrErrorOnIdle = false
                    else -> refreshIdleHint()
                }
            }
            AsrSession.State.CONNECTING -> {
                textStatus.text = getString(R.string.hint_connecting)
                textStatus.setTextColor(getColor(R.color.text_hint))
            }
            AsrSession.State.STARTING -> {
                textStatus.text = getString(R.string.hint_connecting)
                textStatus.setTextColor(getColor(R.color.text_hint))
            }
            AsrSession.State.STREAMING -> {
                updateMicVisual(true)
                textStatus.text = getString(
                    if (instructionState is InstructionState.Listening) {
                        R.string.instruction_hint_listening
                    } else {
                        R.string.hint_listening
                    }
                )
                textStatus.setTextColor(getColor(R.color.status_ok))
            }
            AsrSession.State.FINISHING -> {
                textStatus.text = getString(
                    if (instructionState is InstructionState.Listening) {
                        R.string.instruction_hint_finishing
                    } else {
                        R.string.hint_finishing
                    }
                )
                textStatus.setTextColor(getColor(R.color.text_hint))
            }
        }
    }

    override fun onCommitted(text: String) {
        if (discardAsrUntilIdle) return
        if (instructionState is InstructionState.Listening) {
            instructionText.append(text)
            instructionInterim = ""
            refreshInstructionPreview()
            return
        }
        val committedToEditor = currentInputConnection?.commitText(text, 1) == true
        sessionText.append(text)
        if (committedToEditor) voiceHistoryBuffer.append(text)
        interim = ""
        refreshPreview()
    }

    override fun onInterim(text: String) {
        if (discardAsrUntilIdle) return
        if (instructionState is InstructionState.Listening) {
            instructionInterim = text
            refreshInstructionPreview()
            return
        }
        val ic = currentInputConnection ?: return
        if (text.isEmpty()) {
            ic.finishComposingText()
        } else {
            ic.setComposingText(text, 1)
        }
        interim = text
        refreshPreview()
    }

    override fun onInterimDiscarded() {
        if (discardAsrUntilIdle) return
        if (instructionState is InstructionState.Listening) {
            instructionInterim = ""
            refreshInstructionPreview()
            return
        }
        if (interim.isNotEmpty()) {
            currentInputConnection?.setComposingText("", 1)
        }
        interim = ""
        refreshPreview()
    }

    override fun onError(message: String) {
        if (discardAsrUntilIdle) {
            updateMicVisual(false)
            return
        }
        val listening = instructionState as? InstructionState.Listening
        if (listening != null) {
            instructionState = InstructionState.Armed(listening.document)
            renderInstructionState(getString(R.string.error_prefix, message), error = true)
            updateMicVisual(false)
            return
        }
        preserveAsrErrorOnIdle = true
        textStatus.text = getString(R.string.error_prefix, message)
        textStatus.setTextColor(getColor(R.color.error_red))
        updateMicVisual(false)
    }

    override fun onAmplitude(level: Int) {
        if (discardAsrUntilIdle) return
        waveform.push(level)
    }

    override fun onSessionEnd() {
        if (discardAsrUntilIdle) return
        val listening = instructionState as? InstructionState.Listening
        if (listening != null) {
            startInstructionProcessing(listening.document)
        } else {
            flushVoiceHistory()
        }
    }

    /** ASR 活跃时阻止系统因无操作自动息屏；会话结束后立即恢复系统策略。 */
    private fun setAsrKeepScreenOn(enabled: Boolean) {
        if (asrKeepScreenOn == enabled) return
        asrKeepScreenOn = enabled
        if (::inputRoot.isInitialized) inputRoot.keepScreenOn = enabled
    }

    private fun flushVoiceHistory() {
        val text = voiceHistoryBuffer.toString()
        voiceHistoryBuffer = StringBuilder()
        recordInput(InputSource.VOICE, text)
    }

    private fun recordInput(source: InputSource, text: String) {
        if (text.isBlank()) return
        val inputType = currentInputEditorInfo?.inputType ?: android.text.InputType.TYPE_NULL
        if (InstructionContextResolver.isSensitiveInputType(inputType)) return
        try {
            inputHistoryStore.append(source, text)
        } catch (error: RuntimeException) {
            // 记录失败不能影响向宿主输入框写入文本。
            Log.e("InkTalkHistory", "Failed to append input history", error)
        }
    }

    private fun refreshPreview() {
        textPreview.text = if (interim.isEmpty()) sessionText.toString()
        else sessionText.toString() + interim
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    /**
     * 不直接读取已废弃的 HAPTIC_FEEDBACK_ENABLED；performHapticFeedback 会由系统自动读取并
     * 遵循用户的“触摸反馈”开关，也不需要 VIBRATE 权限。
     */
    private fun View.setSystemHapticClick(
        feedback: Int = HapticFeedbackConstants.KEYBOARD_TAP,
        action: (View) -> Unit,
    ) {
        setOnClickListener { view ->
            view.performHapticFeedback(feedback)
            action(view)
        }
    }

    companion object {
        private const val DELETE_REPEAT_INTERVAL_MS = 55L
        private const val HANDWRITING_IDLE_DELAY_MS = 650L
        private const val HANDWRITING_PRE_CONTEXT_CHARS = 20
        private const val HANDWRITING_CANDIDATE_LIMIT = 5
        private const val PANEL_DESIRED_HEIGHT_DP = 340f
        private const val PANEL_MAXIMUM_SCREEN_FRACTION = 0.5f
        private const val INSTRUCTION_PREVIEW_LIMIT = 36
        private const val PURPOSE_THUMB_TRANSLATION_DP = 40f
        private const val PURPOSE_SLIDE_DURATION_MS = 180L
        private const val PAGE_SLIDE_DURATION_MS = 180L
        private const val PAGE_SLIDE_DISTANCE_RATIO = 0.16f
    }

    private fun purposeThumbTranslationPx(): Float =
        PURPOSE_THUMB_TRANSLATION_DP * resources.displayMetrics.density

    private sealed interface InstructionState {
        data object Off : InstructionState
        data class Armed(val document: InstructionDocumentSnapshot) : InstructionState
        data class Listening(val document: InstructionDocumentSnapshot) : InstructionState
        data class Processing(
            val document: InstructionDocumentSnapshot,
            val instruction: String,
            val operationId: Long,
        ) : InstructionState
        data class Reviewing(
            val document: InstructionDocumentSnapshot,
            val instruction: String,
            val result: String,
            val operationId: Long,
        ) : InstructionState

        fun documentOrNull(): InstructionDocumentSnapshot? = when (this) {
            Off -> null
            is Armed -> document
            is Listening -> document
            is Processing -> document
            is Reviewing -> document
        }
    }
}
