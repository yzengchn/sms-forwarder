package cn.smsforwarder

import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class CallNotificationListenerService : NotificationListenerService() {
    private lateinit var repository: AppRepository

    override fun onCreate() {
        super.onCreate()
        repository = AppRepository.getInstance(this)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        ForwarderService.broadcastStateChanged(this)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        ForwarderService.broadcastStateChanged(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!shouldInspectNotification(sbn)) return
        if (!repository.isServiceEnabled()) return

        val payload = extractMissedCallPayload(sbn) ?: return
        val fingerprint = payload.dedupeFingerprint(
            source = NOTIFICATION_SOURCE,
            sourceId = buildNotificationSourceId(sbn),
        )
        if (!repository.markForwardSeen(fingerprint)) return
        ForwarderService.enqueuePayload(applicationContext, payload)
    }

    /**
     * Phase 1 – quick filter: only inspect notifications from call-related
     * packages or with the missed_call category.  Keyword scanning alone
     * is too fragile and causes false positives (e.g. "未接快递").
     */
    private fun shouldInspectNotification(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName == packageName) return false

        val notification = sbn.notification
        // 1. Official "missed_call" category (Android 12+)
        if (notification.category == MISSED_CALL_CATEGORY) return true
        // 2. Known dialer packages only – skip everything else
        return sbn.packageName in KNOWN_CALL_PACKAGES
    }

    /**
     * Phase 2 – extract a missed-call payload **only** when the
     * notification content actually describes a missed call.
     * A dialer package can post other notifications (call log sync,
     * voicemail, etc.) that must NOT be treated as missed calls.
     */
    private fun extractMissedCallPayload(sbn: StatusBarNotification): ForwardPayload? {
        val notification = sbn.notification
        val extras = notification.extras ?: return null
        val category = notification.category.orEmpty()
        val title = extras.getCharSequence(EXTRA_NOTIFICATION_TITLE)?.toString().orEmpty().trim()
        val text = extras.getCharSequence(EXTRA_NOTIFICATION_TEXT)?.toString().orEmpty().trim()
        val bigText = extras.getCharSequence(EXTRA_NOTIFICATION_BIG_TEXT)?.toString().orEmpty().trim()
        val packageName = sbn.packageName.orEmpty()

        // Must pass at least one strict check
        if (!looksLikeMissedCall(category, packageName, title, text, bigText)) return null

        val sender = extractSender(title, text, bigText)
            ?: ForwardRecordType.MISSED_CALL.defaultSenderLabel()

        return ForwardPayload(
            type = ForwardRecordType.MISSED_CALL,
            sender = sender,
            body = "",
            receivedAt = sbn.postTime.takeIf { it > 0L } ?: System.currentTimeMillis(),
        ).normalized()
    }

    /**
     * Stricter missed-call heuristic.  At least ONE of these must be true:
     *   - category == "missed_call"  (official Android)
     *   - text/bigText starts with a recognized missed-call phrase
     *     ("未接来电", "未接电话", "Missed call") – must be a leading phrase,
     *     not a body substring like "您有未接来电" in a promo SMS
     *   - title matches a phone-number pattern (dialer shows the number)
     *     AND (text contains 未接/missed call OR title itself is a missed-call phrase)
     *
     * We no longer treat ANY notification from a dialer package as a missed call.
     */
    private fun looksLikeMissedCall(
        category: String,
        packageName: String,
        title: String,
        text: String,
        bigText: String,
    ): Boolean {
        // Official category
        if (category == MISSED_CALL_CATEGORY) return true

        // Check if the notification text itself is a missed-call announcement
        // Must be a leading phrase, not buried in unrelated content
        val combinedText = listOf(text, bigText).filter { it.isNotBlank() }
        val hasMissedCallPhrase = combinedText.any { body ->
            MISSED_CALL_PHRASES.any { phrase -> body.startsWith(phrase, ignoreCase = true) }
        }

        // Title looks like a phone number (digit-heavy) from a dialer
        val titleLooksLikeNumber = packageName in KNOWN_CALL_PACKAGES &&
            title.isNotBlank() &&
            PHONE_NUMBER_REGEX.matches(title)

        return hasMissedCallPhrase || titleLooksLikeNumber
    }

    /** Extracts the caller number from notification title/text fields. */
    private fun extractSender(vararg values: String): String? {
        return values.asSequence()
            .mapNotNull { value -> PHONE_NUMBER_IN_TEXT_REGEX.find(value)?.value?.trim() }
            .firstOrNull { it.isNotBlank() }
    }

    private fun buildNotificationSourceId(sbn: StatusBarNotification): String {
        return buildString {
            append(sbn.packageName.orEmpty())
            append('#')
            append(sbn.id)
            if (!sbn.tag.isNullOrBlank()) {
                append('#')
                append(sbn.tag)
            }
        }
    }

    companion object {
        private const val EXTRA_NOTIFICATION_TITLE = "android.title"
        private const val EXTRA_NOTIFICATION_TEXT = "android.text"
        private const val EXTRA_NOTIFICATION_BIG_TEXT = "android.bigText"
        private const val MISSED_CALL_CATEGORY = "missed_call"
        private const val NOTIFICATION_SOURCE = "notification"

        private val KNOWN_CALL_PACKAGES = setOf(
            "com.android.server.telecom",
            "com.android.dialer",
            "com.google.android.dialer",
        )

        /** Phrases that typically START a missed-call notification body. */
        private val MISSED_CALL_PHRASES = listOf(
            "未接来电",
            "未接电话",
            "missed call",
        )

        /** A phone-number-like title: mostly digits, possibly with +/space/dash. */
        private val PHONE_NUMBER_REGEX = Regex("^[+\\s\\d()\\-]{5,20}$")
        private val PHONE_NUMBER_IN_TEXT_REGEX = Regex("(?<!\\d)\\+?[\\d][\\d\\s()\\-]{4,19}(?!\\d)")
    }
}
