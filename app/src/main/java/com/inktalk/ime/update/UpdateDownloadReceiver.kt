package com.inktalk.ime.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.inktalk.ime.R

class UpdateDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        val pending = goAsync()
        Thread {
            val error = UpdateManager.handleDownloadComplete(context.applicationContext, id)
            if (error != null) Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, context.getString(R.string.update_failed, error), Toast.LENGTH_LONG).show()
            }
            pending.finish()
        }.start()
    }
}
