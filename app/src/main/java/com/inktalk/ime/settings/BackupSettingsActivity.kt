package com.inktalk.ime.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import com.inktalk.ime.R
import java.io.ByteArrayOutputStream
import java.time.LocalDate

/** 独立配置导入与导出页。 */
class BackupSettingsActivity : Activity() {
    private lateinit var statusView: TextView
    private lateinit var scrollView: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_backup_settings)

        statusView = findViewById(R.id.textBackupStatus)
        scrollView = findViewById(R.id.backupScroll)

        findViewById<View>(R.id.btnBackupBack).setSystemHapticClick { finish() }
        findViewById<Button>(R.id.btnExportSettings).setSystemHapticClick {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_TITLE, "InkTalk-settings-${LocalDate.now()}.json")
            }
            startActivityForResult(intent, REQ_EXPORT_SETTINGS)
        }
        findViewById<Button>(R.id.btnImportSettings).setSystemHapticClick {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
            }
            startActivityForResult(intent, REQ_IMPORT_SETTINGS)
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQ_EXPORT_SETTINGS -> exportSettingsTo(uri)
            REQ_IMPORT_SETTINGS -> importSettingsFrom(uri)
        }
    }

    private fun exportSettingsTo(uri: Uri) {
        try {
            val output = contentResolver.openOutputStream(uri, "wt")
                ?: throw IllegalStateException("无法打开目标文件")
            output.bufferedWriter(Charsets.UTF_8).use { it.write(Prefs.exportJson(this)) }
            showStatus(getString(R.string.settings_export_success))
        } catch (error: RuntimeException) {
            showStatus(getString(R.string.settings_export_failed, error.safeMessage()))
        } catch (error: java.io.IOException) {
            showStatus(getString(R.string.settings_export_failed, error.safeMessage()))
        }
    }

    private fun importSettingsFrom(uri: Uri) {
        try {
            val input = contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("无法打开配置文件")
            val bytes = input.use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAX_IMPORT_BYTES) {
                        throw Prefs.ImportException(getString(R.string.settings_file_too_large))
                    }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val result = Prefs.importJson(this, String(bytes, Charsets.UTF_8))
            setResult(RESULT_OK)
            showStatus(getString(R.string.settings_import_success, result.importedCount))
        } catch (error: RuntimeException) {
            showStatus(getString(R.string.settings_import_failed, error.safeMessage()))
        } catch (error: java.io.IOException) {
            showStatus(getString(R.string.settings_import_failed, error.safeMessage()))
        }
    }

    private fun showStatus(message: String) {
        statusView.visibility = View.VISIBLE
        statusView.text = message
        statusView.post { scrollView.smoothScrollTo(0, statusView.bottom) }
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
        private const val REQ_EXPORT_SETTINGS = 43
        private const val REQ_IMPORT_SETTINGS = 44
        private const val MAX_IMPORT_BYTES = 128 * 1024
    }
}
