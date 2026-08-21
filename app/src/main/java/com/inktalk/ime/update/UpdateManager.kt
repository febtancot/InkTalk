package com.inktalk.ime.update

import android.app.Activity
import android.app.DownloadManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.inktalk.ime.R
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest

object UpdateManager {
    const val MANIFEST_URL = "https://inktalk.liveby.app/update.json"
    const val ACTION_INSTALL_STATUS = "com.inktalk.ime.UPDATE_INSTALL_STATUS"
    private const val PREFS = "inktalk_update"
    private const val KEY_DOWNLOAD_ID = "download_id"
    private const val KEY_FILE_PATH = "file_path"
    private const val KEY_SHA256 = "sha256"
    private val client = OkHttpClient()
    private val main = Handler(Looper.getMainLooper())

    sealed interface CheckResult {
        data class Available(val manifest: UpdateManifest) : CheckResult
        data object UpToDate : CheckResult
        data class Failed(val message: String) : CheckResult
    }

    fun check(context: Context, callback: (CheckResult) -> Unit): Call {
        val request = Request.Builder().url(MANIFEST_URL).get().build()
        val call = client.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                main.post { callback(CheckResult.Failed(e.message ?: "网络错误")) }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val result = if (!it.isSuccessful) {
                        CheckResult.Failed("HTTP ${it.code}")
                    } else {
                        val manifest = UpdateManifestParser.parse(it.body?.string().orEmpty())
                        when {
                            manifest == null -> CheckResult.Failed("更新清单格式无效")
                            manifest.versionCode > currentVersionCode(context) -> CheckResult.Available(manifest)
                            else -> CheckResult.UpToDate
                        }
                    }
                    main.post { callback(result) }
                }
            }
        })
        return call
    }

    fun canInstallPackages(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(activity: Activity) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${activity.packageName}"),
        )
        activity.startActivity(intent)
    }

    fun enqueueDownload(context: Context, manifest: UpdateManifest): Long {
        val directory = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "updates")
        directory.mkdirs()
        val target = File(directory, "inktalk-${manifest.versionName}-release.apk")
        if (target.exists()) target.delete()
        val request = DownloadManager.Request(Uri.parse(manifest.apkUrl))
            .setTitle(context.getString(R.string.update_download_title, manifest.versionName))
            .setDescription(context.getString(R.string.update_download_description))
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(target))
        val id = context.getSystemService(DownloadManager::class.java).enqueue(request)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_DOWNLOAD_ID, id)
            .putString(KEY_FILE_PATH, target.absolutePath)
            .putString(KEY_SHA256, manifest.sha256)
            .apply()
        return id
    }

    fun handleDownloadComplete(context: Context, downloadId: Long): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getLong(KEY_DOWNLOAD_ID, -1L) != downloadId) return null
        val file = File(prefs.getString(KEY_FILE_PATH, null) ?: return "缺少下载文件路径")
        val expectedHash = prefs.getString(KEY_SHA256, null) ?: return "缺少更新校验值"
        if (!file.isFile) return "更新文件不存在"
        if (!sha256(file).equals(expectedHash, ignoreCase = true)) return "更新文件 SHA-256 校验失败"
        val archive = packageInfo(context, file.absolutePath, archive = true)
            ?: return "无法读取更新 APK"
        if (archive.packageName != context.packageName) return "更新 APK 包名不匹配"
        val installed = packageInfo(context, context.packageName, archive = false)
            ?: return "无法读取当前应用签名"
        if (signatureDigests(archive) != signatureDigests(installed)) return "更新 APK 签名不匹配"
        if (!canInstallPackages(context)) return "请先允许 inktalk 安装未知应用"
        return try {
            installWithSystemConfirmation(context, file)
            null
        } catch (error: Exception) {
            "无法启动系统安装：${error.message ?: error.javaClass.simpleName}"
        }
    }

    private fun installWithSystemConfirmation(context: Context, file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= 33) setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            FileInputStream(file).use { input ->
                session.openWrite("inktalk.apk", 0, file.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            val intent = Intent(context, UpdateInstallReceiver::class.java).setAction(ACTION_INSTALL_STATUS)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(pending.intentSender)
        }
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(context: Context, value: String, archive: Boolean): android.content.pm.PackageInfo? {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        return if (archive) context.packageManager.getPackageArchiveInfo(value, flags)
        else try { context.packageManager.getPackageInfo(value, flags) } catch (_: Exception) { null }
    }

    @Suppress("DEPRECATION")
    private fun signatureDigests(info: android.content.pm.PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            val signing = info.signingInfo ?: return emptySet()
            if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
        } else info.signatures.orEmpty()
        return signatures.mapTo(HashSet()) { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Suppress("DEPRECATION")
    private fun currentVersionCode(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    }
}
