package cn.smsforwarder

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class AppRepository private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var serviceEnabledCache: Boolean? = null
    private var channelConfigsCache: List<ChannelConfig>? = null
    private var enabledChannelsCache: List<ChannelConfig>? = null
    private var statsCache: Stats? = null
    private var recentFailuresCache: List<FailureRecord>? = null
    private var recentForwardLogsCache: List<ForwardLogRecord>? = null
    private var recentFingerprintsCache: MutableList<String>? = null

    fun loadSnapshot(): AppSnapshot {
        return AppSnapshot(
            serviceEnabled = isServiceEnabled(),
            channelConfigs = getChannelConfigs(),
            stats = getStats(),
            recentFailures = getRecentFailures(),
            recentForwardLogs = getRecentForwardLogs(),
        )
    }

    @Synchronized
    fun invalidateCache() {
        serviceEnabledCache = null
        channelConfigsCache = null
        enabledChannelsCache = null
        statsCache = null
        recentFailuresCache = null
        recentForwardLogsCache = null
        recentFingerprintsCache = null
    }

    @Synchronized
    fun isServiceEnabled(): Boolean {
        return serviceEnabledCache ?: prefs.getBoolean(KEY_SERVICE_ENABLED, true).also {
            serviceEnabledCache = it
        }
    }

    @Synchronized
    fun setServiceEnabled(enabled: Boolean) {
        if (isServiceEnabled() == enabled) {
            return
        }
        serviceEnabledCache = enabled
        prefs.edit().putBoolean(KEY_SERVICE_ENABLED, enabled).apply()
    }

    @Synchronized
    fun getChannelConfigs(): List<ChannelConfig> {
        channelConfigsCache?.let { return it }
        val stored = mutableMapOf<ChannelType, ChannelConfig>()
        val raw = prefs.getString(KEY_CHANNELS_JSON, null)
        if (!raw.isNullOrBlank()) {
            runCatching {
                val array = JSONArray(raw)
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val type = ChannelType.fromKey(item.optString(JSON_KEY_TYPE)) ?: continue
                    stored[type] = ChannelConfig(
                        type = type,
                        enabled = item.optBoolean(JSON_KEY_ENABLED, false),
                        webhookUrl = item.optString(JSON_KEY_WEBHOOK_URL),
                        secret = item.optString(JSON_KEY_SECRET),
                        note = item.optString(JSON_KEY_NOTE),
                        forwardTemplate = item.optString(JSON_KEY_FORWARD_TEMPLATE),
                        smtpHost = item.optString(JSON_KEY_SMTP_HOST),
                        smtpPort = item.optString(JSON_KEY_SMTP_PORT),
                        smtpUsername = item.optString(JSON_KEY_SMTP_USERNAME),
                        smtpPassword = item.optString(JSON_KEY_SMTP_PASSWORD),
                        senderEmail = item.optString(JSON_KEY_SENDER_EMAIL),
                        senderDisplayName = item.optString(JSON_KEY_SENDER_DISPLAY_NAME),
                        recipientEmail = item.optString(JSON_KEY_RECIPIENT_EMAIL),
                        useTls = item.optBoolean(JSON_KEY_USE_TLS, ChannelConfigs.DEFAULT_EMAIL_USE_TLS),
                    )
                }
            }
        }

        return ChannelType.entries.map { type ->
            stored[type] ?: ChannelConfigs.defaultConfig(type)
        }.map { it.normalized() }.also {
            channelConfigsCache = it
        }
    }

    @Synchronized
    fun saveChannelConfig(config: ChannelConfig) {
        val normalized = config.normalized()
        val configs = getChannelConfigs()
        if (configs.firstOrNull { it.type == normalized.type } == normalized) {
            return
        }
        val merged = configs.associateBy { it.type }.toMutableMap()
        merged[normalized.type] = normalized
        persistChannels(
            ChannelType.entries.map { type ->
                (merged[type] ?: ChannelConfigs.defaultConfig(type)).normalized()
            },
        )
    }

    @Synchronized
    fun getEnabledChannels(): List<ChannelConfig> {
        return enabledChannelsCache ?: getChannelConfigs()
            .filter { it.enabled && it.isReadyForDispatch() }
            .also { enabledChannelsCache = it }
    }

    @Synchronized
    fun getStats(): Stats {
        statsCache?.let { return it }
        val raw = prefs.getString(KEY_STATS_JSON, null) ?: return Stats().also { statsCache = it }
        return runCatching {
            val json = JSONObject(raw)
            val channelSuccessCounts = buildMap {
                val channelSuccessJson = json.optJSONObject(JSON_KEY_CHANNEL_SUCCESS_COUNTS)
                ChannelType.entries.forEach { type ->
                    val count = channelSuccessJson?.optInt(type.key, 0) ?: 0
                    if (count > 0) {
                        put(type, count)
                    }
                }
            }
            Stats(
                receivedCount = json.optInt(JSON_KEY_RECEIVED_COUNT, 0),
                channelSuccessCounts = channelSuccessCounts,
                failureCount = json.optInt(JSON_KEY_FAILURE_COUNT, 0),
                lastReceivedAt = json.optLong(JSON_KEY_LAST_RECEIVED_AT, 0L),
                lastDispatchAt = json.optLong(JSON_KEY_LAST_DISPATCH_AT, 0L),
            )
        }.getOrDefault(Stats()).also {
            statsCache = it
        }
    }

    @Synchronized
    fun recordIncomingPayload(payload: ForwardPayload) {
        val currentStats = getStats()
        val updatedStats = currentStats.copy(
            receivedCount = currentStats.receivedCount + 1,
            lastReceivedAt = payload.receivedAt,
        )
        val updatedLogs = getRecentForwardLogs().toMutableList().apply {
            add(
                0,
                ForwardLogRecord(
                    timestamp = payload.receivedAt,
                    type = payload.type,
                    sender = payload.sender,
                    body = payload.body,
                ),
            )
        }.take(MAX_FORWARD_LOG_RECORDS)
        persistStatsAndForwardLogs(updatedStats, updatedLogs)
    }

    @Synchronized
    fun recordDispatchBatch(
        successChannels: List<ChannelType>,
        failures: List<FailureRecord>,
        dispatchedAt: Long = System.currentTimeMillis(),
    ) {
        if (successChannels.isEmpty() && failures.isEmpty()) {
            return
        }

        val current = getStats()
        val updatedChannelSuccessCounts = current.channelSuccessCounts.toMutableMap().apply {
            successChannels.forEach { type ->
                this[type] = (this[type] ?: 0) + 1
            }
        }
        val updatedStats = current.copy(
            channelSuccessCounts = updatedChannelSuccessCounts,
            failureCount = current.failureCount + failures.size,
            lastDispatchAt = dispatchedAt,
        )
        val updatedFailures = if (failures.isEmpty()) {
            getRecentFailures()
        } else {
            (failures + getRecentFailures()).take(MAX_FAILURE_RECORDS)
        }
        persistStatsAndFailures(updatedStats, updatedFailures)
    }

    @Synchronized
    fun getRecentFailures(): List<FailureRecord> {
        recentFailuresCache?.let { return it }
        val raw = prefs.getString(KEY_FAILURES_JSON, null) ?: return emptyList<FailureRecord>().also {
            recentFailuresCache = it
        }
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val type = ChannelType.fromKey(item.optString(JSON_KEY_TYPE)) ?: continue
                    add(
                        FailureRecord(
                            timestamp = item.optLong(JSON_KEY_TIMESTAMP, 0L),
                            channelType = type,
                            summary = item.optString(JSON_KEY_SUMMARY),
                            reason = item.optString(JSON_KEY_REASON),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList()).also {
            recentFailuresCache = it
        }
    }

    @Synchronized
    fun getRecentForwardLogs(): List<ForwardLogRecord> {
        recentForwardLogsCache?.let { return it }
        val raw = prefs.getString(KEY_FORWARD_LOGS_JSON, null) ?: return emptyList<ForwardLogRecord>().also {
            recentForwardLogsCache = it
        }
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    add(
                        ForwardLogRecord(
                            timestamp = item.optLong(JSON_KEY_TIMESTAMP, 0L),
                            type = ForwardRecordType.fromKey(item.optString(JSON_KEY_RECORD_TYPE))
                                ?: ForwardRecordType.SMS,
                            sender = item.optString(JSON_KEY_SENDER),
                            body = item.optString(JSON_KEY_BODY),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList()).also {
            recentForwardLogsCache = it
        }
    }

    @Synchronized
    fun markForwardSeen(fingerprint: String): Boolean {
        val items = readFingerprints()
        if (items.contains(fingerprint)) {
            return false
        }

        items.add(0, fingerprint)
        while (items.size > MAX_FORWARD_FINGERPRINTS) {
            items.removeLast()
        }
        prefs.edit().putString(KEY_RECENT_FORWARD_JSON, JSONArray(items).toString()).apply()
        return true
    }

    private fun readFingerprints(): MutableList<String> {
        recentFingerprintsCache?.let { return it }
        val shouldMigrateLegacyKey = !prefs.contains(KEY_RECENT_FORWARD_JSON) && prefs.contains(KEY_RECENT_SMS_JSON)
        val raw = prefs.getString(KEY_RECENT_FORWARD_JSON, null)
            ?: prefs.getString(KEY_RECENT_SMS_JSON, null)
            ?: return mutableListOf<String>().also { recentFingerprintsCache = it }
        val fingerprints = runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    array.optString(index)
                        .trim()
                        .takeIf { it.isNotBlank() }
                        ?.let(::add)
                }
            }.toMutableList()
        }.getOrDefault(mutableListOf()).also {
            while (it.size > MAX_FORWARD_FINGERPRINTS) {
                it.removeLast()
            }
        }
        if (shouldMigrateLegacyKey) {
            prefs.edit()
                .putString(KEY_RECENT_FORWARD_JSON, JSONArray(fingerprints).toString())
                .remove(KEY_RECENT_SMS_JSON)
                .apply()
        }
        return fingerprints.also {
            recentFingerprintsCache = it
        }
    }

    private fun persistChannels(configs: List<ChannelConfig>) {
        channelConfigsCache = configs
        enabledChannelsCache = configs.filter { it.enabled && it.isReadyForDispatch() }
        prefs.edit().putString(KEY_CHANNELS_JSON, serializeChannels(configs)).apply()
    }

    private fun persistStatsAndForwardLogs(stats: Stats, logs: List<ForwardLogRecord>) {
        statsCache = stats
        recentForwardLogsCache = logs
        prefs.edit()
            .putString(KEY_STATS_JSON, serializeStats(stats))
            .putString(KEY_FORWARD_LOGS_JSON, serializeForwardLogs(logs))
            .apply()
    }

    private fun persistStatsAndFailures(stats: Stats, failures: List<FailureRecord>) {
        statsCache = stats
        recentFailuresCache = failures
        prefs.edit()
            .putString(KEY_STATS_JSON, serializeStats(stats))
            .putString(KEY_FAILURES_JSON, serializeFailures(failures))
            .apply()
    }

    private fun serializeChannels(configs: List<ChannelConfig>): String {
        val array = JSONArray()
        configs.forEach { config ->
            array.put(
                JSONObject()
                    .put(JSON_KEY_TYPE, config.type.key)
                    .put(JSON_KEY_ENABLED, config.enabled)
                    .put(JSON_KEY_WEBHOOK_URL, config.webhookUrl)
                    .put(JSON_KEY_SECRET, config.secret)
                    .put(JSON_KEY_NOTE, config.note)
                    .put(JSON_KEY_FORWARD_TEMPLATE, config.forwardTemplate)
                    .put(JSON_KEY_SMTP_HOST, config.smtpHost)
                    .put(JSON_KEY_SMTP_PORT, config.smtpPort)
                    .put(JSON_KEY_SMTP_USERNAME, config.smtpUsername)
                    .put(JSON_KEY_SMTP_PASSWORD, config.smtpPassword)
                    .put(JSON_KEY_SENDER_EMAIL, config.senderEmail)
                    .put(JSON_KEY_SENDER_DISPLAY_NAME, config.senderDisplayName)
                    .put(JSON_KEY_RECIPIENT_EMAIL, config.recipientEmail)
                    .put(JSON_KEY_USE_TLS, config.useTls),
            )
        }
        return array.toString()
    }

    private fun serializeStats(stats: Stats): String {
        val channelSuccessCounts = JSONObject().apply {
            ChannelType.entries.forEach { type ->
                stats.channelSuccessCounts[type]?.takeIf { it > 0 }?.let { put(type.key, it) }
            }
        }
        return JSONObject()
            .put(JSON_KEY_RECEIVED_COUNT, stats.receivedCount)
            .put(JSON_KEY_CHANNEL_SUCCESS_COUNTS, channelSuccessCounts)
            .put(JSON_KEY_FAILURE_COUNT, stats.failureCount)
            .put(JSON_KEY_LAST_RECEIVED_AT, stats.lastReceivedAt)
            .put(JSON_KEY_LAST_DISPATCH_AT, stats.lastDispatchAt)
            .toString()
    }

    private fun serializeFailures(failures: List<FailureRecord>): String {
        val array = JSONArray()
        failures.forEach { record ->
            array.put(
                JSONObject()
                    .put(JSON_KEY_TIMESTAMP, record.timestamp)
                    .put(JSON_KEY_TYPE, record.channelType.key)
                    .put(JSON_KEY_SUMMARY, record.summary)
                    .put(JSON_KEY_REASON, record.reason),
            )
        }
        return array.toString()
    }

    private fun serializeForwardLogs(logs: List<ForwardLogRecord>): String {
        val array = JSONArray()
        logs.forEach { record ->
            array.put(
                JSONObject()
                    .put(JSON_KEY_TIMESTAMP, record.timestamp)
                    .put(JSON_KEY_RECORD_TYPE, record.type.key)
                    .put(JSON_KEY_SENDER, record.sender)
                    .put(JSON_KEY_BODY, record.body),
            )
        }
        return array.toString()
    }

    companion object {
        @Volatile
        private var instance: AppRepository? = null

        fun getInstance(context: Context): AppRepository {
            return instance ?: synchronized(this) {
                instance ?: AppRepository(context).also { instance = it }
            }
        }

        private const val PREFS_NAME = "sms_forwarder_store"
        private const val KEY_SERVICE_ENABLED = "service_enabled"
        private const val KEY_CHANNELS_JSON = "channels_json"
        private const val KEY_STATS_JSON = "stats_json"
        private const val KEY_FAILURES_JSON = "failures_json"
        private const val KEY_FORWARD_LOGS_JSON = "forward_logs_json"
        private const val KEY_RECENT_FORWARD_JSON = "recent_forward_json"
        private const val KEY_RECENT_SMS_JSON = "recent_sms_json"

        private const val JSON_KEY_TYPE = "type"
        private const val JSON_KEY_RECORD_TYPE = "recordType"
        private const val JSON_KEY_ENABLED = "enabled"
        private const val JSON_KEY_WEBHOOK_URL = "webhookUrl"
        private const val JSON_KEY_SECRET = "secret"
        private const val JSON_KEY_NOTE = "note"
        private const val JSON_KEY_FORWARD_TEMPLATE = "forwardTemplate"
        private const val JSON_KEY_SMTP_HOST = "smtpHost"
        private const val JSON_KEY_SMTP_PORT = "smtpPort"
        private const val JSON_KEY_SMTP_USERNAME = "smtpUsername"
        private const val JSON_KEY_SMTP_PASSWORD = "smtpPassword"
        private const val JSON_KEY_SENDER_EMAIL = "senderEmail"
        private const val JSON_KEY_SENDER_DISPLAY_NAME = "senderDisplayName"
        private const val JSON_KEY_RECIPIENT_EMAIL = "recipientEmail"
        private const val JSON_KEY_USE_TLS = "useTls"
        private const val JSON_KEY_RECEIVED_COUNT = "receivedCount"
        private const val JSON_KEY_CHANNEL_SUCCESS_COUNTS = "channelSuccessCounts"
        private const val JSON_KEY_FAILURE_COUNT = "failureCount"
        private const val JSON_KEY_LAST_RECEIVED_AT = "lastReceivedAt"
        private const val JSON_KEY_LAST_DISPATCH_AT = "lastDispatchAt"
        private const val JSON_KEY_TIMESTAMP = "timestamp"
        private const val JSON_KEY_SUMMARY = "summary"
        private const val JSON_KEY_REASON = "reason"
        private const val JSON_KEY_SENDER = "sender"
        private const val JSON_KEY_BODY = "body"

        private const val MAX_FAILURE_RECORDS = 10
        private const val MAX_FORWARD_LOG_RECORDS = 30
        private const val MAX_FORWARD_FINGERPRINTS = 30
    }
}
