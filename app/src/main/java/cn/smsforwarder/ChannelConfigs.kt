package cn.smsforwarder

object ChannelConfigs {
    const val DEFAULT_EMAIL_SENDER_DISPLAY_NAME = "SMS Forwarder"
    const val DEFAULT_EMAIL_SMTP_PORT = "465"
    const val DEFAULT_EMAIL_USE_TLS = true
    const val DEFAULT_FORWARD_TEMPLATE = "Forwarder\nType: {{type}}\nDevice: {{device}}\nTime: {{time}}\nFrom: {{sender}}\nContent:\n{{body}}"

    private const val DEFAULT_DINGTALK_WEBHOOK_URL =
        "https://oapi.dingtalk.com/robot/send?access_token=x"
    private const val DEFAULT_DINGTALK_SECRET =
        "x"

    private val defaults = mapOf(
        ChannelType.DINGTALK to ChannelConfig(
            type = ChannelType.DINGTALK,
            webhookUrl = DEFAULT_DINGTALK_WEBHOOK_URL,
            secret = DEFAULT_DINGTALK_SECRET,
        ),
        ChannelType.FEISHU to ChannelConfig(type = ChannelType.FEISHU),
        ChannelType.EMAIL to ChannelConfig(
            type = ChannelType.EMAIL,
            smtpPort = DEFAULT_EMAIL_SMTP_PORT,
            senderDisplayName = DEFAULT_EMAIL_SENDER_DISPLAY_NAME,
            useTls = DEFAULT_EMAIL_USE_TLS,
        ),
    )

    fun defaultConfig(type: ChannelType): ChannelConfig {
        return defaults.getValue(type)
    }

    fun applyDefaults(config: ChannelConfig): ChannelConfig {
        val defaults = defaultConfig(config.type)
        return when (config.type) {
            ChannelType.DINGTALK -> config.copy(
                webhookUrl = config.webhookUrl.ifBlank { defaults.webhookUrl },
                secret = config.secret.ifBlank { defaults.secret },
            )

            ChannelType.FEISHU -> config

            ChannelType.EMAIL -> config.copy(
                smtpPort = config.smtpPort.ifBlank { defaults.smtpPort },
                senderDisplayName = config.senderDisplayName.ifBlank { defaults.senderDisplayName },
            )
        }
    }
}
