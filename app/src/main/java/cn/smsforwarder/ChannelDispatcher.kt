package cn.smsforwarder

class ChannelDispatcher(
    private val webhookDispatcher: WebhookDispatcher = WebhookDispatcher(),
    private val emailDispatcher: EmailDispatcher = EmailDispatcher(),
) {
    fun sendPayload(config: ChannelConfig, payload: ForwardPayload, deviceLabel: String): DispatchResult {
        return when (config.type) {
            ChannelType.DINGTALK,
            ChannelType.FEISHU,
            -> webhookDispatcher.sendPayload(config, payload, deviceLabel)

            ChannelType.EMAIL -> emailDispatcher.sendPayload(config, payload, deviceLabel)
        }
    }

    fun sendTest(config: ChannelConfig, deviceLabel: String): DispatchResult {
        return when (config.type) {
            ChannelType.DINGTALK,
            ChannelType.FEISHU,
            -> webhookDispatcher.sendTest(config, deviceLabel)

            ChannelType.EMAIL -> emailDispatcher.sendTest(config, deviceLabel)
        }
    }
}
