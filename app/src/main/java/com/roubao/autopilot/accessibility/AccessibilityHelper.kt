package com.roubao.autopilot.accessibility

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils

/**
 * 无障碍服务帮助类
 */
object AccessibilityHelper {

    private const val SERVICE_ID = "com.roubao.autopilot/.accessibility.BaoziAccessibilityService"

    /**
     * 检查无障碍服务是否已启用
     */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)

        while (colonSplitter.hasNext()) {
            val service = colonSplitter.next()
            if (service.equals(SERVICE_ID, ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    /**
     * 检查无障碍服务是否可用（已启用且实例存在）
     */
    fun isServiceAvailable(): Boolean {
        return BaoziAccessibilityService.isAvailable()
    }

    /**
     * 获取服务实例
     */
    fun getService(): BaoziAccessibilityService? {
        return BaoziAccessibilityService.getInstance()
    }

    /**
     * 打开无障碍设置页面
     */
    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /**
     * 获取当前 UI 树
     */
    fun getUITree(forceRefresh: Boolean = false, onlyInteractive: Boolean = true): UITree? {
        return getService()?.getUITree(forceRefresh, onlyInteractive)
    }

    /**
     * 根据索引点击元素
     */
    fun tapByIndex(index: Int): Boolean {
        return getService()?.tapByIndex(index) ?: false
    }

    /**
     * 在坐标点击
     */
    fun tapAtCoordinate(x: Int, y: Int): Boolean {
        return getService()?.tapAtCoordinate(x, y) ?: false
    }

    /**
     * 长按元素
     */
    fun longPressByIndex(index: Int, duration: Long = 1000): Boolean {
        return getService()?.longPressByIndex(index, duration) ?: false
    }

    /**
     * 滑动
     */
    fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, duration: Long = 300): Boolean {
        return getService()?.swipe(startX, startY, endX, endY, duration) ?: false
    }

    /**
     * 输入文本
     */
    fun inputText(index: Int, text: String, clear: Boolean = false): Boolean {
        return getService()?.inputText(index, text, clear) ?: false
    }

    /**
     * 向焦点元素输入文本
     */
    fun inputTextToFocused(text: String, clear: Boolean = false): Boolean {
        return getService()?.inputTextToFocused(text, clear) ?: false
    }

    /**
     * 点击包含指定文本的元素
     */
    fun tapByText(text: String, ignoreCase: Boolean = true): Boolean {
        return getService()?.tapByText(text, ignoreCase) ?: false
    }

    /**
     * 按返回键
     */
    fun pressBack(): Boolean {
        return getService()?.pressBack() ?: false
    }

    /**
     * 按 Home 键
     */
    fun pressHome(): Boolean {
        return getService()?.pressHome() ?: false
    }

    /**
     * 滚动元素
     */
    fun scrollByIndex(index: Int, forward: Boolean = true): Boolean {
        return getService()?.scrollByIndex(index, forward) ?: false
    }
}
