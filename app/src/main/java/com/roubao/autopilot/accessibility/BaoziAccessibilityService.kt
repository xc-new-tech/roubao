package com.roubao.autopilot.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 肉包无障碍服务
 * 提供 UI 树获取、元素点击、文本输入等能力
 */
class BaoziAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "BaoziA11y"

        @Volatile
        private var instance: BaoziAccessibilityService? = null

        /**
         * 获取服务实例
         */
        fun getInstance(): BaoziAccessibilityService? = instance

        /**
         * 检查服务是否可用
         */
        fun isAvailable(): Boolean = instance != null
    }

    // 当前 UI 树缓存
    private var currentUITree: UITree? = null
    private var lastUpdateTime: Long = 0

    // 元素索引映射
    private val elementIndexMap = mutableMapOf<Int, AccessibilityNodeInfo>()

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "BaoziAccessibilityService created")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        // 配置服务
        serviceInfo = serviceInfo?.apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = flags or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 100
        }

        Log.i(TAG, "BaoziAccessibilityService connected")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        clearInternalCache()
        Log.d(TAG, "BaoziAccessibilityService destroyed")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 可以在这里监听 UI 变化事件
        event?.let {
            when (it.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                    // UI 发生变化，清除缓存
                    currentUITree = null
                }
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "BaoziAccessibilityService interrupted")
    }

    /**
     * 清除内部缓存
     */
    private fun clearInternalCache() {
        currentUITree = null
        elementIndexMap.values.forEach {
            try { it.recycle() } catch (e: Exception) { }
        }
        elementIndexMap.clear()
    }

    /**
     * 获取当前 UI 树
     * @param forceRefresh 是否强制刷新
     * @param onlyInteractive 是否只获取可交互元素
     */
    fun getUITree(forceRefresh: Boolean = false, onlyInteractive: Boolean = true): UITree? {
        // 检查缓存
        val now = System.currentTimeMillis()
        if (!forceRefresh && currentUITree != null && now - lastUpdateTime < 500) {
            return currentUITree
        }

        // 清除旧的索引映射
        elementIndexMap.values.forEach {
            try { it.recycle() } catch (e: Exception) { }
        }
        elementIndexMap.clear()

        val rootNode = rootInActiveWindow ?: return null

        try {
            val elements = mutableListOf<UIElement>()
            var indexCounter = 0

            // 递归解析节点
            fun parseNode(node: AccessibilityNodeInfo, depth: Int): UIElement? {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)

                // 过滤无效节点
                if (bounds.width() <= 0 || bounds.height() <= 0) {
                    return null
                }

                val isInteractive = node.isClickable || node.isLongClickable ||
                        node.isEditable || node.isCheckable || node.isScrollable

                // 如果只要可交互元素，跳过非交互元素（但仍然递归子节点）
                val shouldInclude = !onlyInteractive || isInteractive ||
                        !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()

                val element = if (shouldInclude) {
                    val index = indexCounter++

                    // 保存节点引用用于后续操作
                    elementIndexMap[index] = AccessibilityNodeInfo.obtain(node)

                    UIElement(
                        index = index,
                        className = node.className?.toString() ?: "",
                        text = node.text?.toString(),
                        contentDescription = node.contentDescription?.toString(),
                        resourceId = node.viewIdResourceName,
                        bounds = bounds,
                        isClickable = node.isClickable,
                        isLongClickable = node.isLongClickable,
                        isScrollable = node.isScrollable,
                        isEditable = node.isEditable,
                        isCheckable = node.isCheckable,
                        isChecked = node.isChecked,
                        isFocused = node.isFocused,
                        isEnabled = node.isEnabled,
                        packageName = node.packageName?.toString(),
                        depth = depth
                    ).also { elements.add(it) }
                } else null

                // 递归解析子节点
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    val childElement = parseNode(child, depth + 1)
                    if (element != null && childElement != null) {
                        element.children.add(childElement)
                    }
                    child.recycle()
                }

                return element
            }

            val rootElement = parseNode(rootNode, 0)

            // 获取当前包名和 Activity
            val packageName = rootNode.packageName?.toString() ?: ""
            val activityName = try {
                val windows = windows
                windows.firstOrNull { it.isActive }?.title?.toString() ?: ""
            } catch (e: Exception) {
                ""
            }

            currentUITree = UITree(
                elements = elements,
                root = rootElement,
                packageName = packageName,
                activityName = activityName
            )
            lastUpdateTime = now

            Log.d(TAG, "UI Tree parsed: ${elements.size} elements")
            return currentUITree

        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse UI tree", e)
            return null
        } finally {
            rootNode.recycle()
        }
    }

    /**
     * 根据索引点击元素
     */
    fun tapByIndex(index: Int): Boolean {
        val node = elementIndexMap[index]
        if (node == null) {
            Log.w(TAG, "Element with index $index not found")
            return false
        }

        return try {
            // 尝试直接点击节点
            if (node.isClickable) {
                val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.d(TAG, "tapByIndex($index) via ACTION_CLICK: $result")
                return result
            }

            // 如果节点不可点击，尝试点击父节点
            var parent = node.parent
            while (parent != null) {
                if (parent.isClickable) {
                    val result = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    parent.recycle()
                    Log.d(TAG, "tapByIndex($index) via parent ACTION_CLICK: $result")
                    return result
                }
                val temp = parent.parent
                parent.recycle()
                parent = temp
            }

            // 最后尝试使用手势点击坐标
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            tapAtCoordinate(bounds.centerX(), bounds.centerY())
        } catch (e: Exception) {
            Log.e(TAG, "tapByIndex($index) failed", e)
            false
        }
    }

    /**
     * 在指定坐标点击
     */
    fun tapAtCoordinate(x: Int, y: Int, duration: Long = 100): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.w(TAG, "Gesture requires API 24+")
            return false
        }

        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()

        val result = dispatchGesture(gesture, null, null)
        Log.d(TAG, "tapAtCoordinate($x, $y): $result")
        return result
    }

    /**
     * 长按指定索引的元素
     */
    fun longPressByIndex(index: Int, duration: Long = 1000): Boolean {
        val node = elementIndexMap[index]
        if (node == null) {
            Log.w(TAG, "Element with index $index not found")
            return false
        }

        return try {
            if (node.isLongClickable) {
                val result = node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                Log.d(TAG, "longPressByIndex($index) via ACTION_LONG_CLICK: $result")
                return result
            }

            // 使用手势长按
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            longPressAtCoordinate(bounds.centerX(), bounds.centerY(), duration)
        } catch (e: Exception) {
            Log.e(TAG, "longPressByIndex($index) failed", e)
            false
        }
    }

    /**
     * 在指定坐标长按
     */
    fun longPressAtCoordinate(x: Int, y: Int, duration: Long = 1000): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return false
        }

        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()

        val result = dispatchGesture(gesture, null, null)
        Log.d(TAG, "longPressAtCoordinate($x, $y, $duration): $result")
        return result
    }

    /**
     * 滑动手势
     */
    fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, duration: Long = 300): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return false
        }

        val path = Path().apply {
            moveTo(startX.toFloat(), startY.toFloat())
            lineTo(endX.toFloat(), endY.toFloat())
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()

        val result = dispatchGesture(gesture, null, null)
        Log.d(TAG, "swipe($startX,$startY -> $endX,$endY): $result")
        return result
    }

    /**
     * 向指定索引的元素输入文本
     */
    fun inputText(index: Int, text: String, clear: Boolean = false): Boolean {
        val node = elementIndexMap[index]
        if (node == null) {
            Log.w(TAG, "Element with index $index not found")
            return false
        }

        return inputTextToNode(node, text, clear)
    }

    /**
     * 向当前焦点元素输入文本
     */
    fun inputTextToFocused(text: String, clear: Boolean = false): Boolean {
        val focusedNode = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)

        if (focusedNode == null) {
            Log.w(TAG, "No focused element found")
            return false
        }

        return try {
            inputTextToNode(focusedNode, text, clear)
        } finally {
            focusedNode.recycle()
        }
    }

    /**
     * 向指定节点输入文本
     */
    private fun inputTextToNode(node: AccessibilityNodeInfo, text: String, clear: Boolean): Boolean {
        return try {
            // 先聚焦
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

            // 清除现有文本
            if (clear) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val args = Bundle().apply {
                        putInt(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,
                            0
                        )
                        putInt(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,
                            node.text?.length ?: 0
                        )
                    }
                    node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
                }
            }

            // 设置文本
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                val result = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                Log.d(TAG, "inputText via ACTION_SET_TEXT: $result")
                result
            } else {
                // 旧版本使用剪贴板
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("text", text))
                val result = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                Log.d(TAG, "inputText via clipboard: $result")
                result
            }
        } catch (e: Exception) {
            Log.e(TAG, "inputText failed", e)
            false
        }
    }

    /**
     * 根据索引滚动元素
     */
    fun scrollByIndex(index: Int, forward: Boolean = true): Boolean {
        val node = elementIndexMap[index]
        if (node == null) {
            Log.w(TAG, "Element with index $index not found")
            return false
        }

        val action = if (forward) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }

        return try {
            val result = node.performAction(action)
            Log.d(TAG, "scrollByIndex($index, forward=$forward): $result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "scrollByIndex failed", e)
            false
        }
    }

    /**
     * 查找包含指定文本的元素并点击
     */
    fun tapByText(text: String, ignoreCase: Boolean = true): Boolean {
        val tree = getUITree(forceRefresh = true) ?: return false
        val elements = tree.findByText(text, ignoreCase)

        // 优先点击可点击的元素
        val clickable = elements.find { it.isClickable }
        if (clickable != null) {
            return tapByIndex(clickable.index)
        }

        // 其次点击第一个匹配的元素
        val first = elements.firstOrNull()
        if (first != null) {
            return tapByIndex(first.index)
        }

        Log.w(TAG, "No element found with text: $text")
        return false
    }

    /**
     * 按下系统按键
     */
    fun pressKey(keyAction: Int): Boolean {
        return try {
            val result = performGlobalAction(keyAction)
            Log.d(TAG, "pressKey($keyAction): $result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "pressKey failed", e)
            false
        }
    }

    /**
     * 按返回键
     */
    fun pressBack(): Boolean = pressKey(GLOBAL_ACTION_BACK)

    /**
     * 按 Home 键
     */
    fun pressHome(): Boolean = pressKey(GLOBAL_ACTION_HOME)

    /**
     * 打开最近任务
     */
    fun pressRecents(): Boolean = pressKey(GLOBAL_ACTION_RECENTS)

    /**
     * 打开通知栏
     */
    fun openNotifications(): Boolean = pressKey(GLOBAL_ACTION_NOTIFICATIONS)

    /**
     * 打开快捷设置
     */
    fun openQuickSettings(): Boolean = pressKey(GLOBAL_ACTION_QUICK_SETTINGS)
}
