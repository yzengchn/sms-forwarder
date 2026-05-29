package cn.smsforwarder

data class ChannelConfig(
    val type: ChannelType,
    val enabled: Boolean = false,
    val webhookUrl: String = "",
    val secret: String = "",
    val note: String = "",
    val forwardTemplate: String = "",
    val smtpHost: String = "",
    val smtpPort: String = ChannelConfigs.DEFAULT_EMAIL_SMTP_PORT,
    val smtpUsername: String = "",
    val smtpPassword: String = "",
    val senderEmail: String = "",
    val senderDisplayName: String = ChannelConfigs.DEFAULT_EMAIL_SENDER_DISPLAY_NAME,
    val recipientEmail: String = "",
    val useTls: Boolean = ChannelConfigs.DEFAULT_EMAIL_USE_TLS,
)

enum class ChannelType(val key: String, val displayName: String) {
    DINGTALK("dingtalk", "钉钉渠道"),
    FEISHU("feishu", "飞书渠道"),
    EMAIL("email", "邮件渠道"),
    ;

    companion object {
        fun fromKey(value: String?): ChannelType? {
            return entries.firstOrNull { it.key == value }
        }
    }
}

enum class ForwardRecordType(val key: String, val displayName: String) {
    SMS("sms", "短信"),
    MISSED_CALL("missed_call", "未接来电"),
    ;

    companion object {
        fun fromKey(value: String?): ForwardRecordType? {
            return entries.firstOrNull { it.key == value }
        }
    }
}

data class Stats(
    val receivedCount: Int = 0,
    val channelSuccessCounts: Map<ChannelType, Int> = emptyMap(),
    val failureCount: Int = 0,
    val lastReceivedAt: Long = 0L,
    val lastDispatchAt: Long = 0L,
)

fun Stats.totalSuccessCount(): Int {
    return channelSuccessCounts.values.sum()
}

data class FailureRecord(
    val timestamp: Long,
    val channelType: ChannelType,
    val summary: String,
    val reason: String,
)

data class ForwardLogRecord(
    val timestamp: Long,
    val type: ForwardRecordType = ForwardRecordType.SMS,
    val sender: String,
    val body: String,
)

data class AppSnapshot(
    val serviceEnabled: Boolean,
    val channelConfigs: List<ChannelConfig>,
    val stats: Stats,
    val recentFailures: List<FailureRecord>,
    val recentForwardLogs: List<ForwardLogRecord>,
)

data class ForwardPayload(
    val type: ForwardRecordType,
    val sender: String,
    val body: String,
    val receivedAt: Long,
)

private val BODY_PREVIEW_WHITESPACE_REGEX = Regex("\\s+")
private const val DEFAULT_SUMMARY_PREVIEW_LENGTH = 48

fun ForwardRecordType.defaultSenderLabel(): String {
    return when (this) {
        ForwardRecordType.SMS -> "未知发件方"
        ForwardRecordType.MISSED_CALL -> "未知来电"
    }
}

fun ForwardRecordType.defaultBodyText(): String {
    return when (this) {
        ForwardRecordType.SMS -> "空短信内容"
        ForwardRecordType.MISSED_CALL -> "系统通知识别到一条未接来电。"
    }
}

fun ForwardPayload.normalized(): ForwardPayload {
    return copy(
        sender = sender.trim().ifBlank { type.defaultSenderLabel() },
        body = body.trim(),
        receivedAt = receivedAt.takeIf { it > 0L } ?: System.currentTimeMillis(),
    )
}

fun ForwardPayload.bodyOrDefault(): String {
    return body.ifBlank { type.defaultBodyText() }
}

fun ForwardPayload.renderTemplate(template: String, deviceLabel: String, note: String = ""): String {
    if (template.isBlank()) return ""
    return template
        .replace("{{type}}", type.displayName)
        .replace("{{device}}", deviceLabel)
        .replace("{{time}}", TimeFormatter.format(receivedAt))
        .replace("{{sender}}", sender)
        .replace("{{body}}", bodyOrDefault())
        .replace("{{note}}", note)
}

fun ForwardPayload.summary(maxPreviewLength: Int = DEFAULT_SUMMARY_PREVIEW_LENGTH): String {
    val preview = bodyOrDefault()
        .replace('\n', ' ')
        .replace(BODY_PREVIEW_WHITESPACE_REGEX, " ")
        .trim()
        .take(maxPreviewLength)
    return "${type.displayName} | $sender | $preview"
}

fun ForwardPayload.dedupeFingerprint(source: String, sourceId: String = ""): String {
    val normalized = normalized()
    return buildString {
        append(normalized.type.key)
        append('|')
        append(source.trim())
        if (sourceId.isNotBlank()) {
            append('|')
            append(sourceId.trim())
        }
        append('|')
        append(normalized.sender)
        append('|')
        append(normalized.receivedAt)
        append('|')
        append(normalized.body.hashCode())
    }
}

data class DispatchResult(
    val success: Boolean,
    val httpCode: Int = -1,
    val message: String = "",
)

fun ChannelConfig.normalized(): ChannelConfig {
    val trimmed = when (type) {
        ChannelType.DINGTALK,
        ChannelType.FEISHU,
        -> copy(
            webhookUrl = webhookUrl.trim(),
            secret = secret.trim(),
            note = note.trim(),
            forwardTemplate = forwardTemplate.trim(),
        )

        ChannelType.EMAIL -> copy(
            note = note.trim(),
            forwardTemplate = forwardTemplate.trim(),
            smtpHost = smtpHost.trim(),
            smtpPort = smtpPort.trim(),
            smtpUsername = smtpUsername.trim(),
            smtpPassword = smtpPassword.trim(),
            senderEmail = senderEmail.trim(),
            senderDisplayName = senderDisplayName.trim(),
            recipientEmail = recipientEmail.trim(),
        )
    }

    return ChannelConfigs.applyDefaults(trimmed)
}

fun ChannelConfig.isReadyForDispatch(): Boolean {
    return when (type) {
        ChannelType.DINGTALK,
        ChannelType.FEISHU,
        -> webhookUrl.isNotBlank()

        ChannelType.EMAIL -> smtpHost.isNotBlank() &&
            smtpPort.toIntOrNull()?.let { it > 0 } == true &&
            smtpUsername.isNotBlank() &&
            smtpPassword.isNotBlank() &&
            senderEmail.isNotBlank() &&
            recipientEmail.isNotBlank()
    }
}
