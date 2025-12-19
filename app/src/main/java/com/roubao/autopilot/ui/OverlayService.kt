package com.roubao.autopilot.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.roubao.autopilot.MainActivity
import com.roubao.autopilot.R

/**
 * 简洁圆形悬浮按钮 - 开始/停止控制
 */
class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var buttonView: TextView? = null
    private var animator: ValueAnimator? = null

    companion object {
        private const val TAG = "OverlayService"
        private var instance: OverlayService? = null
        private var stopCallback: (() -> Unit)? = null
        private var continueCallback: (() -> Unit)? = null
        private var confirmCallback: ((Boolean) -> Unit)? = null
        private var isTakeOverMode = false
        private var isConfirmMode = false

        private val pendingCallbacks = mutableListOf<() -> Unit>()

        fun show(context: Context, text: String, onStop: (() -> Unit)? = null) {
            stopCallback = onStop
            isTakeOverMode = false
            isConfirmMode = false
            if (instance != null) {
                instance?.setNormalMode()
            } else {
                val intent = Intent(context, OverlayService::class.java)
                ContextCompat.startForegroundService(context, intent)
            }
        }

        fun hide(context: Context) {
            stopCallback = null
            continueCallback = null
            confirmCallback = null
            isTakeOverMode = false
            isConfirmMode = false
            pendingCallbacks.clear()
            if (instance != null) {
                context.stopService(Intent(context, OverlayService::class.java))
            }
        }

        fun update(text: String) {
            // 简化版不显示文字，忽略
        }

        /** 更新思考内容 - 简化版忽略 */
        fun updateThinking(chunk: String, append: Boolean = true) {
            // 简化版不显示思考内容
        }

        /** 清空思考内容 - 简化版忽略 */
        fun clearThinking() {
            // 简化版不显示思考内容
        }

        /** 显示性能指标 - 简化版忽略 */
        fun showMetrics(ttftMs: Long?, totalMs: Long) {
            // 简化版不显示指标
        }

        /** 截图时临时隐藏悬浮窗 */
        fun setVisible(visible: Boolean) {
            instance?.overlayView?.post {
                instance?.overlayView?.visibility = if (visible) View.VISIBLE else View.INVISIBLE
            }
        }

        /** 显示人机协作模式 */
        fun showTakeOver(message: String, onContinue: () -> Unit) {
            val action: () -> Unit = {
                continueCallback = onContinue
                isTakeOverMode = true
                isConfirmMode = false
                instance?.setTakeOverMode()
            }
            if (instance != null) action() else pendingCallbacks.add(action)
        }

        /** 显示敏感操作确认模式 */
        fun showConfirm(message: String, onConfirm: (Boolean) -> Unit) {
            val action: () -> Unit = {
                confirmCallback = onConfirm
                isConfirmMode = true
                isTakeOverMode = false
                instance?.setConfirmMode()
            }
            if (instance != null) action() else pendingCallbacks.add(action)
        }

        private fun processPendingCallbacks() {
            pendingCallbacks.forEach { it.invoke() }
            pendingCallbacks.clear()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // 必须第一时间调用 startForeground，否则会崩溃
        startForegroundNotification()

        // 创建悬浮窗（可能因权限问题失败）
        try {
            createOverlayView()
        } catch (e: Exception) {
            Log.e(TAG, " createOverlayView failed: ${e.message}")
        }

        // 处理在 service 启动前排队的回调
        processPendingCallbacks()
    }

    private fun startForegroundNotification() {
        val channelId = "baozi_overlay"
        val channelName = "肉包状态"

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    channelId,
                    channelName,
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "显示肉包执行状态"
                    setShowBadge(false)
                }
                val notificationManager = getSystemService(NotificationManager::class.java)
                notificationManager.createNotificationChannel(channel)
            }

            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(this, channelId)
                .setContentTitle("肉包运行中")
                .setContentText("正在执行自动化任务...")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

            startForeground(1001, notification)
        } catch (e: Exception) {
            Log.e(TAG, " startForegroundNotification error: ${e.message}")
            // 降级：使用最简单的通知确保 startForeground 被调用
            try {
                val fallbackNotification = NotificationCompat.Builder(this, channelId)
                    .setContentTitle("肉包")
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .build()
                startForeground(1001, fallbackNotification)
            } catch (e2: Exception) {
                Log.e(TAG, " fallback startForeground also failed: ${e2.message}")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()

        // 清理动画，防止内存泄漏
        animator?.let { anim ->
            anim.removeAllUpdateListeners()
            anim.cancel()
        }
        animator = null

        // 移除悬浮窗
        overlayView?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                Log.w(TAG, "移除悬浮窗失败: ${e.message}")
            }
        }
        overlayView = null
        buttonView = null
        windowManager = null

        // 清理静态回调引用，防止内存泄漏
        stopCallback = null
        continueCallback = null
        confirmCallback = null
        pendingCallbacks.clear()

        instance = null
    }

    /** dp 转 px */
    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createOverlayView() {
        val buttonSize = dpToPx(52)

        // 七彩渐变圆形背景
        val gradientDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setStroke(dpToPx(2), Color.WHITE)
        }

        // 圆形按钮
        buttonView = TextView(this).apply {
            text = "⏹"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
            background = gradientDrawable
        }

        // 动画：七彩渐变流动效果
        startRainbowAnimation(gradientDrawable)

        val params = WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            format = PixelFormat.TRANSLUCENT
            width = buttonSize
            height = buttonSize
            gravity = Gravity.TOP or Gravity.START
            x = dpToPx(16)
            y = dpToPx(200)
        }

        // 添加拖动和点击功能
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false
        val dragThreshold = dpToPx(10).toFloat()

        buttonView?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - initialTouchX
                    val deltaY = event.rawY - initialTouchY
                    if (kotlin.math.abs(deltaX) > dragThreshold || kotlin.math.abs(deltaY) > dragThreshold) {
                        isDragging = true
                    }
                    if (isDragging) {
                        params.x = initialX + deltaX.toInt()
                        params.y = initialY + deltaY.toInt()
                        windowManager?.updateViewLayout(buttonView, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        handleButtonClick()
                    }
                    true
                }
                else -> false
            }
        }

        overlayView = buttonView
        windowManager?.addView(overlayView, params)
    }

    /** 处理按钮点击 */
    private fun handleButtonClick() {
        when {
            isConfirmMode -> {
                // 确认模式：点击确认
                confirmCallback?.invoke(true)
                confirmCallback = null
                isConfirmMode = false
                setNormalMode()
            }
            isTakeOverMode -> {
                // 人机协作模式：点击继续
                continueCallback?.invoke()
                continueCallback = null
                isTakeOverMode = false
                setNormalMode()
            }
            else -> {
                // 正常模式：点击停止
                stopCallback?.invoke()
                hide(this@OverlayService)
            }
        }
    }

    private fun startRainbowAnimation(drawable: GradientDrawable) {
        val colors = intArrayOf(
            Color.parseColor("#FF6B6B"), // 红
            Color.parseColor("#FFA94D"), // 橙
            Color.parseColor("#FFE066"), // 黄
            Color.parseColor("#69DB7C"), // 绿
            Color.parseColor("#4DABF7"), // 蓝
            Color.parseColor("#9775FA"), // 紫
            Color.parseColor("#F783AC"), // 粉
            Color.parseColor("#FF6B6B")  // 回到红
        )

        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3000
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART

            addUpdateListener { animation ->
                val fraction = animation.animatedValue as Float
                val index = (fraction * (colors.size - 1)).toInt()
                val nextIndex = minOf(index + 1, colors.size - 1)
                val localFraction = (fraction * (colors.size - 1)) - index

                val color1 = interpolateColor(colors[index], colors[nextIndex], localFraction)
                val color2 = interpolateColor(
                    colors[(index + 2) % colors.size],
                    colors[(nextIndex + 2) % colors.size],
                    localFraction
                )
                val color3 = interpolateColor(
                    colors[(index + 4) % colors.size],
                    colors[(nextIndex + 4) % colors.size],
                    localFraction
                )

                drawable.colors = intArrayOf(color1, color2, color3)
                drawable.orientation = GradientDrawable.Orientation.LEFT_RIGHT
            }
            start()
        }
    }

    private fun interpolateColor(startColor: Int, endColor: Int, fraction: Float): Int {
        val startA = Color.alpha(startColor)
        val startR = Color.red(startColor)
        val startG = Color.green(startColor)
        val startB = Color.blue(startColor)

        val endA = Color.alpha(endColor)
        val endR = Color.red(endColor)
        val endG = Color.green(endColor)
        val endB = Color.blue(endColor)

        return Color.argb(
            (startA + (endA - startA) * fraction).toInt(),
            (startR + (endR - startR) * fraction).toInt(),
            (startG + (endG - startG) * fraction).toInt(),
            (startB + (endB - startB) * fraction).toInt()
        )
    }

    /** 切换到人机协作模式 - 显示绿色继续图标 */
    private fun setTakeOverMode() {
        buttonView?.post {
            overlayView?.visibility = View.VISIBLE
            buttonView?.text = "▶"
            buttonView?.setTextColor(Color.parseColor("#90EE90"))  // 浅绿色
        }
    }

    /** 切换到正常模式 - 显示白色停止图标 */
    private fun setNormalMode() {
        buttonView?.post {
            buttonView?.text = "⏹"
            buttonView?.setTextColor(Color.WHITE)
        }
    }

    /** 切换到敏感操作确认模式 - 显示黄色确认图标 */
    private fun setConfirmMode() {
        buttonView?.post {
            overlayView?.visibility = View.VISIBLE
            buttonView?.text = "✓"
            buttonView?.setTextColor(Color.parseColor("#FFE066"))  // 黄色
        }
    }
}
