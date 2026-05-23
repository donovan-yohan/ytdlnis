package com.deniscerri.ytdl.overlay

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import com.deniscerri.ytdl.MainActivity
import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.accessibility.AccessibilityUrlCaptureCoordinator
import com.deniscerri.ytdl.accessibility.CaptureRequestResult
import com.deniscerri.ytdl.accessibility.CaptureState
import com.deniscerri.ytdl.accessibility.CaptureStatus
import com.deniscerri.ytdl.accessibility.UrlCandidateExtractor
import com.deniscerri.ytdl.database.enums.DownloadType
import com.deniscerri.ytdl.util.NotificationUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class FloatingBubbleService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var bubbleView: TextView
    private var bubbleMenuView: View? = null
    private var bubbleLayoutParams: WindowManager.LayoutParams? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val tapCallback: FloatingBubbleTapCallback = ShareActivityFloatingBubbleTapCallback
    private var longPressTriggered = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        NotificationUtil(this).createNotificationChannel()
        startAsForeground()
        if (Settings.canDrawOverlays(this)) {
            showBubble()
            observeCaptureState()
        } else {
            Toast.makeText(this, R.string.floating_bubble_overlay_permission_required, Toast.LENGTH_LONG).show()
            FloatingBubbleSettings.setEnabled(this, false)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                FloatingBubbleSettings.setEnabled(this, false)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CAPTURE -> handleTapCapture()
            ACTION_OPEN_APP -> openApp()
            ACTION_START, null -> Unit
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        scope.cancel()
        removeBubbleMenu()
        runCatching { if (::bubbleView.isInitialized) windowManager.removeView(bubbleView) }
        super.onDestroy()
    }

    private fun showBubble() {
        if (::bubbleView.isInitialized) return

        val size = resources.getDimensionPixelSize(R.dimen.floating_bubble_size)
        val margin = resources.getDimensionPixelSize(R.dimen.floating_bubble_margin)
        val screen = currentScreenBounds()
        val stored = storedPosition()
        val position = stored ?: FloatingBubblePositioning.defaultPosition(screen, size, margin)

        bubbleView = TextView(this).apply {
            text = "↓"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            contentDescription = getString(R.string.floating_bubble_content_description)
            background = bubbleBackground(COLOR_IDLE)
            elevation = resources.getDimension(R.dimen.floating_bubble_elevation)
        }

        bubbleLayoutParams = WindowManager.LayoutParams(
            size,
            size,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = position.x
            y = position.y
        }

        installDragHandlers(size, margin)
        windowManager.addView(bubbleView, bubbleLayoutParams)
    }

    private fun observeCaptureState() {
        scope.launch {
            AccessibilityUrlCaptureCoordinator.state.collectLatest { state ->
                renderState(state)
            }
        }
    }

    private fun renderState(state: CaptureState) {
        if (!::bubbleView.isInitialized) return
        val visualState = FloatingBubbleStateRenderer.render(state.status)
        val color = when (state.status) {
            CaptureStatus.URL_FOUND -> COLOR_FOUND
            CaptureStatus.QUEUEING -> COLOR_QUEUEING
            CaptureStatus.QUEUED -> COLOR_QUEUED
            CaptureStatus.FAILED -> COLOR_FAILED
            CaptureStatus.IDLE -> COLOR_IDLE
        }
        bubbleView.text = visualState.label
        bubbleView.textSize = if (state.status == CaptureStatus.URL_FOUND) 13f else 22f
        bubbleView.background = bubbleBackground(color)
        bubbleView.contentDescription = buildString {
            append(getString(R.string.floating_bubble_content_description))
            append(". ").append(visualState.statusLabel)
            state.message?.let { append(". ").append(it) }
        }
    }

    private fun installDragHandlers(size: Int, margin: Int) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var startX = 0
        var startY = 0
        var downRawX = 0f
        var downRawY = 0f
        var dragging = false
        var longPressRunnable: Runnable? = null

        bubbleView.setOnTouchListener { view, event ->
            val params = bubbleLayoutParams ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    removeBubbleMenu()
                    dragging = false
                    longPressTriggered = false
                    startX = params.x
                    startY = params.y
                    downRawX = event.rawX
                    downRawY = event.rawY
                    longPressRunnable = Runnable {
                        longPressTriggered = true
                        showBubbleMenu()
                    }
                    mainHandler.postDelayed(longPressRunnable!!, ViewConfiguration.getLongPressTimeout().toLong())
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (!dragging && (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)) {
                        dragging = true
                        longPressRunnable?.let(mainHandler::removeCallbacks)
                    }
                    if (dragging) {
                        params.x = startX + dx
                        params.y = startY + dy
                        windowManager.updateViewLayout(view, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    longPressRunnable?.let(mainHandler::removeCallbacks)
                    when {
                        longPressTriggered -> Unit
                        dragging -> snapAndPersist(size, margin)
                        else -> {
                            view.performClick()
                            handleTapCapture()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    longPressRunnable?.let(mainHandler::removeCallbacks)
                    true
                }
                else -> false
            }
        }
    }

    private fun snapAndPersist(size: Int, margin: Int) {
        val params = bubbleLayoutParams ?: return
        val snapped = FloatingBubblePositioning.snapToEdge(
            current = BubblePosition(params.x, params.y),
            screen = currentScreenBounds(),
            bubbleSizePx = size,
            verticalMarginPx = margin
        )
        params.x = snapped.x
        params.y = snapped.y
        windowManager.updateViewLayout(bubbleView, params)
        PreferenceManager.getDefaultSharedPreferences(this)
            .edit()
            .putInt(FloatingBubbleSettings.POSITION_X_PREFERENCE_KEY, snapped.x)
            .putInt(FloatingBubbleSettings.POSITION_Y_PREFERENCE_KEY, snapped.y)
            .apply()
    }

    private fun storedPosition(): BubblePosition? {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (!prefs.contains(FloatingBubbleSettings.POSITION_X_PREFERENCE_KEY) ||
            !prefs.contains(FloatingBubbleSettings.POSITION_Y_PREFERENCE_KEY)
        ) return null
        return BubblePosition(
            prefs.getInt(FloatingBubbleSettings.POSITION_X_PREFERENCE_KEY, 0),
            prefs.getInt(FloatingBubbleSettings.POSITION_Y_PREFERENCE_KEY, 0)
        )
    }

    private fun currentScreenBounds(): BubbleScreenBounds {
        val metrics = resources.displayMetrics
        return BubbleScreenBounds(metrics.widthPixels, metrics.heightPixels)
    }

    private fun handleTapCapture() {
        handleCapture(null, allowClipboardFallback = true)
    }

    private fun handleCapture(requestedDownloadType: DownloadType?, allowClipboardFallback: Boolean) {
        val result = AccessibilityUrlCaptureCoordinator.requestCapture()
        val url = when (result) {
            is CaptureRequestResult.Captured -> result.candidate.url
            is CaptureRequestResult.Failed -> if (allowClipboardFallback) clipboardUrl() else null
        }

        enqueueUrlOrFail(url, requestedDownloadType)
    }

    private fun handlePasteUrl() {
        enqueueUrlOrFail(clipboardUrl(), requestedDownloadType = null)
    }

    private fun enqueueUrlOrFail(url: String?, requestedDownloadType: DownloadType?) {
        if (url == null) {
            AccessibilityUrlCaptureCoordinator.markFailed(
                getString(R.string.floating_bubble_no_url_found)
            )
            Toast.makeText(this, R.string.floating_bubble_no_url_found, Toast.LENGTH_LONG).show()
            return
        }

        val finalDownloadType = requestedDownloadType ?: defaultDownloadTypeForUrl(url)
        AccessibilityUrlCaptureCoordinator.markQueueing()
        Toast.makeText(this, getString(R.string.floating_bubble_queueing, url), Toast.LENGTH_SHORT).show()
        startQuickDownload(url, finalDownloadType)
        AccessibilityUrlCaptureCoordinator.markQueued()
    }

    private fun defaultDownloadTypeForUrl(url: String): DownloadType? {
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        return FloatingBubbleQuickActions.defaultDownloadTypeForUrl(url) { key ->
            preferences.getString(key, "")
        }
    }

    private fun clipboardUrl(): String? {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip: ClipData = clipboard.primaryClip ?: return null
        for (index in 0 until clip.itemCount) {
            val text = clip.getItemAt(index).coerceToText(this)?.toString().orEmpty()
            val url = UrlCandidateExtractor.extractUrls(text).firstOrNull()
            if (url != null) return url
        }
        return null
    }

    private fun startQuickDownload(url: String, requestedDownloadType: DownloadType?) {
        tapCallback.onCapturedUrl(this, url, requestedDownloadType)
    }

    private fun showBubbleMenu() {
        if (bubbleMenuView != null) return
        val bubbleParams = bubbleLayoutParams ?: return
        val itemPadding = resources.getDimensionPixelSize(R.dimen.floating_bubble_menu_padding)
        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bubbleMenuBackground()
            elevation = resources.getDimension(R.dimen.floating_bubble_elevation)
            FloatingBubbleQuickActions.panelActions.forEach { action ->
                addView(menuItem(labelForAction(action)) { performQuickAction(action) })
            }
            setPadding(itemPadding, itemPadding, itemPadding, itemPadding)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bubbleParams.x
            y = (bubbleParams.y - resources.getDimensionPixelSize(R.dimen.floating_bubble_menu_offset)).coerceAtLeast(0)
        }
        bubbleMenuView = menu
        windowManager.addView(menu, params)
    }

    private fun menuItem(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(Color.WHITE)
            minHeight = resources.getDimensionPixelSize(R.dimen.floating_bubble_menu_item_height)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 0, 24, 0)
            setOnClickListener {
                removeBubbleMenu()
                onClick()
            }
        }
    }

    private fun labelForAction(action: FloatingBubbleQuickAction): String {
        return when (action) {
            FloatingBubbleQuickAction.DOWNLOAD_AUDIO -> getString(R.string.floating_bubble_download_audio)
            FloatingBubbleQuickAction.DOWNLOAD_VIDEO -> getString(R.string.floating_bubble_download_video)
            FloatingBubbleQuickAction.PASTE_URL -> getString(R.string.floating_bubble_paste_url)
            FloatingBubbleQuickAction.OPEN_QUEUE -> getString(R.string.floating_bubble_open_queue)
            FloatingBubbleQuickAction.STOP_BUBBLE -> getString(R.string.floating_bubble_stop)
        }
    }

    private fun performQuickAction(action: FloatingBubbleQuickAction) {
        when (action) {
            FloatingBubbleQuickAction.DOWNLOAD_AUDIO -> handleCapture(DownloadType.audio, allowClipboardFallback = true)
            FloatingBubbleQuickAction.DOWNLOAD_VIDEO -> handleCapture(DownloadType.video, allowClipboardFallback = true)
            FloatingBubbleQuickAction.PASTE_URL -> handlePasteUrl()
            FloatingBubbleQuickAction.OPEN_QUEUE -> openQueue()
            FloatingBubbleQuickAction.STOP_BUBBLE -> {
                FloatingBubbleSettings.setEnabled(this@FloatingBubbleService, false)
                stopSelf()
            }
        }
    }

    private fun removeBubbleMenu() {
        bubbleMenuView?.let { runCatching { windowManager.removeView(it) } }
        bubbleMenuView = null
    }

    private fun openApp() {
        removeBubbleMenu()
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
    }

    private fun openQueue() {
        removeBubbleMenu()
        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra("destination", "Queue")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
    }

    private fun startAsForeground() {
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val captureIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, FloatingBubbleService::class.java).setAction(ACTION_CAPTURE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, FloatingBubbleService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, NotificationUtil.DOWNLOAD_MISC_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground_large)
            .setContentTitle(getString(R.string.floating_bubble_notification_title))
            .setContentText(getString(R.string.floating_bubble_notification_text))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, getString(R.string.floating_bubble_capture), captureIntent)
            .addAction(0, getString(R.string.floating_bubble_stop), stopIntent)
            .build()
    }

    private fun bubbleBackground(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(3, Color.WHITE)
        }
    }

    private fun bubbleMenuBackground(): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = resources.getDimension(R.dimen.floating_bubble_menu_radius)
            setColor(Color.argb(235, 32, 32, 32))
        }
    }

    private fun overlayWindowType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    companion object {
        const val ACTION_START = "com.deniscerri.ytdl.overlay.START"
        const val ACTION_STOP = "com.deniscerri.ytdl.overlay.STOP"
        const val ACTION_CAPTURE = "com.deniscerri.ytdl.overlay.CAPTURE"
        const val ACTION_OPEN_APP = "com.deniscerri.ytdl.overlay.OPEN_APP"

        private const val NOTIFICATION_ID = 91003
        private val COLOR_IDLE = Color.rgb(36, 105, 255)
        private val COLOR_FOUND = Color.rgb(38, 166, 91)
        private val COLOR_QUEUEING = Color.rgb(245, 124, 0)
        private val COLOR_QUEUED = Color.rgb(0, 150, 136)
        private val COLOR_FAILED = Color.rgb(211, 47, 47)
    }
}
