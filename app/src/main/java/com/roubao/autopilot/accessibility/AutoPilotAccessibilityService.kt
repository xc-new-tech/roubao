package com.roubao.autopilot.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 无障碍服务 - 提供精准的 UI 操作能力
 */
class AutoPilotAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "A11yService"
        private var instance: AutoPilotAccessibilityService? = null

        /** 获取服务实例 */
        fun getInstance(): AutoPilotAccessibilityService? = instance

        /** 检查服务是否已启用 */
        fun isEnabled(): Boolean = instance != null

        /** 跳转到无障碍设置页面 */
        fun openSettings(context: Context) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }

        /** 检查无障碍服务是否已在系统设置中启用 */
        fun isServiceEnabled(context: Context): Boolean {
            val serviceName = "${context.packageName}/${AutoPilotAccessibilityService::class.java.canonicalName}"
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabledServices.contains(serviceName)
        }
    }

    // 界面变化监听器
    private var windowChangeListener: ((String?) -> Unit)? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, " 无障碍服务已连接")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.d(TAG, " 无障碍服务已断开")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val packageName = event.packageName?.toString()
                windowChangeListener?.invoke(packageName)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // 页面内容变化
            }
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, " 服务中断")
    }

    /** 设置界面变化监听器 */
    fun setWindowChangeListener(listener: ((String?) -> Unit)?) {
        windowChangeListener = listener
    }

    /** 获取当前界面的根节点 */
    fun getRootNode(): AccessibilityNodeInfo? {
        return rootInActiveWindow
    }

    /** 通过文本查找元素 */
    fun findByText(text: String): List<AccessibilityNodeInfo> {
        val root = rootInActiveWindow ?: return emptyList()
        return root.findAccessibilityNodeInfosByText(text) ?: emptyList()
    }

    /** 通过 View ID 查找元素 */
    fun findById(viewId: String): List<AccessibilityNodeInfo> {
        val root = rootInActiveWindow ?: return emptyList()
        return root.findAccessibilityNodeInfosByViewId(viewId) ?: emptyList()
    }

    /** 点击节点 */
    fun clickNode(node: AccessibilityNodeInfo): Boolean {
        // 尝试直接点击
        if (node.isClickable) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        // 如果节点不可点击，尝试点击父节点
        var parent = node.parent
        while (parent != null) {
            if (parent.isClickable) {
                val result = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                parent.recycle()
                return result
            }
            val temp = parent.parent
            parent.recycle()
            parent = temp
        }
        return false
    }

    /** 通过坐标点击 (使用手势) */
    fun clickAt(x: Int, y: Int, duration: Long = 100): Boolean {
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /** 长按坐标 */
    fun longPressAt(x: Int, y: Int, duration: Long = 1000): Boolean {
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /** 滑动手势 */
    fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, duration: Long = 300): Boolean {
        val path = Path().apply {
            moveTo(startX.toFloat(), startY.toFloat())
            lineTo(endX.toFloat(), endY.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /** 向节点输入文本 (会先清除现有文本) */
    fun inputText(node: AccessibilityNodeInfo, text: String): Boolean {
        // 先聚焦
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

        // 方法1: 尝试直接设置空字符串清除 (某些输入框支持)
        val clearArgs = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs)

        // 方法2: 使用全选+删除来确保清除 (更可靠)
        // 全选: Ctrl+A
        val selectAllArgs = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, Int.MAX_VALUE)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectAllArgs)
        // 删除选中内容 (通过设置空文本)
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs)

        // 设置新文本
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** 向当前焦点输入文本 */
    fun inputTextToFocused(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focusedNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focusedNode != null) {
            val result = inputText(focusedNode, text)
            focusedNode.recycle()
            return result
        }
        return false
    }

    /** 执行返回操作 */
    fun performBack(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    /** 执行 Home 操作 */
    fun performHome(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_HOME)
    }

    /** 执行最近任务操作 */
    fun performRecents(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_RECENTS)
    }

    /** 打开通知栏 */
    fun openNotifications(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    }

    /** 获取 UI 树的文本描述 (用于调试或提供给 VLM) */
    fun getUITreeDescription(): String {
        val root = rootInActiveWindow ?: return "无法获取界面"
        val sb = StringBuilder()
        traverseNode(root, sb, 0)
        return sb.toString()
    }

    private fun traverseNode(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
        val indent = "  ".repeat(depth)
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val className = node.className?.toString()?.substringAfterLast('.') ?: ""
        val viewId = node.viewIdResourceName?.substringAfterLast('/') ?: ""

        // 只记录有意义的节点
        if (text.isNotEmpty() || desc.isNotEmpty() || node.isClickable) {
            sb.append(indent)
            sb.append("[$className]")
            if (text.isNotEmpty()) sb.append(" text=\"$text\"")
            if (desc.isNotEmpty()) sb.append(" desc=\"$desc\"")
            if (viewId.isNotEmpty()) sb.append(" id=$viewId")
            if (node.isClickable) sb.append(" [可点击]")
            if (node.isEditable) sb.append(" [可编辑]")
            sb.appendLine()
        }

        // 递归子节点
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverseNode(child, sb, depth + 1)
            child.recycle()
        }
    }

    /** 查找可点击的元素 (通过文本) */
    fun findClickableByText(text: String): AccessibilityNodeInfo? {
        val nodes = findByText(text)
        for (node in nodes) {
            if (node.isClickable) return node
            // 检查父节点是否可点击
            var parent = node.parent
            while (parent != null) {
                if (parent.isClickable) {
                    node.recycle()
                    return parent
                }
                val temp = parent.parent
                parent.recycle()
                parent = temp
            }
            node.recycle()
        }
        return null
    }

    /** 查找可编辑的输入框 */
    fun findEditableNode(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return findEditableRecursive(root)
    }

    private fun findEditableRecursive(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findEditableRecursive(child)
            if (result != null) return result
            child.recycle()
        }
        return null
    }
}
