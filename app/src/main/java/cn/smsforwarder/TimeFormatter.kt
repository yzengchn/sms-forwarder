package cn.smsforwarder

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TimeFormatter {
    private val formatter = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    }
    private val dayKeyFormatter = ThreadLocal.withInitial {
        SimpleDateFormat("yyyyMMdd", Locale.getDefault())
    }
    private val yearKeyFormatter = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy", Locale.getDefault())
    }
    private val timeOnlyFormatter = ThreadLocal.withInitial {
        SimpleDateFormat("HH:mm", Locale.getDefault())
    }
    private val monthDayFormatter = ThreadLocal.withInitial {
        SimpleDateFormat("MM-dd", Locale.getDefault())
    }

    fun format(timestamp: Long): String {
        return checkNotNull(formatter.get()).format(Date(timestamp))
    }

    fun formatListTimestamp(timestamp: Long): String {
        val date = Date(timestamp)
        val now = Date()
        return when {
            checkNotNull(dayKeyFormatter.get()).format(now) == checkNotNull(dayKeyFormatter.get()).format(date) ->
                checkNotNull(timeOnlyFormatter.get()).format(date)

            checkNotNull(yearKeyFormatter.get()).format(now) == checkNotNull(yearKeyFormatter.get()).format(date) ->
                checkNotNull(monthDayFormatter.get()).format(date)

            else -> format(timestamp)
        }
    }

    fun formatOrDash(timestamp: Long): String {
        return if (timestamp > 0L) {
            format(timestamp)
        } else {
            "--"
        }
    }
}
