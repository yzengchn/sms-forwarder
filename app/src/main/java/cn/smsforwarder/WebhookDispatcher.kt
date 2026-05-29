package cn.smsforwarder

import android.net.Uri
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class WebhookDispatcher {
    fun sendPayload(config: ChannelConfig, payload: ForwardPayload, deviceLabel: String): DispatchResult {
        val template = if (config.forwardTemplate.isNotBlank()) config.forwardTemplate
            else ChannelConfigs.DEFAULT_FORWARD_TEMPLATE
        val text = payload.renderTemplate(template, deviceLabel, config.note)
        return postMessage(config, text)
    }

    fun sendTest(config: ChannelConfig, deviceLabel: String): DispatchResult {
        val text = buildString {
            append("SMS Forwarder Test\n")
            append("Device: ").append(deviceLabel).append('\n')
            append("Time: ").append(TimeFormatter.format(System.currentTimeMillis())).append('\n')
            append("Channel: ").append(config.type.displayName).append('\n')
            if (config.note.isNotBlank()) {
                append("Note: ").append(config.note).append('\n')
            }
            append("This is a connectivity test message.")
        }
        return postMessage(config, text)
    }

    private fun postMessage(config: ChannelConfig, text: String): DispatchResult {
        if (config.webhookUrl.isBlank()) {
            return DispatchResult(success = false, message = "Webhook URL is empty.")
        }
        val body = when (config.type) {
            ChannelType.DINGTALK -> JSONObject()
                .put("msgtype", "text")
                .put("text", JSONObject().put("content", text))
                .toString()

            ChannelType.FEISHU -> JSONObject()
                .put("msg_type", "text")
                .put("content", JSONObject().put("text", text))
                .toString()

            ChannelType.EMAIL -> return DispatchResult(
                success = false,
                message = "Email channel is not supported by WebhookDispatcher.",
            )
        }
        val requestUrl = when (config.type) {
            ChannelType.DINGTALK -> buildDingTalkUrl(config)
            ChannelType.FEISHU -> config.webhookUrl
            ChannelType.EMAIL -> config.webhookUrl
        }
        return postJson(requestUrl, body)
    }

    private fun postJson(urlString: String, jsonBody: String): DispatchResult {
        return runCatching {
            val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 15_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            try {
                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(jsonBody)
                }

                val code = connection.responseCode
                val payload = readResponse(connection)
                if (code in 200..299) {
                    DispatchResult(success = true, httpCode = code, message = payload.ifBlank { "OK" })
                } else {
                    DispatchResult(success = false, httpCode = code, message = payload.ifBlank { "HTTP $code" })
                }
            } finally {
                connection.disconnect()
            }
        }.getOrElse { error ->
            DispatchResult(success = false, message = error.message ?: error.javaClass.simpleName)
        }
    }

    private fun readResponse(connection: HttpURLConnection): String {
        val stream = runCatching { connection.inputStream }.getOrNull()
            ?: connection.errorStream
            ?: return ""
        return BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
            reader.readText().take(400)
        }
    }

    private fun buildDingTalkUrl(config: ChannelConfig): String {
        if (config.secret.isBlank()) {
            return config.webhookUrl
        }

        val timestamp = System.currentTimeMillis().toString()
        val stringToSign = "$timestamp\n${config.secret}"
        val mac = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(config.secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256")
        mac.init(secretKey)
        val sign = Base64.getEncoder().encodeToString(
            mac.doFinal(stringToSign.toByteArray(StandardCharsets.UTF_8)),
        )

        return Uri.parse(config.webhookUrl)
            .buildUpon()
            .appendQueryParameter("timestamp", timestamp)
            .appendQueryParameter("sign", sign)
            .build()
            .toString()
    }
}
