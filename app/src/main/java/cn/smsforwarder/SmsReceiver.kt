package cn.smsforwarder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            return
        }

        val repository = AppRepository.getInstance(context)
        if (!repository.isServiceEnabled()) {
            return
        }

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isEmpty()) {
            return
        }

        val payload = ForwardPayload(
            type = ForwardRecordType.SMS,
            sender = messages.firstOrNull()?.originatingAddress.orEmpty().ifBlank { "Unknown" },
            body = messages.joinToString(separator = "") { it.messageBody.orEmpty() },
            receivedAt = messages.maxOfOrNull { it.timestampMillis } ?: System.currentTimeMillis(),
        ).normalized()

        if (!repository.markForwardSeen(payload.dedupeFingerprint(source = "sms"))) {
            return
        }

        ForwarderService.enqueuePayload(context.applicationContext, payload)
    }
}
