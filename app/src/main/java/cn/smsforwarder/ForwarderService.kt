package cn.smsforwarder

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ForwarderService : Service() {
    private lateinit var repository: AppRepository
    private lateinit var dispatcher: ChannelDispatcher
    private lateinit var executor: ExecutorService

    override fun onCreate() {
        super.onCreate()
        repository = AppRepository.getInstance(this)
        dispatcher = ChannelDispatcher()
        executor = Executors.newSingleThreadExecutor()
        createNotificationChannel()
        isRunning = true
        if (lastActionText.isBlank()) {
            lastActionText = getString(R.string.last_action_idle)
        }
        val initialSummary = notificationSummary()
        val initialActionText = lastActionText.ifBlank { getString(R.string.last_action_idle) }
        lastNotificationSummary = initialSummary
        lastNotifiedActionText = initialActionText
        startForeground(NOTIFICATION_ID, buildNotification(initialSummary, initialActionText))
        sendStateBroadcast()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PROCESS_PAYLOAD -> handlePayloadIntent(intent)
            ACTION_TEST_CHANNEL -> handleTestIntent(intent)
            ACTION_START,
            null,
            -> {
                if (lastActionText != getString(R.string.last_action_idle)) {
                    lastActionText = getString(R.string.last_action_idle)
                    refreshNotificationAndState()
                }
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        scheduleRestartIfNeeded()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        isRunning = false
        executor.shutdownNow()
        scheduleRestartIfNeeded()
        sendStateBroadcast()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun handlePayloadIntent(intent: Intent) {
        if (!repository.isServiceEnabled()) {
            return
        }
        val recordType = ForwardRecordType.fromKey(intent.getStringExtra(EXTRA_RECORD_TYPE))
            ?: ForwardRecordType.SMS
        val sender = intent.getStringExtra(EXTRA_SENDER).orEmpty()
        val body = intent.getStringExtra(EXTRA_BODY).orEmpty()
        val receivedAt = intent.getLongExtra(EXTRA_RECEIVED_AT, System.currentTimeMillis())
        if (sender.isBlank() && body.isBlank()) {
            return
        }

        executor.execute {
            processIncomingPayload(
                ForwardPayload(
                    type = recordType,
                    sender = sender,
                    body = body,
                    receivedAt = receivedAt,
                ).normalized(),
            )
        }
    }

    private fun handleTestIntent(intent: Intent) {
        val type = ChannelType.fromKey(intent.getStringExtra(EXTRA_CHANNEL_TYPE)) ?: return
        val config = repository.getChannelConfigs().firstOrNull { it.type == type } ?: return
        executor.execute {
            val result = dispatcher.sendTest(config, MiuiSupport.deviceLabel())
            lastActionText = if (result.success) {
                "${config.type.displayName} 测试成功"
            } else {
                "${config.type.displayName} 测试失败"
            }
            refreshNotificationAndState()
            sendTestResult(config.type.displayName, result)
        }
    }

    private fun processIncomingPayload(payload: ForwardPayload) {
        repository.recordIncomingPayload(payload)
        val enabledChannels = repository.getEnabledChannels()
        if (enabledChannels.isEmpty()) {
            lastActionText = getString(R.string.no_enabled_channel)
            refreshNotificationAndState()
            return
        }

        val successChannels = mutableListOf<ChannelType>()
        val failures = mutableListOf<FailureRecord>()
        val deviceLabel = MiuiSupport.deviceLabel()
        val payloadSummary = payload.summary()
        enabledChannels.forEach { config ->
            val result = dispatchWithRetry(config, payload, deviceLabel)
            if (result.success) {
                successChannels += config.type
            } else {
                failures += FailureRecord(
                    timestamp = System.currentTimeMillis(),
                    channelType = config.type,
                    summary = payloadSummary,
                    reason = result.message,
                )
            }
        }
        repository.recordDispatchBatch(successChannels = successChannels, failures = failures)

        lastActionText = getString(
            R.string.last_action_handled_template,
            payload.type.displayName,
            payload.sender,
        )
        refreshNotificationAndState()
    }

    private fun dispatchWithRetry(
        config: ChannelConfig,
        payload: ForwardPayload,
        deviceLabel: String,
    ): DispatchResult {
        var lastFailure = "Unknown error"
        for (attempt in 1..MAX_RETRY_ATTEMPTS) {
            val result = dispatcher.sendPayload(config, payload, deviceLabel)
            if (result.success) {
                return result
            }

            lastFailure = buildString {
                append(result.message.ifBlank { "HTTP ${result.httpCode}" })
                append(" (attempt ")
                append(attempt)
                append('/')
                append(MAX_RETRY_ATTEMPTS)
                append(')')
            }

            if (attempt < MAX_RETRY_ATTEMPTS) {
                SystemClock.sleep(RETRY_INTERVAL_MS * attempt)
            }
        }
        return DispatchResult(success = false, message = lastFailure)
    }

    private fun buildNotification(summary: String, actionText: String) =
        NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_sms_forwarder)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(summary)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    summary + "\n" + getString(R.string.last_action_prefix, actionText),
                ),
            )
            .setContentIntent(mainActivityPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun updateNotification() {
        val summary = notificationSummary()
        val actionText = lastActionText.ifBlank { getString(R.string.last_action_idle) }
        if (summary == lastNotificationSummary && actionText == lastNotifiedActionText) {
            return
        }
        val manager = ContextCompat.getSystemService(this, NotificationManager::class.java) ?: return
        lastNotificationSummary = summary
        lastNotifiedActionText = actionText
        manager.notify(NOTIFICATION_ID, buildNotification(summary, actionText))
    }

    private fun notificationSummary(): String {
        val stats = repository.getStats()
        return if (repository.getEnabledChannels().isEmpty()) {
            getString(R.string.notification_no_channel)
        } else {
            getString(
                R.string.notification_text_template,
                stats.receivedCount,
                stats.totalSuccessCount(),
                stats.failureCount,
            )
        }
    }

    private fun mainActivityPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            100,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    private fun scheduleRestartIfNeeded() {
        if (!repository.isServiceEnabled()) {
            return
        }

        val alarmManager = getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(this, ForwarderService::class.java).setAction(ACTION_START)
        val pendingIntent = PendingIntent.getService(
            this,
            101,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager.set(
            AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + SERVICE_RESTART_DELAY_MS,
            pendingIntent,
        )
    }

    private fun sendStateBroadcast() {
        broadcastStateChanged(this)
    }

    private fun refreshNotificationAndState() {
        updateNotification()
        sendStateBroadcast()
    }

    private fun sendTestResult(channelLabel: String, result: DispatchResult) {
        sendBroadcast(
            Intent(ACTION_TEST_RESULT)
                .setPackage(packageName)
                .putExtra(EXTRA_TEST_CHANNEL_LABEL, channelLabel)
                .putExtra(EXTRA_TEST_SUCCESS, result.success)
                .putExtra(EXTRA_TEST_MESSAGE, result.message),
        )
    }

    companion object {
        const val ACTION_START = "cn.smsforwarder.action.START"
        const val ACTION_PROCESS_PAYLOAD = "cn.smsforwarder.action.PROCESS_PAYLOAD"
        const val ACTION_TEST_CHANNEL = "cn.smsforwarder.action.TEST_CHANNEL"
        const val ACTION_APP_STATE_CHANGED = "cn.smsforwarder.action.STATE_CHANGED"
        const val ACTION_TEST_RESULT = "cn.smsforwarder.action.TEST_RESULT"

        private const val EXTRA_RECORD_TYPE = "extra_record_type"
        private const val EXTRA_SENDER = "extra_sender"
        private const val EXTRA_BODY = "extra_body"
        private const val EXTRA_RECEIVED_AT = "extra_received_at"
        private const val EXTRA_CHANNEL_TYPE = "extra_channel_type"

        const val EXTRA_TEST_CHANNEL_LABEL = "extra_test_channel_label"
        const val EXTRA_TEST_SUCCESS = "extra_test_success"
        const val EXTRA_TEST_MESSAGE = "extra_test_message"

        private const val NOTIFICATION_CHANNEL_ID = "sms_forwarder_service"
        private const val NOTIFICATION_ID = 1101
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val RETRY_INTERVAL_MS = 2_000L
        private const val SERVICE_RESTART_DELAY_MS = 5_000L

        @Volatile
        var isRunning: Boolean = false

        @Volatile
        var lastActionText: String = ""

        @Volatile
        private var lastNotificationSummary: String = ""

        @Volatile
        private var lastNotifiedActionText: String = ""

        fun start(context: Context) {
            if (isRunning) {
                return
            }
            ContextCompat.startForegroundService(
                context,
                Intent(context, ForwarderService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ForwarderService::class.java))
        }

        fun broadcastStateChanged(context: Context) {
            context.sendBroadcast(Intent(ACTION_APP_STATE_CHANGED).setPackage(context.packageName))
        }

        fun enqueuePayload(context: Context, payload: ForwardPayload) {
            val normalized = payload.normalized()
            val intent = Intent(context, ForwarderService::class.java)
                .setAction(ACTION_PROCESS_PAYLOAD)
                .putExtra(EXTRA_RECORD_TYPE, normalized.type.key)
                .putExtra(EXTRA_SENDER, normalized.sender)
                .putExtra(EXTRA_BODY, normalized.body)
                .putExtra(EXTRA_RECEIVED_AT, normalized.receivedAt)
            startServiceCompat(context, intent)
        }

        fun enqueueTest(context: Context, config: ChannelConfig) {
            val intent = Intent(context, ForwarderService::class.java)
                .setAction(ACTION_TEST_CHANNEL)
                .putExtra(EXTRA_CHANNEL_TYPE, config.type.key)
            startServiceCompat(context, intent)
        }

        private fun startServiceCompat(context: Context, intent: Intent) {
            val started = if (isRunning) {
                runCatching { context.startService(intent) }.isSuccess
            } else {
                false
            }
            if (!started) {
                ContextCompat.startForegroundService(context, intent)
            }
        }
    }
}
