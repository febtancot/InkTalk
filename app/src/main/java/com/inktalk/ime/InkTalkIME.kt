package com.inktalk.ime

import android.Manifest
import android.content.res.ColorStateList
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ImageButton
import android.widget.HorizontalScrollView
import android.widget.FrameLayout
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
import com.inktalk.ime.handwriting.HandwritingCandidateMerger
import com.inktalk.ime.handwriting.HandwritingLanguage
import com.inktalk.ime.handwriting.HandwritingRecognizer
import com.inktalk.ime.history.InputHistoryStore
import com.inktalk.ime.history.InputSource
import com.inktalk.ime.history.CorrectionCandidateDetector
import com.inktalk.ime.history.HotwordSelection
import com.inktalk.ime.keyboard.FullKeyboardAction
import com.inktalk.ime.keyboard.FullKeyboardLanguage
import com.inktalk.ime.keyboard.FullKeyboardPage
import com.inktalk.ime.keyboard.PinyinLexicon
import com.inktalk.ime.settings.Prefs
import com.inktalk.ime.settings.SettingsActivity
import com.inktalk.ime.ui.FullKeyboardView
import com.inktalk.ime.ui.HandwritingPadView
import com.inktalk.ime.ui.AdaptiveWindowProfile
import com.inktalk.ime.ui.InputPanelSizing
import com.inktalk.ime.ui.WaveformView
import okhttp3.Call
import java.time.Instant
import java.time.ZoneId

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
    private lateinit var btnNumericKeypadMode: ImageButton
    private lateinit var btnExtremeSideSwap: ImageButton
    private lateinit var btnSettings: ImageButton
    private lateinit var textStatus: TextView
    private lateinit var textPreview: TextView
    private lateinit var textInstructionScope: TextView
    private lateinit var waveform: WaveformView
    private lateinit var pageVoice: LinearLayout
    private lateinit var pageKeys: View
    private lateinit var inputModeGroup: View
    private lateinit var inputContentArea: View
    private lateinit var previewContainer: View
    private lateinit var handwritingPanel: View
    private lateinit var numericKeypadPanel: View
    private lateinit var fullKeyboardPanel: FullKeyboardView
    private lateinit var handwritingPad: HandwritingPadView
    private lateinit var handwritingHint: TextView
    private lateinit var handwritingCandidateScroll: HorizontalScrollView
    private lateinit var handwritingCandidates: LinearLayout
    private val handwritingCandidateButtons = mutableListOf<TextView>()
    private lateinit var voicePurposeControls: View
    private lateinit var voicePurposeModePill: View
    private lateinit var aiActionRow: LinearLayout
    private lateinit var topToolbar: LinearLayout
    private lateinit var toolbarActionGroup: LinearLayout
    private lateinit var voicePurposeButtonRow: LinearLayout
    private lateinit var voiceActionArea: View
    private lateinit var voicePurposeThumb: View
    private lateinit var instructionReviewActions: View
    private lateinit var btnInstructionCancel: TextView
    private lateinit var btnInstructionRetry: TextView
    private lateinit var btnInstructionApply: TextView
    private lateinit var aiButtons: List<TextView>
    private lateinit var modeButtons: Map<SpeechInputMode, TextView>
    private var appliedVoiceLayoutKey: String? = null
    private var wideContentOnRight = false
    private var extremeHeightMode = false

    private var session: AsrSession? = null
    private var sessionText = StringBuilder()
    private var voiceHistoryBuffer = StringBuilder()
    private var numericHistoryBuffer = StringBuilder()
    private var fullKeyboardHistoryBuffer = StringBuilder()
    private var interim = ""
    private var aiBusy = false
    private var shortcutPageVisible = false
    private var handwritingEnabled = false
    private var numericKeypadEnabled = false
    private var fullKeyboardEnabled = false
    private var fullKeyboardPreferred = false
    private var fullKeyboardLanguage = FullKeyboardLanguage.CHINESE
    private var fullKeyboardPage = FullKeyboardPage.LETTERS
    private var fullKeyboardUppercase = false
    private var pinyinComposition = ""
    @Volatile private var pinyinLexicon: PinyinLexicon? = null
    @Volatile private var pinyinLexiconLoadStarted = false
    @Volatile private var pinyinLexiconLoadFailed = false
    private var handwritingReadyLanguages = emptySet<HandwritingLanguage>()
    private var handwritingComposingText = ""
    private var handwritingOperationId = 0L
    private var inputMode = SpeechInputMode.CHINESE
    private var englishRecognitionStrategy = EnglishRecognitionStrategy.REALTIME_BILINGUAL
    private var pendingInputMode: SpeechInputMode? = null
    private var pendingInstructionActivation = false
    private var editorSessionId = 0L
    private var currentSelectionStart = -1
    private var currentSelectionEnd = -1
    private var deleteBatchText = ""
    private var deleteBatchVerified = true
    private var deleteBatchSessionId = -1L
    private var deleteBatchCursorBefore: Int? = null
    private var lastDeleteEvidence: DeleteEvidence? = null
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
    private val flushDeleteEvidenceAction = Runnable { flushDeleteEvidence() }
    private val inputRootLayoutChangeListener = View.OnLayoutChangeListener {
            _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
        val width = right - left
        val height = bottom - top
        if (width > 0 && height > 0 &&
            (width != oldRight - oldLeft || height != oldBottom - oldTop)
        ) {
            applyAdaptivePanelWidth(width, height)
        }
    }

    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.ime_voice_panel, null)
        if (::inputRoot.isInitialized) {
            inputRoot.removeOnLayoutChangeListener(inputRootLayoutChangeListener)
        }
        inputRoot = view
        inputRoot.addOnLayoutChangeListener(inputRootLayoutChangeListener)
        inputRoot.keepScreenOn = asrKeepScreenOn
        wideContentOnRight = Prefs.getBool(this, Prefs.KEY_WIDE_IME_CONTENT_ON_RIGHT, false)
        reloadExtremeHeightPreference()
        applyPanelHeightForCurrentMode(view)
        btnMic = view.findViewById(R.id.btnMic)
        btnCmd = view.findViewById(R.id.btnCmd)
        btnInstruction = view.findViewById(R.id.btnInstruction)
        btnHandwritingMode = view.findViewById(R.id.btnHandwritingMode)
        btnNumericKeypadMode = view.findViewById(R.id.btnNumericKeypadMode)
        btnExtremeSideSwap = view.findViewById(R.id.btnExtremeSideSwap)
        btnSettings = view.findViewById(R.id.btnSettings)
        textStatus = view.findViewById(R.id.textStatus)
        textPreview = view.findViewById(R.id.textPreview)
        textInstructionScope = view.findViewById(R.id.textInstructionScope)
        waveform = view.findViewById(R.id.waveform)
        pageVoice = view.findViewById(R.id.pageVoice)
        pageKeys = view.findViewById(R.id.pageKeys)
        inputModeGroup = view.findViewById(R.id.inputModeGroup)
        inputContentArea = view.findViewById(R.id.inputContentArea)
        previewContainer = view.findViewById(R.id.previewContainer)
        handwritingPanel = view.findViewById(R.id.handwritingPanel)
        numericKeypadPanel = view.findViewById(R.id.numericKeypadPanel)
        fullKeyboardPanel = view.findViewById(R.id.fullKeyboardPanel)
        handwritingPad = view.findViewById(R.id.handwritingPad)
        handwritingHint = view.findViewById(R.id.textHandwritingHint)
        handwritingCandidateScroll = view.findViewById(R.id.handwritingCandidateScroll)
        handwritingCandidates = view.findViewById(R.id.handwritingCandidates)
        handwritingCandidateButtons.clear()
        handwritingCandidates.removeAllViews()
        hideHandwritingCandidates()
        voicePurposeControls = view.findViewById(R.id.voicePurposeControls)
        voicePurposeModePill = view.findViewById(R.id.voicePurposeModePill)
        aiActionRow = view.findViewById(R.id.aiActionRow)
        topToolbar = view.findViewById(R.id.topToolbar)
        toolbarActionGroup = view.findViewById(R.id.toolbarActionGroup)
        voicePurposeButtonRow = view.findViewById(R.id.voicePurposeButtonRow)
        voiceActionArea = view.findViewById(R.id.voiceActionArea)
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
            SpeechInputMode.ENGLISH to view.findViewById(R.id.btnModeEnglish),
        )
        reloadSpeechModePreferences()

        btnMic.setSystemHapticClick { toggleSession() }
        textStatus.setSystemHapticClick { maybeOpenSettings() }

        bindRepeatingDelete(view.findViewById(R.id.btnDelete))
        bindKeyboardSwitcher(view.findViewById(R.id.btnSwitchKeyboard))
        view.findViewById<View>(R.id.btnEnter).setSystemHapticClick {
            if (handwritingEnabled) finishHandwritingComposition()
            if (fullKeyboardEnabled) {
                handleFullKeyboardEnter()
            } else {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
            }
        }
        view.findViewById<View>(R.id.btnSpace).setSystemHapticClick {
            if (handwritingEnabled) finishHandwritingComposition()
            if (fullKeyboardEnabled) {
                handleFullKeyboardSpace()
            } else {
                currentInputConnection?.let { connection ->
                    connection.finishComposingText()
                    if (connection.commitText(" ", 1)) sessionText.append(' ')
                }
            }
        }
        btnSettings.setSystemHapticClick { openSettings() }
        btnExtremeSideSwap.setSystemHapticClick { toggleWideLayoutSide() }
        btnCmd.setSystemHapticClick { toggleShortcutPage() }
        view.findViewById<View>(R.id.btnClearHandwriting).setSystemHapticClick {
            clearHandwriting(cancelComposition = true)
        }
        handwritingPad.onStrokeStarted = { onHandwritingStrokeStarted() }
        handwritingPad.onStrokeFinished = { scheduleHandwritingRecognition() }
        btnInstruction.setSystemHapticClick(HapticFeedbackConstants.CONTEXT_CLICK) {
            toggleInstructionMode()
        }
        btnHandwritingMode.setSystemHapticClick(HapticFeedbackConstants.CONTEXT_CLICK) {
            toggleHandwritingMode()
        }
        btnNumericKeypadMode.setSystemHapticClick(HapticFeedbackConstants.CONTEXT_CLICK) {
            toggleAuxiliaryKeyboardMode()
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
        bindNumericKeys(view)
        bindFullKeyboard()

        view.findViewById<TextView>(R.id.btnSummarize).setSystemHapticClick {
            runAi(AiProcessor.Mode.SUMMARIZE)
        }
        view.findViewById<TextView>(R.id.btnTranslate).setSystemHapticClick {
            runAi(AiProcessor.Mode.TRANSLATE)
        }
        view.findViewById<TextView>(R.id.btnPolish).setSystemHapticClick {
            runAi(AiProcessor.Mode.POLISH)
        }
        reloadFullKeyboardPreference()
        updateInputModeVisual()
        applyPanelPresentationForCurrentMode()
        return view
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onWindowShown() {
        super.onWindowShown()
        if (::inputRoot.isInitialized) {
            reloadExtremeHeightPreference()
            reloadFullKeyboardPreference()
            applyPanelPresentationForCurrentMode()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::inputRoot.isInitialized) {
            inputRoot.post { applyPanelPresentationForCurrentMode() }
        }
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        reloadExtremeHeightPreference()
        reloadFullKeyboardPreference()
        // 每次弹出面板时重置会话状态
        editorSessionId += 1
        resetEditEvidence()
        currentSelectionStart = info?.initialSelStart ?: -1
        currentSelectionEnd = info?.initialSelEnd ?: -1
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
        resetNumericKeypadMode()
        resetFullKeyboardMode(commitComposition = false)
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
        applyPanelPresentationForCurrentMode()
    }

    override fun onDestroy() {
        if (::inputRoot.isInitialized) {
            inputRoot.removeOnLayoutChangeListener(inputRootLayoutChangeListener)
        }
        setAsrKeepScreenOn(false)
        stopDeleteRepeat()
        flushDeleteEvidence()
        flushVoiceHistory()
        flushNumericHistory()
        flushFullKeyboardHistory()
        cancelPendingHandwritingRecognition()
        handwritingRecognizer.close()
        cancelInstructionMode(refresh = false)
        session?.destroy()
        super.onDestroy()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        stopDeleteRepeat()
        flushDeleteEvidence()
        resetHandwritingMode(commitComposition = true)
        resetNumericKeypadMode()
        resetFullKeyboardMode(commitComposition = true)
        flushVoiceHistory()
        flushNumericHistory()
        flushFullKeyboardHistory()
        editorSessionId += 1
        session?.takeIf { it.state != AsrSession.State.IDLE }?.let {
            discardAsrUntilIdle = true
            it.stop()
        }
        cancelInstructionMode(refresh = false)
        super.onFinishInputView(finishingInput)
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd
        )
        currentSelectionStart = newSelStart
        currentSelectionEnd = newSelEnd
    }

    // ---------- ⌘ 快捷键面板 ----------

    private fun toggleShortcutPage() {
        if (!shortcutPageVisible && handwritingEnabled) {
            resetHandwritingMode(commitComposition = true)
        }
        if (!shortcutPageVisible && numericKeypadEnabled) {
            resetNumericKeypadMode()
        }
        if (!shortcutPageVisible && fullKeyboardEnabled) {
            resetFullKeyboardMode(commitComposition = true)
        }
        shortcutPageVisible = !shortcutPageVisible
        applyPanelPresentationForCurrentMode()
        animateShortcutPage(shortcutPageVisible)
        btnCmd.background = getDrawable(
            if (shortcutPageVisible) R.drawable.bg_icon_button_selected else R.drawable.bg_icon_button
        )
    }

    private fun animateShortcutPage(showShortcuts: Boolean) {
        val incoming = if (showShortcuts) pageKeys else pageVoice
        val outgoing = if (showShortcuts) pageVoice else pageKeys
        val availableWidth = inputRoot.width.takeIf { it > 0 }
            ?: resources.configuration.screenWidthDp
                .takeIf { it > 0 }
                ?.let { (it * resources.displayMetrics.density).toInt() }
            ?: resources.displayMetrics.widthPixels
        val distance = availableWidth * PAGE_SLIDE_DISTANCE_RATIO

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
        if (numericKeypadEnabled) resetNumericKeypadMode()
        if (fullKeyboardEnabled) resetFullKeyboardMode(commitComposition = true)
        if (instructionState !is InstructionState.Off) cancelInstructionMode(refresh = false)
        session?.takeIf { it.state != AsrSession.State.IDLE }?.let {
            discardAsrUntilIdle = true
            it.stop()
        }
        flushVoiceHistory()
        handwritingEnabled = true
        handwritingOperationId += 1
        handwritingReadyLanguages = emptySet()
        inputModeGroup.visibility = View.GONE
        previewContainer.visibility = View.GONE
        handwritingPanel.visibility = View.VISIBLE
        textStatus.visibility = View.GONE
        waveform.visibility = View.GONE
        renderPurposeModeVisual()
        applyPanelPresentationForCurrentMode()
        showHandwritingMessage(
            getString(R.string.handwriting_preparing),
            R.color.text_hint,
        )
        prepareHandwritingModels()
    }

    private fun resetHandwritingMode(commitComposition: Boolean) {
        cancelPendingHandwritingRecognition()
        handwritingOperationId += 1
        if (commitComposition) finishHandwritingComposition() else cancelHandwritingComposition()
        handwritingEnabled = false
        handwritingReadyLanguages = emptySet()
        if (::handwritingPad.isInitialized) {
            handwritingPad.clear()
            handwritingHint.visibility = View.GONE
            hideHandwritingCandidates()
            handwritingPanel.visibility = View.GONE
            previewContainer.visibility = View.VISIBLE
            inputModeGroup.visibility = View.VISIBLE
            textStatus.visibility = View.VISIBLE
            waveform.visibility = View.VISIBLE
            renderPurposeModeVisual()
        }
        if (::inputRoot.isInitialized) applyPanelPresentationForCurrentMode()
    }

    // ---------- 数字键盘输入 ----------

    private fun toggleNumericKeypadMode() {
        if (numericKeypadEnabled) {
            resetNumericKeypadMode()
            refreshIdleHint()
            return
        }
        if (shortcutPageVisible) {
            shortcutPageVisible = false
            animateShortcutPage(showShortcuts = false)
            btnCmd.background = getDrawable(R.drawable.bg_icon_button)
        }
        if (handwritingEnabled) resetHandwritingMode(commitComposition = true)
        if (fullKeyboardEnabled) resetFullKeyboardMode(commitComposition = true)
        if (instructionState !is InstructionState.Off) cancelInstructionMode(refresh = false)
        session?.takeIf { it.state != AsrSession.State.IDLE }?.let {
            discardAsrUntilIdle = true
            it.stop()
        }
        flushVoiceHistory()
        numericKeypadEnabled = true
        numericHistoryBuffer = StringBuilder()
        inputModeGroup.visibility = View.GONE
        previewContainer.visibility = View.GONE
        handwritingPanel.visibility = View.GONE
        numericKeypadPanel.visibility = View.VISIBLE
        waveform.visibility = View.GONE
        textStatus.text = getString(R.string.numeric_keypad_hint)
        textStatus.setTextColor(getColor(R.color.text_hint))
        renderPurposeModeVisual()
        applyPanelPresentationForCurrentMode()
    }

    private fun resetNumericKeypadMode() {
        flushNumericHistory()
        numericKeypadEnabled = false
        if (::numericKeypadPanel.isInitialized) {
            numericKeypadPanel.visibility = View.GONE
            previewContainer.visibility = View.VISIBLE
            inputModeGroup.visibility = View.VISIBLE
            waveform.visibility = View.VISIBLE
            renderPurposeModeVisual()
        }
        if (::inputRoot.isInitialized) applyPanelPresentationForCurrentMode()
    }

    private fun bindNumericKeys(view: View) {
        mapOf(
            R.id.keyNumber0 to "0",
            R.id.keyNumber1 to "1",
            R.id.keyNumber2 to "2",
            R.id.keyNumber3 to "3",
            R.id.keyNumber4 to "4",
            R.id.keyNumber5 to "5",
            R.id.keyNumber6 to "6",
            R.id.keyNumber7 to "7",
            R.id.keyNumber8 to "8",
            R.id.keyNumber9 to "9",
            R.id.keyNumber00 to "00",
            R.id.keyNumberDecimal to ".",
            R.id.keyNumberMinus to "-",
            R.id.keyNumberPlus to "+",
            R.id.keyNumberColon to ":",
            R.id.keyNumberSlash to "/",
        ).forEach { (id, value) ->
            view.findViewById<View>(id).setSystemHapticClick {
                commitNumericKey(value)
            }
        }
    }

    private fun commitNumericKey(value: String) {
        if (!numericKeypadEnabled) return
        val committed = currentInputConnection?.let { connection ->
            connection.finishComposingText()
            connection.commitText(value, 1)
        } == true
        if (committed) {
            numericHistoryBuffer.append(value)
            sessionText.append(value)
            recordCommittedEdit(InputSource.NUMERIC_KEYPAD, value)
        }
    }

    private fun flushNumericHistory() {
        val text = numericHistoryBuffer.toString()
        numericHistoryBuffer = StringBuilder()
        recordInput(InputSource.NUMERIC_KEYPAD, text)
    }

    // ---------- 全键盘输入 ----------

    private fun toggleAuxiliaryKeyboardMode() {
        reloadFullKeyboardPreference()
        if (fullKeyboardPreferred) toggleFullKeyboardMode() else toggleNumericKeypadMode()
    }

    private fun toggleFullKeyboardMode() {
        if (fullKeyboardEnabled) {
            resetFullKeyboardMode(commitComposition = true)
            refreshIdleHint()
            return
        }
        if (shortcutPageVisible) {
            shortcutPageVisible = false
            animateShortcutPage(showShortcuts = false)
            btnCmd.background = getDrawable(R.drawable.bg_icon_button)
        }
        if (handwritingEnabled) resetHandwritingMode(commitComposition = true)
        if (numericKeypadEnabled) resetNumericKeypadMode()
        if (instructionState !is InstructionState.Off) cancelInstructionMode(refresh = false)
        session?.takeIf { it.state != AsrSession.State.IDLE }?.let {
            discardAsrUntilIdle = true
            it.stop()
        }
        flushVoiceHistory()
        fullKeyboardEnabled = true
        fullKeyboardHistoryBuffer = StringBuilder()
        fullKeyboardPage = FullKeyboardPage.LETTERS
        fullKeyboardUppercase = false
        pinyinComposition = ""
        inputModeGroup.visibility = View.GONE
        previewContainer.visibility = View.GONE
        handwritingPanel.visibility = View.GONE
        numericKeypadPanel.visibility = View.GONE
        fullKeyboardPanel.visibility = View.VISIBLE
        waveform.visibility = View.GONE
        textStatus.text = getString(
            if (pinyinLexiconLoadFailed) R.string.full_keyboard_dictionary_failed
            else R.string.full_keyboard_hint
        )
        textStatus.setTextColor(getColor(R.color.text_hint))
        ensurePinyinLexiconLoaded()
        renderFullKeyboard()
        renderPurposeModeVisual()
        applyPanelPresentationForCurrentMode()
    }

    private fun resetFullKeyboardMode(commitComposition: Boolean) {
        if (commitComposition) {
            if (!commitCurrentPinyin()) cancelCurrentPinyin()
        } else {
            cancelCurrentPinyin()
        }
        flushFullKeyboardHistory()
        fullKeyboardEnabled = false
        fullKeyboardPage = FullKeyboardPage.LETTERS
        fullKeyboardUppercase = false
        if (::fullKeyboardPanel.isInitialized) {
            fullKeyboardPanel.visibility = View.GONE
            previewContainer.visibility = View.VISIBLE
            inputModeGroup.visibility = View.VISIBLE
            waveform.visibility = View.VISIBLE
            renderPurposeModeVisual()
        }
        if (::inputRoot.isInitialized) applyPanelPresentationForCurrentMode()
    }

    private fun bindFullKeyboard() {
        fullKeyboardPanel.onKey = { action ->
            when (action) {
                is FullKeyboardAction.Character -> handleFullKeyboardCharacter(action.value)
                FullKeyboardAction.Shift -> {
                    fullKeyboardUppercase = !fullKeyboardUppercase
                    renderFullKeyboard()
                }
                FullKeyboardAction.Delete -> handleFullKeyboardDelete()
                FullKeyboardAction.SwitchLanguage -> switchFullKeyboardLanguage()
                FullKeyboardAction.SwitchPage -> {
                    commitCurrentPinyin()
                    fullKeyboardPage = if (fullKeyboardPage == FullKeyboardPage.LETTERS) {
                        FullKeyboardPage.NUMBERS
                    } else {
                        FullKeyboardPage.LETTERS
                    }
                    renderFullKeyboard()
                }
                FullKeyboardAction.Space -> handleFullKeyboardSpace()
                FullKeyboardAction.Enter -> handleFullKeyboardEnter()
            }
        }
        fullKeyboardPanel.onCandidate = { candidate -> commitPinyinCandidate(candidate) }
    }

    private fun handleFullKeyboardCharacter(value: String) {
        if (!fullKeyboardEnabled) return
        val character = value.singleOrNull()?.lowercaseChar()
        val isPinyinKey = fullKeyboardLanguage == FullKeyboardLanguage.CHINESE &&
            fullKeyboardPage == FullKeyboardPage.LETTERS &&
            (character != null && character in 'a'..'z' || value == "'")
        if (isPinyinKey) {
            if (value == "'" && (pinyinComposition.isEmpty() || pinyinComposition.endsWith("'"))) {
                return
            }
            pinyinComposition += value.lowercase()
            currentInputConnection?.setComposingText(pinyinComposition, 1)
            renderFullKeyboard()
            return
        }
        commitCurrentPinyin()
        commitFullKeyboardText(value)
        if (fullKeyboardLanguage == FullKeyboardLanguage.ENGLISH && fullKeyboardUppercase) {
            fullKeyboardUppercase = false
            renderFullKeyboard()
        }
    }

    private fun handleFullKeyboardSpace() {
        if (!fullKeyboardEnabled) return
        if (pinyinComposition.isNotEmpty()) commitCurrentPinyin() else commitFullKeyboardText(" ")
    }

    private fun handleFullKeyboardEnter() {
        if (!fullKeyboardEnabled) return
        commitCurrentPinyin()
        sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
    }

    private fun handleFullKeyboardDelete() {
        if (!fullKeyboardEnabled) return
        if (pinyinComposition.isNotEmpty()) {
            pinyinComposition = pinyinComposition.dropLast(1)
            if (pinyinComposition.isEmpty()) {
                currentInputConnection?.setComposingText("", 1)
                currentInputConnection?.finishComposingText()
            } else {
                currentInputConnection?.setComposingText(pinyinComposition, 1)
            }
            renderFullKeyboard()
        } else {
            deleteOnce()
        }
    }

    private fun switchFullKeyboardLanguage() {
        commitCurrentPinyin()
        fullKeyboardLanguage = if (fullKeyboardLanguage == FullKeyboardLanguage.CHINESE) {
            FullKeyboardLanguage.ENGLISH
        } else {
            FullKeyboardLanguage.CHINESE
        }
        fullKeyboardUppercase = false
        renderFullKeyboard()
    }

    private fun commitCurrentPinyin(): Boolean {
        val raw = pinyinComposition
        if (raw.isEmpty()) return false
        val candidate = pinyinLexicon?.candidates(raw, 1)?.firstOrNull()
        return commitPinyinCandidate(candidate ?: raw.replace("'", ""))
    }

    private fun commitPinyinCandidate(candidate: String): Boolean {
        if (pinyinComposition.isEmpty()) return false
        val committed = currentInputConnection?.commitText(candidate, 1) == true
        if (committed) {
            fullKeyboardHistoryBuffer.append(candidate)
            sessionText.append(candidate)
            recordCommittedEdit(InputSource.FULL_KEYBOARD, candidate)
            pinyinComposition = ""
        }
        renderFullKeyboard()
        return committed
    }

    private fun cancelCurrentPinyin() {
        if (pinyinComposition.isEmpty()) return
        currentInputConnection?.setComposingText("", 1)
        currentInputConnection?.finishComposingText()
        pinyinComposition = ""
        if (::fullKeyboardPanel.isInitialized) renderFullKeyboard()
    }

    private fun commitFullKeyboardText(value: String): Boolean {
        val committed = currentInputConnection?.let { connection ->
            connection.finishComposingText()
            connection.commitText(value, 1)
        } == true
        if (committed) {
            fullKeyboardHistoryBuffer.append(value)
            sessionText.append(value)
            recordCommittedEdit(InputSource.FULL_KEYBOARD, value)
        }
        return committed
    }

    private fun flushFullKeyboardHistory() {
        val text = fullKeyboardHistoryBuffer.toString()
        fullKeyboardHistoryBuffer = StringBuilder()
        recordInput(InputSource.FULL_KEYBOARD, text)
    }

    private fun renderFullKeyboard() {
        if (!::fullKeyboardPanel.isInitialized) return
        val candidates = if (
            fullKeyboardLanguage == FullKeyboardLanguage.CHINESE &&
            fullKeyboardPage == FullKeyboardPage.LETTERS
        ) {
            pinyinLexicon?.candidates(pinyinComposition).orEmpty()
        } else {
            emptyList()
        }
        fullKeyboardPanel.render(
            language = fullKeyboardLanguage,
            page = fullKeyboardPage,
            uppercase = fullKeyboardUppercase,
            composition = pinyinComposition,
            candidates = candidates,
        )
    }

    private fun ensurePinyinLexiconLoaded() {
        if (pinyinLexicon != null || pinyinLexiconLoadStarted || pinyinLexiconLoadFailed) return
        pinyinLexiconLoadStarted = true
        textStatus.text = getString(R.string.full_keyboard_loading_dictionary)
        Thread({
            val result = runCatching {
                resources.openRawResource(R.raw.pinyin_dictionary).use(PinyinLexicon::from)
            }
            handwritingHandler.post {
                pinyinLexiconLoadStarted = false
                result.onSuccess { pinyinLexicon = it }
                    .onFailure {
                        pinyinLexiconLoadFailed = true
                        Log.e("InkTalkKeyboard", "Failed to load pinyin dictionary", it)
                    }
                if (fullKeyboardEnabled) {
                    textStatus.text = getString(
                        if (result.isSuccess) R.string.full_keyboard_hint
                        else R.string.full_keyboard_dictionary_failed
                    )
                    renderFullKeyboard()
                }
            }
        }, "InkTalkPinyinLexicon").start()
    }

    private fun reloadFullKeyboardPreference() {
        val preferred = Prefs.getBool(this, Prefs.KEY_ENABLE_FULL_KEYBOARD, false)
        if (fullKeyboardPreferred != preferred) {
            if (numericKeypadEnabled) resetNumericKeypadMode()
            if (fullKeyboardEnabled) resetFullKeyboardMode(commitComposition = true)
            fullKeyboardPreferred = preferred
            if (::textStatus.isInitialized) refreshIdleHint()
        }
        if (!::btnNumericKeypadMode.isInitialized) return
        btnNumericKeypadMode.setImageResource(
            if (fullKeyboardPreferred) R.drawable.ic_material_keyboard_24
            else R.drawable.ic_material_dialpad_24
        )
        renderPurposeModeVisual()
    }

    private fun prepareHandwritingModels() {
        val languages = HandwritingLanguage.entries.toList()
        val requestId = ++handwritingOperationId
        val completedLanguages = mutableSetOf<HandwritingLanguage>()
        val readyLanguages = mutableSetOf<HandwritingLanguage>()
        val errors = mutableListOf<String>()
        handwritingReadyLanguages = emptySet()
        showHandwritingMessage(
            getString(R.string.handwriting_preparing),
            R.color.text_hint,
        )

        fun complete(language: HandwritingLanguage, error: String?) {
            handwritingHandler.post {
                if (!isCurrentHandwritingRequest(requestId)) return@post
                if (!completedLanguages.add(language)) return@post
                if (error == null) readyLanguages += language else errors += error
                if (completedLanguages.size != languages.size) return@post

                handwritingReadyLanguages = readyLanguages.toSet()
                if (readyLanguages.isEmpty()) {
                    showHandwritingMessage(
                        getString(
                            R.string.handwriting_error,
                            errors.firstOrNull().orEmpty(),
                        ),
                        R.color.error_red,
                    )
                } else {
                    refreshHandwritingIdleState()
                    if (handwritingPad.hasInk()) scheduleHandwritingRecognition()
                }
            }
        }

        languages.forEach { language ->
            handwritingRecognizer.prepare(language, object : HandwritingRecognizer.Callback {
                override fun onModelReady() = complete(language, error = null)

                override fun onCandidates(candidates: List<String>) = Unit

                override fun onError(message: String) = complete(language, error = message)
            })
        }
    }

    private fun refreshHandwritingIdleState() {
        if (!handwritingEnabled) return
        if (handwritingReadyLanguages.isEmpty()) {
            showHandwritingMessage(
                getString(R.string.handwriting_preparing),
                R.color.text_hint,
            )
        } else {
            val message = if (
                handwritingReadyLanguages.size == HandwritingLanguage.entries.size
            ) {
                getString(R.string.handwriting_ready)
            } else {
                getString(R.string.handwriting_partial_ready)
            }
            showHandwritingMessage(
                message,
                R.color.text_hint,
                visible = !handwritingPad.hasInk(),
            )
        }
    }

    private fun showHandwritingMessage(
        message: String,
        colorRes: Int,
        visible: Boolean = true,
    ) {
        handwritingHint.text = message
        handwritingHint.setTextColor(getColor(colorRes))
        handwritingHint.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun onHandwritingStrokeStarted() {
        cancelPendingHandwritingRecognition()
        handwritingOperationId += 1
        handwritingHint.visibility = View.GONE
        if (handwritingComposingText.isNotEmpty()) {
            finishHandwritingComposition()
            handwritingPad.clear()
            hideHandwritingCandidates()
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
        val languages = handwritingReadyLanguages.toList()
        if (languages.isEmpty()) {
            showHandwritingMessage(
                getString(R.string.handwriting_preparing),
                R.color.text_hint,
            )
            prepareHandwritingModels()
            return
        }
        val strokes = handwritingPad.snapshot()
        val requestId = ++handwritingOperationId
        showHandwritingMessage(
            getString(R.string.handwriting_recognizing),
            R.color.text_hint,
        )
        val preContext = currentInputConnection
            ?.getTextBeforeCursor(HANDWRITING_PRE_CONTEXT_CHARS, 0)
            ?.toString()
            .orEmpty()
        val completedLanguages = mutableSetOf<HandwritingLanguage>()
        val candidatesByLanguage = mutableMapOf<HandwritingLanguage, List<String>>()
        val errors = mutableListOf<String>()

        fun complete(
            language: HandwritingLanguage,
            candidates: List<String>,
            error: String?,
        ) {
            handwritingHandler.post {
                if (!isCurrentHandwritingRequest(requestId)) return@post
                if (!completedLanguages.add(language)) return@post
                candidatesByLanguage[language] = candidates
                if (error != null) errors += error
                if (completedLanguages.size != languages.size) return@post

                val mergedCandidates = HandwritingCandidateMerger.merge(
                    candidatesByLanguage = candidatesByLanguage,
                    preContext = preContext,
                    limit = HANDWRITING_CANDIDATE_LIMIT,
                )
                if (mergedCandidates.isEmpty()) {
                    val message = if (errors.size == languages.size) {
                        getString(R.string.handwriting_error, errors.firstOrNull().orEmpty())
                    } else {
                        getString(R.string.handwriting_no_result)
                    }
                    showHandwritingMessage(message, R.color.error_red)
                    showHandwritingCandidates(emptyList())
                } else {
                    setHandwritingComposition(mergedCandidates.first())
                    showHandwritingCandidates(mergedCandidates)
                    handwritingHint.visibility = View.GONE
                }
            }
        }

        languages.forEach { language ->
            handwritingRecognizer.recognize(
                strokes = strokes,
                width = handwritingPad.width.toFloat(),
                height = handwritingPad.height.toFloat(),
                language = language,
                preContext = preContext,
                callback = object : HandwritingRecognizer.Callback {
                    override fun onModelReady() = Unit

                    override fun onCandidates(candidates: List<String>) =
                        complete(language, candidates, error = null)

                    override fun onError(message: String) =
                        complete(language, candidates = emptyList(), error = message)
                },
            )
        }
    }

    private fun showHandwritingCandidates(candidates: List<String>) {
        ensureHandwritingCandidateButtons()
        val displayedCandidates = candidates.take(HANDWRITING_CANDIDATE_LIMIT)
        handwritingCandidateButtons.forEachIndexed { index, button ->
            val candidate = displayedCandidates.getOrNull(index)
            if (candidate == null) {
                button.text = ""
                button.contentDescription = null
                button.visibility = View.GONE
            } else {
                button.text = candidate
                button.setBackgroundResource(
                    if (index == 0) R.drawable.bg_mode_selected
                    else R.drawable.bg_action_chip
                )
                button.contentDescription = getString(R.string.handwriting_candidate, candidate)
                button.visibility = View.VISIBLE
            }
        }

        if (displayedCandidates.isEmpty()) {
            handwritingCandidateScroll.scrollTo(0, 0)
            handwritingCandidateScroll.importantForAccessibility =
                View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            // 保留 40dp 候选槽，避免手写板在落笔期间重新布局。
            handwritingCandidateScroll.visibility = View.INVISIBLE
        } else {
            handwritingCandidateScroll.importantForAccessibility =
                View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            handwritingCandidateScroll.visibility = View.VISIBLE
            handwritingCandidateScroll.post { handwritingCandidateScroll.scrollTo(0, 0) }
        }
    }

    private fun ensureHandwritingCandidateButtons() {
        if (handwritingCandidateButtons.isNotEmpty()) return
        val density = resources.displayMetrics.density
        val margin = (3 * density).toInt()
        repeat(HANDWRITING_CANDIDATE_LIMIT) {
            val button = TextView(this).apply {
                textSize = 16f
                gravity = Gravity.CENTER
                minWidth = (52 * density).toInt()
                setPadding((14 * density).toInt(), 0, (14 * density).toInt(), 0)
                setTextColor(getColor(R.color.text_primary))
                visibility = View.GONE
                setSystemHapticClick { candidateView ->
                    val candidate = (candidateView as TextView).text.toString()
                    if (candidate.isNotEmpty()) {
                        setHandwritingComposition(candidate)
                        finishHandwritingComposition()
                        clearHandwriting(cancelComposition = false)
                    }
                }
            }
            handwritingCandidateButtons += button
            handwritingCandidates.addView(
                button,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                ).apply { setMargins(margin, 2, margin, 2) },
            )
        }
    }

    private fun hideHandwritingCandidates() = showHandwritingCandidates(emptyList())

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
        recordCommittedEdit(InputSource.HANDWRITING, text)
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
        hideHandwritingCandidates()
        refreshHandwritingIdleState()
    }

    private fun cancelPendingHandwritingRecognition() {
        pendingHandwritingRecognition?.let { handwritingHandler.removeCallbacks(it) }
        pendingHandwritingRecognition = null
    }

    private fun isCurrentHandwritingRequest(requestId: Long): Boolean =
        handwritingEnabled && handwritingOperationId == requestId

    private fun applyPanelHeightForCurrentMode(root: View) {
        val configurationHeight = resources.configuration.screenHeightDp
            .takeIf { it > 0 }
            ?.let { (it * resources.displayMetrics.density).toInt() }
        val expandedInput = handwritingEnabled || numericKeypadEnabled || fullKeyboardEnabled
        val height = InputPanelSizing.heightPx(
            screenHeightPx = configurationHeight ?: resources.displayMetrics.heightPixels,
            density = resources.displayMetrics.density,
            desiredHeightDp = InputPanelSizing.desiredHeightDp(
                expandedInput = expandedInput,
                extremeHeightMode = extremeHeightMode,
                shortcutPageVisible = shortcutPageVisible,
            ),
            maximumScreenFraction = PANEL_MAXIMUM_SCREEN_FRACTION,
        )
        val params = root.layoutParams ?: ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            height,
        )
        params.width = ViewGroup.LayoutParams.MATCH_PARENT
        params.height = height
        root.layoutParams = params
        root.requestLayout()
        window?.window?.decorView?.requestLayout()
    }

    private fun reloadExtremeHeightPreference() {
        extremeHeightMode = Prefs.getBool(this, Prefs.KEY_EXTREME_HEIGHT_MODE, false)
    }

    private fun applyPanelPresentationForCurrentMode() {
        if (!::inputRoot.isInitialized || !::btnSettings.isInitialized) return
        val expandedInput = handwritingEnabled || numericKeypadEnabled || fullKeyboardEnabled
        val extremeVoicePresentation = extremeHeightMode &&
            !expandedInput && !shortcutPageVisible

        if (extremeVoicePresentation) {
            applyExtremeModeButtonPlacement(resolveAvailablePanelWidthPx())
            inputModeGroup.visibility = View.GONE
            textInstructionScope.visibility = View.GONE
            inputContentArea.visibility = View.GONE
            textStatus.visibility = View.GONE
            waveform.visibility = View.GONE
            voicePurposeControls.visibility = View.VISIBLE
            instructionReviewActions.visibility = View.GONE
            btnSettings.visibility = View.VISIBLE
            btnMic.visibility = View.VISIBLE
        } else {
            placeExtremeModeButtonsInToolbar(inToolbar = false)
            voicePurposeModePill.visibility = View.VISIBLE
            btnSettings.visibility = View.VISIBLE
            inputContentArea.visibility = View.VISIBLE
            if (!expandedInput) {
                inputModeGroup.visibility = View.VISIBLE
                previewContainer.visibility = View.VISIBLE
                textStatus.visibility = View.VISIBLE
                renderInstructionState()
            } else {
                voicePurposeControls.visibility = View.VISIBLE
                instructionReviewActions.visibility = View.GONE
                btnMic.visibility = View.VISIBLE
            }
        }

        applyPanelHeightForCurrentMode(inputRoot)
        inputRoot.post { applyAdaptivePanelWidth() }
    }

    private fun resolveAvailablePanelWidthPx(): Int {
        val density = resources.displayMetrics.density
        return inputRoot.width.takeIf { it > 0 }
            ?: resources.configuration.screenWidthDp
                .takeIf { it > 0 }
                ?.let { (it * density).toInt() }
            ?: resources.displayMetrics.widthPixels
    }

    private fun applyExtremeModeButtonPlacement(availableWidthPx: Int) {
        val inToolbar = InputPanelSizing.canPlaceExtremeModeButtonsInToolbar(
            availableWidthPx = availableWidthPx,
            density = resources.displayMetrics.density,
            aiActionsWidthPx = resolveAiActionRowWidthPx(),
        )
        placeExtremeModeButtonsInToolbar(inToolbar)
        voicePurposeModePill.visibility = if (inToolbar) View.GONE else View.VISIBLE
    }

    private fun resolveAiActionRowWidthPx(): Int {
        aiActionRow.measuredWidth.takeIf { it > 0 }?.let { return it }
        aiActionRow.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        return aiActionRow.measuredWidth
    }

    private fun toggleWideLayoutSide() {
        wideContentOnRight = !wideContentOnRight
        Prefs.putBool(
            this,
            Prefs.KEY_WIDE_IME_CONTENT_ON_RIGHT,
            wideContentOnRight,
        )
        appliedVoiceLayoutKey = null
        applyAdaptivePanelWidth()
    }

    private fun applyExtremeWideSingleHandLayout(
        availableWidthPx: Int,
        density: Float,
    ) {
        val controlsOnRight = !wideContentOnRight
        placeExtremeModeButtonsInToolbar(inToolbar = true)
        voicePurposeModePill.visibility = View.GONE

        val toolbarParams = (topToolbar.layoutParams as? LinearLayout.LayoutParams)
            ?: LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (48 * density).toInt())
        toolbarParams.width = InputPanelSizing.extremeWideToolbarWidthPx(
            availableWidthPx = availableWidthPx,
            density = density,
        )
        toolbarParams.gravity = if (controlsOnRight) Gravity.END else Gravity.START
        topToolbar.layoutParams = toolbarParams

        btnMic.translationX = InputPanelSizing.extremeWideControlTranslationPx(
            availableWidthPx = availableWidthPx,
            controlsOnRight = controlsOnRight,
        )

        val edgeMargin = (12 * density).toInt()
        val settingsParams = (btnSettings.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams((44 * density).toInt(), (44 * density).toInt())
        settingsParams.gravity = (if (controlsOnRight) Gravity.END else Gravity.START) or
            Gravity.CENTER_VERTICAL
        settingsParams.marginStart = if (controlsOnRight) 0 else edgeMargin
        settingsParams.marginEnd = if (controlsOnRight) edgeMargin else 0
        btnSettings.layoutParams = settingsParams

        btnExtremeSideSwap.visibility = View.VISIBLE
        btnExtremeSideSwap.translationX = InputPanelSizing.extremeWideSwapTranslationPx(
            density = density,
            controlsOnRight = controlsOnRight,
        )
        btnExtremeSideSwap.contentDescription = getString(
            if (controlsOnRight) R.string.a11y_extreme_controls_move_left
            else R.string.a11y_extreme_controls_move_right
        )
    }

    private fun restoreStandardExtremeLayout(density: Float) {
        val toolbarParams = (topToolbar.layoutParams as? LinearLayout.LayoutParams)
            ?: LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (48 * density).toInt())
        toolbarParams.width = ViewGroup.LayoutParams.MATCH_PARENT
        toolbarParams.gravity = Gravity.NO_GRAVITY
        topToolbar.layoutParams = toolbarParams

        btnMic.translationX = 0f
        btnExtremeSideSwap.visibility = View.GONE
        btnExtremeSideSwap.translationX = 0f

        val settingsParams = (btnSettings.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams((44 * density).toInt(), (44 * density).toInt())
        settingsParams.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        settingsParams.marginStart = 0
        settingsParams.marginEnd = (12 * density).toInt()
        btnSettings.layoutParams = settingsParams
    }

    private fun placeExtremeModeButtonsInToolbar(inToolbar: Boolean) {
        if (!::toolbarActionGroup.isInitialized || !::voicePurposeButtonRow.isInitialized) return
        val target = if (inToolbar) toolbarActionGroup else voicePurposeButtonRow
        if (btnHandwritingMode.parent === target && btnNumericKeypadMode.parent === target) return

        detachFromParent(btnHandwritingMode)
        detachFromParent(btnNumericKeypadMode)
        val density = resources.displayMetrics.density
        val size = ((if (inToolbar) 34 else 40) * density).toInt()
        val padding = ((if (inToolbar) 7 else 9) * density).toInt()
        listOf(btnHandwritingMode, btnNumericKeypadMode).forEach { button ->
            button.setPadding(padding, padding, padding, padding)
            if (inToolbar) {
                button.setBackgroundResource(R.drawable.bg_icon_button)
            } else {
                button.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
        }
        if (inToolbar) {
            toolbarActionGroup.addView(
                btnHandwritingMode,
                0,
                LinearLayout.LayoutParams(size, size),
            )
            toolbarActionGroup.addView(
                btnNumericKeypadMode,
                1,
                LinearLayout.LayoutParams(size, size),
            )
        } else {
            voicePurposeButtonRow.addView(
                btnHandwritingMode,
                1,
                LinearLayout.LayoutParams(size, size),
            )
            voicePurposeButtonRow.addView(
                btnNumericKeypadMode,
                2,
                LinearLayout.LayoutParams(size, size),
            )
        }
    }

    private fun applyAdaptivePanelWidth(
        measuredWidth: Int = inputRoot.width,
        measuredHeight: Int = inputRoot.height,
    ) {
        if (!::inputRoot.isInitialized || !::pageVoice.isInitialized || !::pageKeys.isInitialized) return
        val density = resources.displayMetrics.density
        val availableWidth = measuredWidth.takeIf { it > 0 }
            ?: resources.configuration.screenWidthDp
                .takeIf { it > 0 }
                ?.let { (it * density).toInt() }
            ?: resources.displayMetrics.widthPixels
        val availableHeight = measuredHeight.takeIf { it > 0 }
            ?: resources.configuration.screenHeightDp
            .takeIf { it > 0 }
            ?.let { (it * density).toInt() }
            ?: resources.displayMetrics.heightPixels
        val profile = AdaptiveWindowProfile.fromPixels(availableWidth, availableHeight, density)

        val extremeVoicePresentation = extremeHeightMode &&
            !handwritingEnabled && !numericKeypadEnabled && !fullKeyboardEnabled &&
            !shortcutPageVisible
        val useExtremeWideSingleHandLayout =
            InputPanelSizing.usesExtremeWideSingleHandLayout(
                isWideWindow = profile.isWide,
                isLandscape = resources.configuration.orientation ==
                    Configuration.ORIENTATION_LANDSCAPE,
            )
        when {
            extremeVoicePresentation && useExtremeWideSingleHandLayout ->
                applyExtremeWideSingleHandLayout(availableWidth, density)
            extremeVoicePresentation && profile.isWide -> {
                restoreStandardExtremeLayout(density)
                placeExtremeModeButtonsInToolbar(inToolbar = false)
                voicePurposeModePill.visibility = View.VISIBLE
            }
            extremeVoicePresentation -> {
                restoreStandardExtremeLayout(density)
                applyExtremeModeButtonPlacement(availableWidth)
            }
            else -> restoreStandardExtremeLayout(density)
        }
        applyAdaptiveVoiceLayout(
            wide = profile.isWide && !extremeVoicePresentation,
            density = density,
            controlColumnWidthDp = profile.imeControlColumnWidthDp,
        )
        constrainPanelPage(
            pageVoice,
            availableWidth,
            if (extremeVoicePresentation && useExtremeWideSingleHandLayout) profile.widthDp
            else profile.imePrimaryContentMaxWidthDp,
            density,
        )
        constrainPanelPage(pageKeys, availableWidth, profile.imeShortcutContentMaxWidthDp, density)
    }

    private fun applyAdaptiveVoiceLayout(
        wide: Boolean,
        density: Float,
        controlColumnWidthDp: Int,
    ) {
        val layoutKey = "$wide:$controlColumnWidthDp:$wideContentOnRight"
        if (appliedVoiceLayoutKey == layoutKey) return
        if (appliedVoiceLayoutKey == null && !wide) {
            appliedVoiceLayoutKey = layoutKey
            return
        }

        pageVoice.removeAllViews()
        listOf(
            inputModeGroup,
            textInstructionScope,
            inputContentArea,
            textStatus,
            waveform,
            voiceActionArea,
        ).forEach(::detachFromParent)

        if (wide) {
            pageVoice.orientation = LinearLayout.HORIZONTAL
            pageVoice.gravity = Gravity.CENTER
            val outerPadding = (8 * density).toInt()
            pageVoice.setPadding(outerPadding, outerPadding, outerPadding, outerPadding)

            inputContentArea.setBackgroundResource(R.drawable.bg_settings_card)
            val controlColumn = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(
                    0,
                    (4 * density).toInt(),
                    0,
                    (4 * density).toInt(),
                )
                setBackgroundResource(R.drawable.bg_settings_card)
            }
            val modeRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            modeRow.addView(
                inputModeGroup,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    (38 * density).toInt(),
                ),
            )
            modeRow.addView(
                ImageButton(this).apply {
                    setImageResource(R.drawable.ic_material_swap_24)
                    imageTintList = ColorStateList.valueOf(getColor(R.color.icon_primary))
                    background = getDrawable(R.drawable.bg_icon_button)
                    scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                    val padding = (8 * density).toInt()
                    setPadding(padding, padding, padding, padding)
                    contentDescription = getString(
                        if (wideContentOnRight) R.string.a11y_wide_ime_move_content_left
                        else R.string.a11y_wide_ime_move_content_right
                    )
                    setSystemHapticClick { toggleWideLayoutSide() }
                },
                LinearLayout.LayoutParams(
                    (38 * density).toInt(),
                    (38 * density).toInt(),
                ).apply {
                    marginStart = (8 * density).toInt()
                },
            )
            controlColumn.addView(
                modeRow,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    (38 * density).toInt(),
                ).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = (2 * density).toInt()
                },
            )
            controlColumn.addView(
                textInstructionScope,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    (24 * density).toInt(),
                ).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = (3 * density).toInt()
                },
            )
            controlColumn.addView(
                View(this),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
            )
            controlColumn.addView(
                textStatus,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (24 * density).toInt(),
                ),
            )
            controlColumn.addView(
                waveform,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (18 * density).toInt(),
                ).apply {
                    marginStart = (24 * density).toInt()
                    marginEnd = (24 * density).toInt()
                },
            )
            controlColumn.addView(
                voiceActionArea,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (78 * density).toInt(),
                ),
            )
            val gap = (10 * density).toInt()
            val contentParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                1f,
            )
            val controlParams = LinearLayout.LayoutParams(
                (controlColumnWidthDp * density).toInt(),
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            if (wideContentOnRight) {
                controlParams.marginEnd = gap
                pageVoice.addView(controlColumn, controlParams)
                pageVoice.addView(inputContentArea, contentParams)
            } else {
                contentParams.marginEnd = gap
                pageVoice.addView(inputContentArea, contentParams)
                pageVoice.addView(controlColumn, controlParams)
            }
        } else {
            pageVoice.orientation = LinearLayout.VERTICAL
            pageVoice.gravity = Gravity.NO_GRAVITY
            pageVoice.setPadding(0, 0, 0, 0)
            inputContentArea.setBackgroundResource(0)

            pageVoice.addView(
                inputModeGroup,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    (38 * density).toInt(),
                ).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = (6 * density).toInt()
                },
            )
            pageVoice.addView(
                textInstructionScope,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    (24 * density).toInt(),
                ).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = (3 * density).toInt()
                },
            )
            pageVoice.addView(
                inputContentArea,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
            )
            pageVoice.addView(
                textStatus,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (24 * density).toInt(),
                ),
            )
            pageVoice.addView(
                waveform,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (18 * density).toInt(),
                ).apply {
                    marginStart = (48 * density).toInt()
                    marginEnd = (48 * density).toInt()
                },
            )
            pageVoice.addView(
                voiceActionArea,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (78 * density).toInt(),
                ),
            )
        }
        appliedVoiceLayoutKey = layoutKey
    }

    private fun detachFromParent(view: View) {
        (view.parent as? ViewGroup)?.removeView(view)
    }

    private fun constrainPanelPage(
        page: View,
        availableWidth: Int,
        maximumWidthDp: Int,
        density: Float,
    ) {
        val targetWidth = AdaptiveWindowProfile(widthDp = maximumWidthDp, heightDp = 1)
            .contentWidthPx(availableWidth, density, maximumWidthDp)
        val params = (page.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(targetWidth, ViewGroup.LayoutParams.MATCH_PARENT)
        if (params.width == targetWidth && params.gravity == Gravity.CENTER_HORIZONTAL) return
        params.width = targetWidth
        params.gravity = Gravity.CENTER_HORIZONTAL
        page.layoutParams = params
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
        if (fullKeyboardEnabled && pinyinComposition.isNotEmpty()) {
            handleFullKeyboardDelete()
            return
        }
        if (handwritingEnabled &&
            (handwritingPad.hasInk() || handwritingComposingText.isNotEmpty())
        ) {
            clearHandwriting(cancelComposition = true)
            return
        }
        val connection = currentInputConnection
        val sessionId = editorSessionId
        val sensitive = InstructionContextResolver.isSensitiveInputType(
            currentInputEditorInfo?.inputType ?: android.text.InputType.TYPE_NULL
        )
        val selected = if (sensitive) null else connection?.getSelectedText(0)?.toString()
        val before = if (sensitive || !selected.isNullOrEmpty()) null else
            connection?.getTextBeforeCursor(DELETE_CONTEXT_CHARS, 0)?.toString()
        val deleted = selected?.takeIf { it.isNotEmpty() }
            ?: before?.let { HotwordSelection.units(it).lastOrNull()?.text }
        val cursorBefore = currentSelectionStart.takeIf { it >= 0 }
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        if (!deleted.isNullOrEmpty() && connection != null) {
            deleteRepeatHandler.postDelayed({
                if (sessionId != editorSessionId) return@postDelayed
                val verified = if (!selected.isNullOrEmpty()) {
                    connection.getSelectedText(0)?.toString() != selected
                } else {
                    val after = connection.getTextBeforeCursor(DELETE_CONTEXT_CHARS - 1, 0)?.toString()
                    val expected = before.orEmpty().dropLast(deleted.length).takeLast(DELETE_CONTEXT_CHARS - 1)
                    after == expected
                }
                recordDeletePiece(deleted, verified, cursorBefore)
            }, DELETE_VERIFY_DELAY_MS)
        }
    }

    private fun stopDeleteRepeat() {
        deleteRepeatActive = false
        deleteRepeatHandler.removeCallbacks(deleteRepeatAction)
        deleteButton?.isPressed = false
    }

    // ---------- 会话控制 ----------

    private fun toggleSession() {
        if (handwritingEnabled) resetHandwritingMode(commitComposition = true)
        if (numericKeypadEnabled) resetNumericKeypadMode()
        if (fullKeyboardEnabled) resetFullKeyboardMode(commitComposition = true)
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
                    if (extremeHeightMode) toast(textStatus.text.toString())
                    return
                }
                if (!Prefs.hasAsrCredentials(this)) {
                    textStatus.text = getString(R.string.hint_no_credentials)
                    textStatus.setTextColor(getColor(R.color.error_red))
                    if (extremeHeightMode) toast(textStatus.text.toString())
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
        val storedValue = Prefs.get(
            this,
            Prefs.KEY_INPUT_MODE,
            SpeechInputMode.CHINESE.preferenceValue,
        )
        val storedMode = SpeechInputMode.fromPreference(storedValue)
        inputMode = storedMode.normalizedFor(englishRecognitionStrategy)
        if (storedValue != inputMode.preferenceValue) {
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
        if (numericKeypadEnabled) resetNumericKeypadMode()
        if (fullKeyboardEnabled) resetFullKeyboardMode(commitComposition = true)
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
        val auxiliaryKeyboardEnabled = numericKeypadEnabled || fullKeyboardEnabled
        val specialModeActive = instructionActive || handwritingEnabled || auxiliaryKeyboardEnabled
        btnInstruction.isSelected = instructionActive
        btnHandwritingMode.isSelected = handwritingEnabled
        btnNumericKeypadMode.isSelected = auxiliaryKeyboardEnabled
        voicePurposeThumb.animate().cancel()
        voicePurposeThumb.visibility = if (specialModeActive) View.VISIBLE else View.INVISIBLE
        if (specialModeActive) {
            val selectedIndex = when {
                auxiliaryKeyboardEnabled -> 2
                handwritingEnabled -> 1
                else -> 0
            }
            voicePurposeThumb.animate()
                .translationX(purposeThumbTranslationPx(selectedIndex))
                .setDuration(PURPOSE_SLIDE_DURATION_MS)
                .start()
        } else {
            voicePurposeThumb.translationX = 0f
        }
        btnHandwritingMode.contentDescription = getString(
            if (handwritingEnabled) R.string.a11y_disable_handwriting
            else R.string.a11y_enable_handwriting
        )
        btnNumericKeypadMode.contentDescription = getString(when {
            fullKeyboardPreferred && fullKeyboardEnabled -> R.string.a11y_disable_full_keyboard
            fullKeyboardPreferred -> R.string.a11y_enable_full_keyboard
            numericKeypadEnabled -> R.string.a11y_disable_numeric_keypad
            else -> R.string.a11y_enable_numeric_keypad
        })
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
        if (handwritingEnabled || numericKeypadEnabled || fullKeyboardEnabled) {
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
        if (committedToEditor) {
            voiceHistoryBuffer.append(text)
            recordCommittedEdit(InputSource.VOICE, text)
        }
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

    private fun recordDeletePiece(text: String, verified: Boolean, cursorBefore: Int?) {
        if (deleteBatchSessionId != editorSessionId) {
            flushDeleteEvidence()
            deleteBatchSessionId = editorSessionId
            deleteBatchCursorBefore = cursorBefore
        }
        deleteBatchText = text + deleteBatchText
        deleteBatchVerified = deleteBatchVerified && verified
        deleteRepeatHandler.removeCallbacks(flushDeleteEvidenceAction)
        deleteRepeatHandler.postDelayed(flushDeleteEvidenceAction, DELETE_BATCH_IDLE_MS)
    }

    private fun flushDeleteEvidence(): DeleteEvidence? {
        deleteRepeatHandler.removeCallbacks(flushDeleteEvidenceAction)
        val deleted = deleteBatchText
        if (deleted.isEmpty()) return lastDeleteEvidence
        val createdAt = System.currentTimeMillis()
        val eventId = try {
            inputHistoryStore.appendEditEvent(
                editorSessionId = deleteBatchSessionId,
                eventType = "delete",
                verified = deleteBatchVerified,
                deletedText = deleted,
                cursorBefore = deleteBatchCursorBefore,
                cursorAfter = currentSelectionStart.takeIf { it >= 0 },
                createdAt = createdAt,
            )
        } catch (error: RuntimeException) {
            Log.e("InkTalkHistory", "Failed to append delete evidence", error)
            -1L
        }
        lastDeleteEvidence = if (deleteBatchVerified && eventId >= 0) {
            DeleteEvidence(eventId, deleteBatchSessionId, deleted, createdAt)
        } else null
        deleteBatchText = ""
        deleteBatchVerified = true
        deleteBatchSessionId = -1L
        deleteBatchCursorBefore = null
        return lastDeleteEvidence
    }

    private fun recordCommittedEdit(source: InputSource, text: String) {
        if (text.isBlank()) return
        val inputType = currentInputEditorInfo?.inputType ?: android.text.InputType.TYPE_NULL
        if (InstructionContextResolver.isSensitiveInputType(inputType)) return
        val evidence = flushDeleteEvidence()
        val createdAt = System.currentTimeMillis()
        val insertEventId = try {
            inputHistoryStore.appendEditEvent(
                editorSessionId = editorSessionId,
                eventType = "commit",
                verified = true,
                insertedText = text,
                source = source,
                cursorAfter = currentSelectionStart.takeIf { it >= 0 },
                createdAt = createdAt,
            )
        } catch (error: RuntimeException) {
            Log.e("InkTalkHistory", "Failed to append commit evidence", error)
            -1L
        }
        if (evidence != null && evidence.sessionId == editorSessionId &&
            createdAt - evidence.createdAt <= CORRECTION_PAIR_WINDOW_MS
        ) {
            CorrectionCandidateDetector.detect(evidence.deletedText, text)?.let { correction ->
                try {
                    val date = Instant.ofEpochMilli(createdAt).atZone(ZoneId.systemDefault()).toLocalDate()
                    inputHistoryStore.appendCorrectionCandidate(
                        date,
                        correction,
                        if (insertEventId >= 0) insertEventId else evidence.eventId,
                        createdAt,
                    )
                } catch (error: RuntimeException) {
                    Log.e("InkTalkHistory", "Failed to append correction candidate", error)
                }
            }
        }
        lastDeleteEvidence = null
    }

    private fun resetEditEvidence() {
        deleteRepeatHandler.removeCallbacks(flushDeleteEvidenceAction)
        deleteBatchText = ""
        deleteBatchVerified = true
        deleteBatchSessionId = -1L
        deleteBatchCursorBefore = null
        lastDeleteEvidence = null
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
        private const val DELETE_VERIFY_DELAY_MS = 45L
        private const val DELETE_BATCH_IDLE_MS = 260L
        private const val DELETE_CONTEXT_CHARS = 65
        private const val CORRECTION_PAIR_WINDOW_MS = 10_000L
        private const val HANDWRITING_IDLE_DELAY_MS = 650L
        private const val HANDWRITING_PRE_CONTEXT_CHARS = 20
        private const val HANDWRITING_CANDIDATE_LIMIT = 5
        private const val PANEL_MAXIMUM_SCREEN_FRACTION = 0.5f
        private const val INSTRUCTION_PREVIEW_LIMIT = 36
        private const val PURPOSE_THUMB_TRANSLATION_DP = 40f
        private const val PURPOSE_SLIDE_DURATION_MS = 180L
        private const val PAGE_SLIDE_DURATION_MS = 180L
        private const val PAGE_SLIDE_DISTANCE_RATIO = 0.16f
    }

    private fun purposeThumbTranslationPx(index: Int): Float =
        PURPOSE_THUMB_TRANSLATION_DP * index * resources.displayMetrics.density

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

    private data class DeleteEvidence(
        val eventId: Long,
        val sessionId: Long,
        val deletedText: String,
        val createdAt: Long,
    )
}
