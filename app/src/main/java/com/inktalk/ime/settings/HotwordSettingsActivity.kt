package com.inktalk.ime.settings

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.inktalk.ime.R
import com.inktalk.ime.asr.HotwordCatalog

/** 独立热词编辑页。 */
class HotwordSettingsActivity : Activity() {
    private lateinit var editor: EditText
    private lateinit var countView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hotword_settings)

        editor = findViewById(R.id.editHotwordList)
        countView = findViewById(R.id.textHotwordCount)

        findViewById<View>(R.id.btnHotwordsBack).setSystemHapticClick { finish() }
        findViewById<View>(R.id.btnRestoreHotwords).setSystemHapticClick {
            editor.setText(HotwordCatalog.defaultEditorText)
            editor.setSelection(editor.text.length)
        }
        findViewById<View>(R.id.btnSaveHotwords).setSystemHapticClick {
            val count = Prefs.putHotwords(this, editor.text.toString())
            setResult(RESULT_OK)
            Toast.makeText(
                this,
                getString(R.string.hotwords_saved_count, count),
                Toast.LENGTH_SHORT,
            ).show()
            finish()
        }

        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateCount(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        editor.setText(HotwordCatalog.toEditorText(Prefs.hotwords(this)))
        editor.setSelection(0)
        updateCount(editor.text.toString())
    }

    private fun updateCount(raw: String) {
        countView.text = getString(R.string.hotwords_count, HotwordCatalog.parse(raw).size)
    }

    private fun View.setSystemHapticClick(action: () -> Unit) {
        setOnClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            action()
        }
    }
}
