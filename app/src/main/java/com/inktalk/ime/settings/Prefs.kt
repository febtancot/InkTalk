package com.inktalk.ime.settings

import android.content.Context
import android.content.SharedPreferences
import com.inktalk.ime.asr.EnglishRecognitionStrategy
import com.inktalk.ime.asr.HotwordCatalog
import com.inktalk.ime.asr.SpeechInputMode
import org.json.JSONObject
import java.util.UUID

/** 应用配置，统一走 SharedPreferences。 */
object Prefs {
    private const val FILE = "inktalk_prefs"
    private const val EXPORT_SCHEMA = "com.inktalk.ime.settings"
    private const val EXPORT_VERSION = 1
    private const val MAX_EXPORTED_STRING_CHARS = 20_000
    private const val KEY_HOTWORDS_DEFAULTS_VERSION = "asr_hotwords_defaults_version"
    private const val HOTWORDS_DEFAULTS_VERSION = 2
    private const val KEY_ASR_UID = "asr_install_uid"

    const val KEY_API_KEY = "asr_api_key"
    const val KEY_APP_KEY = "asr_app_key"
    const val KEY_ACCESS_KEY = "asr_access_key"
    const val KEY_HOTWORDS = "asr_hotwords"
    const val KEY_PRIORITY_HOTWORDS = "asr_priority_hotwords"
    const val KEY_ENABLE_DDC = "asr_enable_ddc"
    const val KEY_ENABLE_PUNC = "asr_enable_punc"
    const val KEY_ENABLE_ITN = "asr_enable_itn"
    const val KEY_INPUT_MODE = "speech_input_mode"
    const val KEY_ENGLISH_RECOGNITION_STRATEGY = "english_recognition_strategy"
    const val KEY_WIDE_IME_CONTENT_ON_RIGHT = "wide_ime_content_on_right"
    const val KEY_EXTREME_HEIGHT_MODE = "extreme_height_mode"
    const val KEY_ENABLE_FULL_KEYBOARD = "enable_full_keyboard"

    const val KEY_AI_BASE_URL = "ai_base_url"
    const val KEY_AI_API_KEY = "ai_api_key"
    const val KEY_AI_MODEL = "ai_model"
    const val KEY_AI_NO_THINKING = "ai_no_thinking"
    const val KEY_AI_REPLACE_ORIGINAL = "ai_replace_original"

    const val KEY_RESOURCE_ID = "asr_resource_id"

    const val DEFAULT_AI_BASE_URL = "https://api.openai.com/v1"
    const val DEFAULT_AI_MODEL = "gpt-4o-mini"

    /** 豆包流式语音识别大模型 2.0（小时版）资源 ID。 */
    const val ASR_RESOURCE_ID = "volc.seedasr.sauc.duration"

    /** 可选资源 ID（必须与控制台开通的计费版本一致）。 */
    val RESOURCE_IDS = listOf(
        "volc.seedasr.sauc.duration",   // 2.0 小时版
        "volc.seedasr.sauc.concurrent", // 2.0 并发版
        "volc.bigasr.sauc.duration",    // 1.0 小时版
        "volc.bigasr.sauc.concurrent",  // 1.0 并发版
    )

    fun resourceId(context: Context): String =
        get(context, KEY_RESOURCE_ID, ASR_RESOURCE_ID)

    /** 双向流式优化版端点：中文与中英混合模式使用，支持实时增量结果。 */
    const val ASR_ENDPOINT_ASYNC = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async"

    /** 流式输入端点：支持通过 language=en-US 固定英文识别。 */
    const val ASR_ENDPOINT_NOSTREAM = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_nostream"

    /** 保留原名称供连接测试使用。 */
    const val ASR_ENDPOINT = ASR_ENDPOINT_ASYNC

    private fun sp(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun get(context: Context, key: String, def: String = ""): String =
        sp(context).getString(key, def) ?: def

    fun getBool(context: Context, key: String, def: Boolean): Boolean =
        sp(context).getBoolean(key, def)

    fun put(context: Context, key: String, value: String) {
        sp(context).edit().putString(key, value).apply()
    }

    fun putBool(context: Context, key: String, value: Boolean) {
        sp(context).edit().putBoolean(key, value).apply()
    }

    /** 发送给 ASR 的匿名安装标识；不使用稳定的 Android 设备标识，也不导出。 */
    @Synchronized
    fun asrUid(context: Context): String {
        val preferences = sp(context)
        preferences.getString(KEY_ASR_UID, null)?.takeIf(String::isNotBlank)?.let { return it }
        val generated = "inktalk-" + UUID.randomUUID().toString()
        preferences.edit().putString(KEY_ASR_UID, generated).apply()
        return generated
    }

    /** 旧版空值一次性迁移为内置词表；迁移后用户主动保存空词表仍保持为空。 */
    fun hotwords(context: Context): String {
        val preferences = sp(context)
        val version = preferences.getInt(KEY_HOTWORDS_DEFAULTS_VERSION, 0)
        if (version < HOTWORDS_DEFAULTS_VERSION) {
            var migrated = preferences.getString(KEY_HOTWORDS, null)
            if (version < 1) migrated = HotwordCatalog.migrateLegacy(migrated)
            if (version < 2) migrated = HotwordCatalog.migrateBrandName(migrated.orEmpty())
            preferences.edit()
                .putString(KEY_HOTWORDS, migrated)
                .putInt(KEY_HOTWORDS_DEFAULTS_VERSION, HOTWORDS_DEFAULTS_VERSION)
                .apply()
            return migrated.orEmpty()
        }
        return get(context, KEY_HOTWORDS)
    }

    fun putHotwords(context: Context, raw: String): Int {
        val words = HotwordCatalog.parse(raw)
        val activeKeys = words.mapTo(HashSet()) { it.lowercase() }
        val priority = HotwordCatalog.parse(get(context, KEY_PRIORITY_HOTWORDS))
            .filter { it.lowercase() in activeKeys }
        sp(context).edit()
            .putString(KEY_HOTWORDS, HotwordCatalog.serialize(words))
            .putString(KEY_PRIORITY_HOTWORDS, HotwordCatalog.serialize(priority))
            .putInt(KEY_HOTWORDS_DEFAULTS_VERSION, HOTWORDS_DEFAULTS_VERSION)
            .apply()
        return words.size
    }

    data class HotwordAddResult(val added: List<String>, val duplicates: List<String>)

    fun addPriorityHotwords(context: Context, additions: Iterable<String>): HotwordAddResult {
        val current = HotwordCatalog.parse(hotwords(context))
        val currentKeys = current.mapTo(HashSet()) { it.lowercase() }
        val requested = HotwordCatalog.parse(additions.joinToString("\n"))
        val added = requested.filter { it.lowercase() !in currentKeys }
        val duplicates = requested.filter { it.lowercase() in currentKeys }
        val merged = HotwordCatalog.prepend(HotwordCatalog.serialize(current), added)
        // 用户再次确认内置或已有热词时，也应提升其请求优先级。
        val priority = HotwordCatalog.prepend(get(context, KEY_PRIORITY_HOTWORDS), requested)
        sp(context).edit()
            .putString(KEY_HOTWORDS, HotwordCatalog.serialize(merged))
            .putString(KEY_PRIORITY_HOTWORDS, HotwordCatalog.serialize(priority))
            .putInt(KEY_HOTWORDS_DEFAULTS_VERSION, HOTWORDS_DEFAULTS_VERSION)
            .apply()
        return HotwordAddResult(added, duplicates)
    }

    fun requestHotwords(context: Context): List<String> = HotwordCatalog.forRequest(
        raw = hotwords(context),
        priorityRaw = get(context, KEY_PRIORITY_HOTWORDS),
    )

    /** 新版控制台只需 X-Api-Key；留空则回退旧版 App Key + Access Token。 */
    fun hasAsrCredentials(context: Context): Boolean =
        get(context, KEY_API_KEY).isNotBlank() ||
            (get(context, KEY_APP_KEY).isNotBlank() && get(context, KEY_ACCESS_KEY).isNotBlank())

    fun hasAiConfig(context: Context): Boolean =
        get(context, KEY_AI_BASE_URL).isNotBlank() &&
            get(context, KEY_AI_API_KEY).isNotBlank() &&
            get(context, KEY_AI_MODEL).isNotBlank()

    data class ImportResult(val importedCount: Int)

    class ImportException(message: String) : IllegalArgumentException(message)

    fun exportJson(context: Context): String {
        val settings = JSONObject()
        settings.put(KEY_API_KEY, get(context, KEY_API_KEY))
        settings.put(KEY_APP_KEY, get(context, KEY_APP_KEY))
        settings.put(KEY_ACCESS_KEY, get(context, KEY_ACCESS_KEY))
        settings.put(KEY_HOTWORDS, hotwords(context))
        settings.put(KEY_PRIORITY_HOTWORDS, get(context, KEY_PRIORITY_HOTWORDS))
        settings.put(KEY_ENABLE_DDC, getBool(context, KEY_ENABLE_DDC, false))
        settings.put(KEY_ENABLE_PUNC, getBool(context, KEY_ENABLE_PUNC, true))
        settings.put(KEY_ENABLE_ITN, getBool(context, KEY_ENABLE_ITN, true))
        settings.put(KEY_EXTREME_HEIGHT_MODE, getBool(context, KEY_EXTREME_HEIGHT_MODE, false))
        settings.put(KEY_ENABLE_FULL_KEYBOARD, getBool(context, KEY_ENABLE_FULL_KEYBOARD, false))
        settings.put(
            KEY_INPUT_MODE,
            get(context, KEY_INPUT_MODE, SpeechInputMode.CHINESE.preferenceValue),
        )
        settings.put(
            KEY_ENGLISH_RECOGNITION_STRATEGY,
            get(
                context,
                KEY_ENGLISH_RECOGNITION_STRATEGY,
                EnglishRecognitionStrategy.REALTIME_BILINGUAL.preferenceValue,
            ),
        )
        settings.put(KEY_RESOURCE_ID, resourceId(context))
        settings.put(KEY_AI_BASE_URL, get(context, KEY_AI_BASE_URL, DEFAULT_AI_BASE_URL))
        settings.put(KEY_AI_API_KEY, get(context, KEY_AI_API_KEY))
        settings.put(KEY_AI_MODEL, get(context, KEY_AI_MODEL, DEFAULT_AI_MODEL))
        settings.put(KEY_AI_NO_THINKING, getBool(context, KEY_AI_NO_THINKING, false))
        settings.put(
            KEY_AI_REPLACE_ORIGINAL,
            getBool(context, KEY_AI_REPLACE_ORIGINAL, false),
        )
        return JSONObject()
            .put("schema", EXPORT_SCHEMA)
            .put("version", EXPORT_VERSION)
            .put("exported_at", System.currentTimeMillis())
            .put("settings", settings)
            .toString(2)
    }

    fun importJson(context: Context, rawJson: String): ImportResult {
        val root = try {
            JSONObject(rawJson)
        } catch (_: RuntimeException) {
            throw ImportException("文件不是有效的 JSON")
        }
        if (root.optString("schema") != EXPORT_SCHEMA) {
            throw ImportException("不是 inktalk 配置文件")
        }
        if (root.optInt("version", -1) != EXPORT_VERSION) {
            throw ImportException("暂不支持该配置文件版本")
        }
        val settings = root.optJSONObject("settings")
            ?: throw ImportException("配置文件缺少 settings 对象")
        val stringValues = LinkedHashMap<String, String>()
        val booleanValues = LinkedHashMap<String, Boolean>()

        stringKeys.forEach { key ->
            if (!settings.has(key)) return@forEach
            val value = settings.opt(key)
            if (value !is String) throw ImportException("字段 $key 必须是字符串")
            if (value.length > MAX_EXPORTED_STRING_CHARS) {
                throw ImportException("字段 $key 过长")
            }
            stringValues[key] = value
        }
        booleanKeys.forEach { key ->
            if (!settings.has(key)) return@forEach
            val value = settings.opt(key)
            if (value !is Boolean) throw ImportException("字段 $key 必须是布尔值")
            booleanValues[key] = value
        }
        if (stringValues.isEmpty() && booleanValues.isEmpty()) {
            throw ImportException("配置文件中没有可导入的设置")
        }
        stringValues[KEY_RESOURCE_ID]?.let {
            if (it !in RESOURCE_IDS) throw ImportException("语音模型与计费版本无效")
        }
        stringValues[KEY_INPUT_MODE]?.let { value ->
            if (SpeechInputMode.entries.none { it.preferenceValue == value }) {
                throw ImportException("语音输入模式无效")
            }
        }
        stringValues[KEY_ENGLISH_RECOGNITION_STRATEGY]?.let { value ->
            if (EnglishRecognitionStrategy.entries.none { it.preferenceValue == value }) {
                throw ImportException("英文识别方式无效")
            }
        }

        val editor = sp(context).edit()
        stringValues.forEach { (key, value) -> editor.putString(key, value) }
        booleanValues.forEach { (key, value) -> editor.putBoolean(key, value) }
        if (KEY_HOTWORDS in stringValues) {
            editor.putInt(KEY_HOTWORDS_DEFAULTS_VERSION, HOTWORDS_DEFAULTS_VERSION)
        }
        if (!editor.commit()) throw ImportException("配置写入失败")
        return ImportResult(stringValues.size + booleanValues.size)
    }

    private val stringKeys = listOf(
        KEY_API_KEY,
        KEY_APP_KEY,
        KEY_ACCESS_KEY,
        KEY_HOTWORDS,
        KEY_PRIORITY_HOTWORDS,
        KEY_INPUT_MODE,
        KEY_ENGLISH_RECOGNITION_STRATEGY,
        KEY_RESOURCE_ID,
        KEY_AI_BASE_URL,
        KEY_AI_API_KEY,
        KEY_AI_MODEL,
    )

    private val booleanKeys = listOf(
        KEY_ENABLE_DDC,
        KEY_ENABLE_PUNC,
        KEY_ENABLE_ITN,
        KEY_EXTREME_HEIGHT_MODE,
        KEY_ENABLE_FULL_KEYBOARD,
        KEY_AI_NO_THINKING,
        KEY_AI_REPLACE_ORIGINAL,
    )
}
