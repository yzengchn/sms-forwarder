package cn.smsforwarder

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import cn.smsforwarder.databinding.ActivityMainBinding
import com.google.android.material.switchmaterial.SwitchMaterial

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: AppRepository

    private var suppressUiCallbacks = false
    private var receiverRegistered = false
    private var currentNavigationItemId = R.id.navStatusAuth
    private var feedbackHideRunnable: Runnable? = null

    private val pageInterpolator = DecelerateInterpolator(1.8f)

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (action == ForwarderService.ACTION_TEST_RESULT) {
                val channelLabel = intent.getStringExtra(ForwarderService.EXTRA_TEST_CHANNEL_LABEL).orEmpty()
                val success = intent.getBooleanExtra(ForwarderService.EXTRA_TEST_SUCCESS, false)
                val templateRes = if (success) R.string.test_success else R.string.test_failure
                val message = getString(templateRes, channelLabel)
                val detail = intent.getStringExtra(ForwarderService.EXTRA_TEST_MESSAGE).orEmpty()
                showMessage("$message\n$detail")
            }
            renderRuntimeState()
            repository.invalidateCache()
            renderRuntimeState()
        }
    }

    // region Lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = AppRepository.getInstance(this)

        currentNavigationItemId = savedInstanceState?.getInt(STATE_NAV_ITEM_ID)
            ?: R.id.navStatusAuth

        setupListeners()
        setupMicroInteractions()
        setupTemplateListeners()
        renderAll()

        if (repository.isServiceEnabled()) {
            ForwarderService.start(this)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_NAV_ITEM_ID, currentNavigationItemId)
    }

    override fun onStart() {
        super.onStart()
        registerStateReceiver()
        repository.invalidateCache()
        renderRuntimeState()
    }

    override fun onResume() {
        super.onResume()
        suppressUiCallbacks = false
        repository.invalidateCache()
        renderRuntimeState()
    }

    override fun onStop() {
        super.onStop()
        unregisterStateReceiver()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        renderRuntimeState()
    }

    // endregion

    // region Setup

    private fun setupListeners() {
        // Service toggle
        binding.switchService.setOnCheckedChangeListener { _, isChecked ->
            if (suppressUiCallbacks) return@setOnCheckedChangeListener
            repository.setServiceEnabled(isChecked)
            if (isChecked) {
                ForwarderService.start(this)
            } else {
                ForwarderService.stop(this)
            }
            renderRuntimeState()
        }

        // Permission buttons
        binding.buttonGrantSmsPermission.setOnClickListener {
            if (!MiuiSupport.hasSmsPermission(this)) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECEIVE_SMS), REQUEST_SMS_PERMISSION)
            }
        }
        binding.buttonGrantNotificationAccess.setOnClickListener {
            if (!MiuiSupport.hasNotificationAccess(this)) {
                MiuiSupport.openNotificationListenerSettings(this)
            }
        }
        binding.buttonGrantNotifications.setOnClickListener {
            if (!MiuiSupport.hasNotificationPermission(this)) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_NOTIFICATION_PERMISSION,
                )
            }
        }
        binding.buttonOpenBatterySettings.setOnClickListener {
            MiuiSupport.openIgnoreBatteryOptimization(this)
        }
        binding.buttonOpenAutostartSettings.setOnClickListener {
            MiuiSupport.openAutostartSettings(this)
        }
        binding.buttonOpenAppSettings.setOnClickListener {
            MiuiSupport.openAppDetails(this)
        }

        // Navigation
        binding.navStatusAuth.setOnClickListener { showPage(R.id.navStatusAuth) }
        binding.navChannelConfig.setOnClickListener { showPage(R.id.navChannelConfig) }
        binding.navDataStats.setOnClickListener { showPage(R.id.navDataStats) }

        // Channel actions - using the consolidated wireChannelActions helper
        wireChannelActions(
            ChannelType.DINGTALK,
            configProvider = {
                ChannelConfig(
                    type = ChannelType.DINGTALK,
                    enabled = binding.switchDingTalk.isChecked,
                    webhookUrl = binding.editDingTalkWebhook.text.toString().orEmpty(),
                    secret = binding.editDingTalkSecret.text.toString().orEmpty(),
                    note = binding.editDingTalkNote.text.toString().orEmpty(),
                    forwardTemplate = binding.editForwardTemplate.text.toString().orEmpty(),
                )
            },
            saveButton = binding.buttonSaveDingTalk,
            testButton = binding.buttonTestDingTalk,
            enableSwitch = binding.switchDingTalk,
        )

        wireChannelActions(
            ChannelType.FEISHU,
            configProvider = {
                ChannelConfig(
                    type = ChannelType.FEISHU,
                    enabled = binding.switchFeishu.isChecked,
                    webhookUrl = binding.editFeishuWebhook.text.toString().orEmpty(),
                    note = binding.editFeishuNote.text.toString().orEmpty(),
                    forwardTemplate = binding.editForwardTemplate.text.toString().orEmpty(),
                )
            },
            saveButton = binding.buttonSaveFeishu,
            testButton = binding.buttonTestFeishu,
            enableSwitch = binding.switchFeishu,
        )

        wireChannelActions(
            ChannelType.EMAIL,
            configProvider = {
                ChannelConfig(
                    type = ChannelType.EMAIL,
                    enabled = binding.switchEmail.isChecked,
                    forwardTemplate = binding.editForwardTemplate.text.toString().orEmpty(),
                    smtpHost = binding.editEmailSmtpHost.text.toString().orEmpty(),
                    smtpPort = binding.editEmailSmtpPort.text.toString().orEmpty(),
                    smtpUsername = binding.editEmailSmtpUsername.text.toString().orEmpty(),
                    smtpPassword = binding.editEmailSmtpPassword.text.toString().orEmpty(),
                    senderEmail = binding.editEmailSender.text.toString().orEmpty(),
                    senderDisplayName = binding.editEmailSenderName.text.toString().orEmpty(),
                    recipientEmail = binding.editEmailRecipient.text.toString().orEmpty(),
                    useTls = binding.switchEmailTls.isChecked,
                )
            },
            saveButton = binding.buttonSaveEmail,
            testButton = binding.buttonTestEmail,
            enableSwitch = binding.switchEmail,
        )
    }

    private fun setupTemplateListeners() {
        binding.buttonSaveTemplate.setOnClickListener {
            val template = binding.editForwardTemplate.text.toString().trim()
            val configs = repository.getChannelConfigs()
            configs.forEach { config ->
                repository.saveChannelConfig(config.copy(forwardTemplate = template))
            }
            showMessage(getString(R.string.template_saved))
        }

        binding.buttonResetTemplate.setOnClickListener {
            val configs = repository.getChannelConfigs()
            configs.forEach { config ->
                repository.saveChannelConfig(config.copy(forwardTemplate = ""))
            }
            binding.editForwardTemplate.setText(ChannelConfigs.DEFAULT_FORWARD_TEMPLATE)
            showMessage(getString(R.string.template_reset_done))
        }

        binding.editForwardTemplate.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                updateTemplatePreview()
            }
        }
    }

    private fun wireChannelActions(
        type: ChannelType,
        configProvider: () -> ChannelConfig,
        saveButton: View,
        testButton: View,
        enableSwitch: SwitchMaterial,
    ) {
        enableSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (suppressUiCallbacks) return@setOnCheckedChangeListener
            val config = configProvider().copy(enabled = isChecked)
            saveChannel(config, showToast = false)
        }

        saveButton.setOnClickListener {
            val config = configProvider()
            saveChannel(config, showToast = true)
        }

        testButton.setOnClickListener {
            val config = configProvider()
            val validationError = validateChannelConfig(config)
            if (validationError != null) {
                showMessage(validationError)
                return@setOnClickListener
            }
            repository.saveChannelConfig(config)
            ForwarderService.enqueueTest(this, config)
            showMessage(getString(R.string.test_started, type.displayName))
        }
    }

    // endregion

    // region Channel persistence

    private fun saveChannel(config: ChannelConfig, showToast: Boolean) {
        val validationError = validateChannelConfig(config)
        if (validationError != null) {
            if (showToast) showMessage(validationError)
            return
        }
        repository.saveChannelConfig(config)
        if (showToast) {
            showMessage(getString(R.string.save_success, config.type.displayName))
        }
    }

    private fun validateChannelConfig(config: ChannelConfig): String? {
        return when (config.type) {
            ChannelType.DINGTALK, ChannelType.FEISHU -> {
                if (config.webhookUrl.isBlank()) {
                    getString(R.string.save_failed_empty_url, config.type.displayName)
                } else null
            }
            ChannelType.EMAIL -> {
                when {
                    config.smtpHost.isBlank() -> getString(R.string.email_host_required)
                    config.smtpPort.toIntOrNull()?.let { it > 0 } != true -> getString(R.string.email_port_invalid)
                    config.smtpUsername.isBlank() -> getString(R.string.email_username_required)
                    config.smtpPassword.isBlank() -> getString(R.string.email_password_required)
                    config.senderEmail.isBlank() -> getString(R.string.email_sender_required)
                    config.recipientEmail.isBlank() -> getString(R.string.email_recipient_required)
                    EmailAddressParser.parseSender(config.senderEmail).isFailure -> getString(R.string.email_sender_invalid)
                    EmailAddressParser.parseRecipients(config.recipientEmail).isFailure -> getString(R.string.email_recipient_invalid)
                    else -> null
                }
            }
        }
    }

    // endregion

    // region Rendering

    private fun renderAll() {
        showPage(currentNavigationItemId, animate = false)
        renderRuntimeState()
    }

    private fun showPage(navItemId: Int, animate: Boolean = true) {
        currentNavigationItemId = navItemId

        val pages = mapOf(
            R.id.navStatusAuth to binding.pageStatusAuth,
            R.id.navChannelConfig to binding.pageChannelConfig,
            R.id.navDataStats to binding.pageStats,
        )

        pages.forEach { (id, page) ->
            val isTarget = id == navItemId
            if (isTarget) {
                page.isVisible = true
                if (animate) {
                    page.alpha = 0f
                    page.animate()
                        .alpha(1f)
                        .setDuration(220)
                        .setInterpolator(pageInterpolator)
                        .withEndAction { page.scrollTo(0, 0) }
                        .start()
                }
            } else {
                page.isVisible = false
            }
        }

        updateNavigationSelection(navItemId)

        if (navItemId == R.id.navChannelConfig) {
            populateChannelFields()
        }
    }

    private fun updateNavigationSelection(navItemId: Int) {
        applyNavigationState(binding.navStatusAuth, binding.iconNavStatus, binding.labelNavStatus, navItemId == R.id.navStatusAuth)
        applyNavigationState(binding.navChannelConfig, binding.iconNavChannels, binding.labelNavChannels, navItemId == R.id.navChannelConfig)
        applyNavigationState(binding.navDataStats, binding.iconNavStats, binding.labelNavStats, navItemId == R.id.navDataStats)
    }

    private fun applyNavigationState(tab: LinearLayout, icon: ImageView, label: TextView, selected: Boolean) {
        val tintColor = if (selected) ContextCompat.getColor(this, R.color.brand_primary) else ContextCompat.getColor(this, R.color.nav_unselected)
        tab.setBackgroundResource(if (selected) R.drawable.bg_nav_tab_selected else 0)
        icon.setColorFilter(tintColor)
        label.setTextColor(tintColor)
    }

    private fun renderRuntimeState() {
        val snapshot = repository.loadSnapshot()

        // Service status
        val isRunning = ForwarderService.isRunning
        val isEnabled = snapshot.serviceEnabled

        binding.textStatusCompanion.text = MiuiSupport.buildChecklistText(
            this,
            isEnabled,
            isRunning,
            buildEnabledChannelsText(snapshot.channelConfigs),
        )

        binding.textServiceStatus.text = if (isRunning) {
            getString(R.string.service_status_running)
        } else {
            getString(R.string.service_status_stopped)
        }

        // Service switch
        suppressUiCallbacks = true
        binding.switchService.isChecked = isEnabled
        suppressUiCallbacks = false

        // Permission buttons
        // Permission buttons: granted → brand fill + white text, not granted → secondary style
        val hasSmsPerm = MiuiSupport.hasSmsPermission(this)
        binding.buttonGrantSmsPermission.text = if (hasSmsPerm) {
            getString(R.string.button_sms_permission_granted)
        } else {
            getString(R.string.button_sms_permission)
        }
        applyPermissionButtonStyle(binding.buttonGrantSmsPermission, hasSmsPerm)

        val hasNotifAccess = MiuiSupport.hasNotificationAccess(this)
        binding.buttonGrantNotificationAccess.text = if (hasNotifAccess) {
            getString(R.string.button_notification_access_granted)
        } else {
            getString(R.string.button_notification_access)
        }
        applyPermissionButtonStyle(binding.buttonGrantNotificationAccess, hasNotifAccess)

        val hasNotifPerm = MiuiSupport.hasNotificationPermission(this)
        binding.buttonGrantNotifications.text = if (hasNotifPerm) {
            getString(R.string.button_notification_permission_granted)
        } else {
            getString(R.string.button_notification_permission)
        }
        applyPermissionButtonStyle(binding.buttonGrantNotifications, hasNotifPerm)

        binding.buttonOpenBatterySettings.isVisible = !MiuiSupport.isIgnoringBatteryOptimizations(this)
        binding.buttonOpenAutostartSettings.isVisible = MiuiSupport.isXiaomiDevice()

        // Stats page
        binding.textReceivedCount.text = snapshot.stats.receivedCount.toString()
        binding.textChannelSuccessCounts.text = buildChannelSuccessCountsText(snapshot.stats.channelSuccessCounts)
        binding.textFailureCount.text = snapshot.stats.failureCount.toString()

        // Failures
        val failures = snapshot.recentFailures
        if (failures.isEmpty()) {
            binding.textFailures.text = getString(R.string.failures_empty)
        } else {
            binding.textFailures.text = failures.joinToString("\n\n") { record ->
                getString(
                    R.string.failure_record_template,
                    TimeFormatter.format(record.timestamp),
                    record.channelType.displayName,
                    record.summary,
                    record.reason,
                )
            }
        }

        // Forward logs
        renderForwardLogs(snapshot.recentForwardLogs)
    }

    private fun populateChannelFields() {
        val configs = repository.getChannelConfigs()
        suppressUiCallbacks = true

        configs.forEach { config ->
            when (config.type) {
                ChannelType.DINGTALK -> {
                    binding.switchDingTalk.isChecked = config.enabled
                    binding.editDingTalkWebhook.setText(config.webhookUrl)
                    binding.editDingTalkSecret.setText(config.secret)
                    binding.editDingTalkNote.setText(config.note)
                }
                ChannelType.FEISHU -> {
                    binding.switchFeishu.isChecked = config.enabled
                    binding.editFeishuWebhook.setText(config.webhookUrl)
                    binding.editFeishuNote.setText(config.note)
                }
                ChannelType.EMAIL -> {
                    binding.switchEmail.isChecked = config.enabled
                    binding.editEmailSmtpHost.setText(config.smtpHost)
                    binding.editEmailSmtpPort.setText(config.smtpPort)
                    binding.editEmailSmtpUsername.setText(config.smtpUsername)
                    binding.editEmailSmtpPassword.setText(config.smtpPassword)
                    binding.editEmailSender.setText(config.senderEmail)
                    binding.editEmailSenderName.setText(config.senderDisplayName)
                    binding.editEmailRecipient.setText(config.recipientEmail)
                    binding.switchEmailTls.isChecked = config.useTls
                }
            }
        }

        // Forward template: use the first channel's template as the shared template display
        val sharedTemplate = configs.firstOrNull()?.forwardTemplate.orEmpty()
        binding.editForwardTemplate.setText(sharedTemplate.ifBlank { ChannelConfigs.DEFAULT_FORWARD_TEMPLATE })

        suppressUiCallbacks = false
    }

    private fun renderForwardLogs(logs: List<ForwardLogRecord>) {
        val container = binding.containerForwardLogs
        container.removeAllViews()

        if (logs.isEmpty()) {
            binding.textForwardLogsEmpty.isVisible = true
            return
        }

        binding.textForwardLogsEmpty.isVisible = false

        logs.forEach { record ->
            val itemView = layoutInflater.inflate(R.layout.item_forward_log, container, false)
            val chipType = itemView.findViewById<TextView>(R.id.chipLogType)
            val textSender = itemView.findViewById<TextView>(R.id.textLogSender)
            val textTimestamp = itemView.findViewById<TextView>(R.id.textLogTimestamp)
            val textBody = itemView.findViewById<TextView>(R.id.textLogBody)

            when (record.type) {
                ForwardRecordType.MISSED_CALL -> {
                    chipType.text = getString(R.string.log_type_missed_call)
                    chipType.setTextColor(ContextCompat.getColor(this, R.color.brand_primary))
                    chipType.background = ContextCompat.getDrawable(this, R.drawable.bg_chip_blue)
                    textSender.text = record.sender
                    textBody.isVisible = false
                }
                ForwardRecordType.SMS -> {
                    chipType.text = getString(R.string.log_type_sms)
                    chipType.setTextColor(ContextCompat.getColor(this, R.color.success_green))
                    chipType.background = ContextCompat.getDrawable(this, R.drawable.bg_chip_green)
                    textSender.text = record.sender
                    val preview = record.body.ifBlank { getString(R.string.forward_log_body_empty) }
                        .replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
                    textBody.text = preview
                    textBody.isVisible = true
                }
            }
            textTimestamp.text = TimeFormatter.formatListTimestamp(record.timestamp)
            container.addView(itemView)
        }
    }

    private fun buildEnabledChannelsText(configs: List<ChannelConfig>): String {
        val names = configs
            .filter { it.enabled && it.isReadyForDispatch() }
            .map { it.type.displayName }
        return if (names.isEmpty()) {
            getString(R.string.enabled_channels_none)
        } else {
            getString(R.string.enabled_channels_prefix, names.joinToString(", "))
        }
    }

    private fun buildChannelSuccessCountsText(counts: Map<ChannelType, Int>): String {
        if (counts.isEmpty()) {
            return getString(R.string.channel_success_empty)
        }

        return ChannelType.entries
            .mapNotNull { type ->
                counts[type]
                    ?.takeIf { it > 0 }
                    ?.let { count -> getString(R.string.channel_success_item, type.displayName, count) }
            }
            .joinToString("\n")
    }

    // endregion

    // region Micro-interactions

    private fun setupMicroInteractions() {
        applyPressEffect(binding.buttonGrantSmsPermission)
        applyPressEffect(binding.buttonGrantNotificationAccess)
        applyPressEffect(binding.buttonGrantNotifications)
        applyPressEffect(binding.buttonOpenBatterySettings)
        applyPressEffect(binding.buttonOpenAutostartSettings)
        applyPressEffect(binding.buttonOpenAppSettings)
        applyPressEffect(binding.buttonSaveDingTalk)
        applyPressEffect(binding.buttonTestDingTalk)
        applyPressEffect(binding.buttonSaveFeishu)
        applyPressEffect(binding.buttonTestFeishu)
        applyPressEffect(binding.buttonSaveEmail)
        applyPressEffect(binding.buttonTestEmail)
        applyPressEffect(binding.buttonSaveTemplate)
        applyPressEffect(binding.buttonResetTemplate)
    }

    private fun applyPressEffect(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(120).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(180).start()
                }
            }
            false
        }
    }

    // endregion

    // region Feedback bubble

    private fun showMessage(text: String) {
        feedbackHideRunnable?.let { binding.textFeedbackBubble.removeCallbacks(it) }

        binding.textFeedbackBubble.text = text
        binding.textFeedbackBubble.isVisible = true
        binding.textFeedbackBubble.alpha = 1f
        binding.textFeedbackBubble.translationY = 0f

        val hideRunnable = Runnable {
            binding.textFeedbackBubble.animate()
                .alpha(0f)
                .translationY(-dp(18))
                .setDuration(180)
                .withEndAction { binding.textFeedbackBubble.isVisible = false }
                .start()
        }
        feedbackHideRunnable = hideRunnable
        binding.textFeedbackBubble.postDelayed(hideRunnable, 3000)
    }

    // endregion

    // region State receiver

    private fun registerStateReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(ForwarderService.ACTION_APP_STATE_CHANGED)
            addAction(ForwarderService.ACTION_TEST_RESULT)
        }
        ContextCompat.registerReceiver(this, stateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
    }

    private fun unregisterStateReceiver() {
        if (!receiverRegistered) return
        unregisterReceiver(stateReceiver)
        receiverRegistered = false
    }

    // endregion

    private fun dp(value: Int): Float {
        return value * resources.displayMetrics.density
    }

    private fun updateTemplatePreview() {
        val template = binding.editForwardTemplate.text.toString().trim()
        if (template.isBlank()) {
            binding.textTemplatePreview.isVisible = false
            return
        }
        val samplePayload = ForwardPayload(
            type = ForwardRecordType.SMS,
            sender = "10086",
            body = "验证码是123456",
            receivedAt = System.currentTimeMillis(),
        )
        val rendered = samplePayload.renderTemplate(template, MiuiSupport.deviceLabel())
        binding.textTemplatePreview.text = rendered
        binding.textTemplatePreview.isVisible = true
    }

    private fun applyPermissionButtonStyle(button: com.google.android.material.button.MaterialButton, granted: Boolean) {
        val primaryColor = ContextCompat.getColor(this, R.color.brand_primary)
        val textColor = ContextCompat.getColorStateList(this, R.color.button_secondary_fill)
        val strokeColor = ContextCompat.getColorStateList(this, R.color.border_soft)
        if (granted) {
            button.backgroundTintList = android.content.res.ColorStateList.valueOf(primaryColor)
            button.setTextColor(android.graphics.Color.WHITE)
            button.strokeColor = null
            button.strokeWidth = 0
        } else {
            button.backgroundTintList = textColor
            button.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            button.strokeColor = strokeColor
            button.strokeWidth = 1
        }
    }

    companion object {
        private const val REQUEST_SMS_PERMISSION = 1001
        private const val REQUEST_NOTIFICATION_PERMISSION = 1002
        private const val STATE_NAV_ITEM_ID = "state_nav_item_id"
    }
}
