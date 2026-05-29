package cn.smsforwarder

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object MiuiSupport {
    fun deviceLabel(): String {
        return cachedDeviceLabel
    }

    fun isXiaomiDevice(): Boolean {
        return cachedIsXiaomiDevice
    }

    fun hasSmsPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECEIVE_SMS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasNotificationPermission(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasNotificationAccess(context: Context): Boolean {
        return NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(PowerManager::class.java)
        return powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
    }

    fun openIgnoreBatteryOptimization(context: Context): Boolean {
        val intent = if (!isIgnoringBatteryOptimizations(context)) {
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}"),
            )
        } else {
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        }
        return launchIntent(context, intent)
    }

    fun openAutostartSettings(context: Context): Boolean {
        val intents = listOf(
            Intent().setComponent(
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity",
                ),
            ),
            Intent().setComponent(
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsEditorActivity",
                ),
            ).putExtra("extra_pkgname", context.packageName),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(
                Uri.fromParts("package", context.packageName, null),
            ),
        )

        return intents.any { launchIntent(context, it) }
    }

    fun openAppDetails(context: Context): Boolean {
        return launchIntent(
            context,
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(
                Uri.fromParts("package", context.packageName, null),
            ),
        )
    }

    fun openNotificationListenerSettings(context: Context): Boolean {
        val intents = listOf(
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(
                Uri.fromParts("package", context.packageName, null),
            ),
        )
        return intents.any { launchIntent(context, it) }
    }

    fun buildChecklistText(
        context: Context,
        serviceEnabled: Boolean,
        serviceRunning: Boolean,
        enabledChannels: String,
    ): String {
        val checklist = mutableListOf<String>()
        checklist += "设备：${deviceLabel()}"
        checklist += if (serviceEnabled) {
            "• 你已经允许我在后台持续待命。"
        } else {
            "• 还没有开启后台守护，我暂时不会持续工作。"
        }
        checklist += if (serviceRunning) {
            "• 前台服务正在运行，我现在是醒着的。"
        } else {
            "• 前台服务还没起来，我还没有完全进入状态。"
        }
        checklist += if (hasSmsPermission(context)) {
            "• 短信权限已经准备好。"
        } else {
            "• 我还在等你把短信权限交给我。"
        }
        checklist += if (hasNotificationAccess(context)) {
            "• 通知读取授权已经打开，未接来电可以并入同一条转发链。"
        } else {
            "• 还没打开通知读取授权，未接来电暂时进不来。"
        }
        checklist += if (hasNotificationPermission(context)) {
            "• 系统通知权限已经放行，前台提醒不会被拦住。"
        } else {
            "• 系统通知权限还没打开，前台提醒体验会受影响。"
        }
        checklist += if (isIgnoringBatteryOptimizations(context)) {
            "• 电池优化已经放行，我更不容易被系统打断。"
        } else {
            "• 电池优化还在拦着我，后台稳定性会差一些。"
        }
        checklist += "• 目前可用的送达渠道：$enabledChannels"
        if (isXiaomiDevice()) {
            checklist += "• 我认出了这是小米系统，建议把自启动和无限制耗电一起打开。"
            checklist += context.getString(R.string.miui_task_lock_hint)
        }
        return checklist.joinToString(separator = "\n")
    }

    private fun launchIntent(context: Context, intent: Intent): Boolean {
        val safeIntent = intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (safeIntent.resolveActivity(context.packageManager) != null) {
            runCatching {
                context.startActivity(safeIntent)
            }.isSuccess
        } else {
            false
        }
    }

    private val cachedDeviceLabel by lazy(LazyThreadSafetyMode.NONE) {
        listOf(Build.MANUFACTURER, Build.MODEL)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .trim()
    }

    private val cachedIsXiaomiDevice by lazy(LazyThreadSafetyMode.NONE) {
        val value = "${Build.MANUFACTURER} ${Build.BRAND}".lowercase()
        value.contains("xiaomi") || value.contains("redmi")
    }
}
