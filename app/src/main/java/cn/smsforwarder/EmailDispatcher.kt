package cn.smsforwarder

import java.nio.charset.StandardCharsets
import java.util.Date
import java.util.Properties
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.NoSuchProviderException
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.AddressException
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

class EmailDispatcher {
    fun sendPayload(config: ChannelConfig, payload: ForwardPayload, deviceLabel: String): DispatchResult {
        val subject = "${payload.type.displayName}转发 - ${payload.sender}"
        val template = if (config.forwardTemplate.isNotBlank()) config.forwardTemplate
            else ChannelConfigs.DEFAULT_FORWARD_TEMPLATE
        val body = payload.renderTemplate(template, deviceLabel, config.note)
        return sendEmail(config, subject, body)
    }

    fun sendTest(config: ChannelConfig, deviceLabel: String): DispatchResult {
        val body = buildString {
            append("SMS Forwarder Test").append('\n')
            append("Device: ").append(deviceLabel).append('\n')
            append("Time: ").append(TimeFormatter.format(System.currentTimeMillis())).append('\n')
            append("Channel: ").append(config.type.displayName).append('\n')
            append("Recipient: ").append(config.recipientEmail).append('\n')
            if (config.note.isNotBlank()) {
                append("Note: ").append(config.note).append('\n')
            }
            append('\n')
            append("This is a connectivity test message.")
        }
        return sendEmail(config, "短信转发测试 - ${config.recipientEmail}", body)
    }

    private fun sendEmail(config: ChannelConfig, subject: String, body: String): DispatchResult {
        val port = config.smtpPort.toIntOrNull()?.takeIf { it > 0 }
            ?: return DispatchResult(success = false, message = "SMTP port is invalid.")
        val senderAddress = EmailAddressParser.parseSender(config.senderEmail).getOrElse { error ->
            return DispatchResult(success = false, message = formatAddressError(error, "发件邮箱格式不正确"))
        }
        val recipientAddresses = EmailAddressParser.parseRecipients(config.recipientEmail).getOrElse { error ->
            return DispatchResult(success = false, message = formatAddressError(error, "收件邮箱格式不正确"))
        }

        return runCatching {
            val properties = Properties().apply {
                put("mail.transport.protocol", "smtp")
                put("mail.smtp.host", config.smtpHost)
                put("mail.smtp.port", port.toString())
                put("mail.smtp.auth", "true")
                put("mail.smtp.connectiontimeout", "10000")
                put("mail.smtp.timeout", "15000")
                put("mail.smtp.writetimeout", "15000")
                if (config.useTls) {
                    if (port == 465) {
                        put("mail.smtp.ssl.enable", "true")
                    } else {
                        put("mail.smtp.starttls.enable", "true")
                        put("mail.smtp.starttls.required", "true")
                    }
                }
            }

            val session = Session.getInstance(
                properties,
                object : Authenticator() {
                    override fun getPasswordAuthentication(): PasswordAuthentication {
                        return PasswordAuthentication(config.smtpUsername, config.smtpPassword)
                    }
                },
            )

            val message = MimeMessage(session).apply {
                setFrom(
                    InternetAddress(
                        senderAddress.address,
                        config.senderDisplayName.ifBlank { ChannelConfigs.DEFAULT_EMAIL_SENDER_DISPLAY_NAME },
                        StandardCharsets.UTF_8.name(),
                    ),
                )
                setRecipients(Message.RecipientType.TO, recipientAddresses)
                setSentDate(Date())
                setSubject(subject, StandardCharsets.UTF_8.name())
                setText(body, StandardCharsets.UTF_8.name())
            }

            Transport.send(message)
            DispatchResult(success = true, message = "OK")
        }.getOrElse { error ->
            DispatchResult(success = false, message = formatSendError(error))
        }
    }

    private fun formatSendError(error: Throwable): String {
        return when (error) {
            is NoSuchProviderException -> "SMTP provider 加载失败：${error.message ?: error.javaClass.simpleName}"
            else -> error.message ?: error.javaClass.simpleName
        }
    }

    private fun formatAddressError(error: Throwable, fallback: String): String {
        return when (error) {
            is IllegalArgumentException -> error.message ?: fallback
            is AddressException -> fallback
            else -> error.message ?: fallback
        }
    }
}
