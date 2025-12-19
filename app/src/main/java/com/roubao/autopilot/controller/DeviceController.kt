package com.roubao.autopilot.controller

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.roubao.autopilot.IShellService
import com.roubao.autopilot.accessibility.AutoPilotAccessibilityService
import com.roubao.autopilot.service.ShellService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 设备控制器 - 通过 Shizuku UserService 执行 shell 命令
 * 优先使用 AccessibilityService 进行操作，降级使用 Shizuku
 */
class DeviceController(private val context: Context? = null) {

    companion object {
        private const val TAG = "DeviceController"
    }

    // 委托给专门的管理器
    private val gestureController: GestureController by lazy {
        GestureController(::exec, ::getScreenSize)
    }

    private val screenshotManager: ScreenshotManager by lazy {
        ScreenshotManager(::exec, ::getScreenSize)
    }

    /** 执行方式 */
    enum class ExecutionMethod {
        A11Y,       // 使用 AccessibilityService
        SHIZUKU     // 使用 Shizuku shell
    }

    /** 上一次操作使用的执行方式 */
    var lastExecutionMethod: ExecutionMethod = ExecutionMethod.SHIZUKU
        private set

    /** 获取无障碍服务实例 */
    private val a11yService: AutoPilotAccessibilityService?
        get() = AutoPilotAccessibilityService.getInstance()

    /** 检查无障碍服务是否可用 */
    fun isA11yAvailable(): Boolean = a11yService != null

    private var shellService: IShellService? = null
    private var serviceBound = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val clipboardManager: ClipboardManager? by lazy {
        context?.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    }

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(
            "com.roubao.autopilot",
            ShellService::class.java.name
        )
    )
        .daemon(false)
        .processNameSuffix("shell")
        .debuggable(true)
        .version(1)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            shellService = IShellService.Stub.asInterface(service)
            serviceBound = true
            Log.d(TAG, " ShellService connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shellService = null
            serviceBound = false
            Log.d(TAG, " ShellService disconnected")
        }
    }

    /**
     * 绑定 Shizuku UserService
     */
    fun bindService() {
        if (!isShizukuAvailable()) {
            Log.d(TAG, " Shizuku not available")
            return
        }
        try {
            Shizuku.bindUserService(userServiceArgs, serviceConnection)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 解绑服务
     */
    fun unbindService() {
        try {
            Shizuku.unbindUserService(userServiceArgs, serviceConnection, true)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 检查 Shizuku 是否可用
     */
    fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 检查服务是否可用
     */
    fun isAvailable(): Boolean {
        return serviceBound && shellService != null
    }

    /**
     * Shizuku 权限级别
     */
    enum class ShizukuPrivilegeLevel {
        NONE,       // 未连接
        ADB,        // ADB 模式 (UID 2000)
        ROOT        // Root 模式 (UID 0)
    }

    /**
     * 获取当前 Shizuku 权限级别
     * UID 0 = root, UID 2000 = shell (ADB)
     */
    fun getShizukuPrivilegeLevel(): ShizukuPrivilegeLevel {
        if (!isAvailable()) {
            return ShizukuPrivilegeLevel.NONE
        }
        return try {
            val uid = Shizuku.getUid()
            Log.d(TAG, " Shizuku UID: $uid")
            when (uid) {
                0 -> ShizukuPrivilegeLevel.ROOT
                else -> ShizukuPrivilegeLevel.ADB
            }
        } catch (e: Exception) {
            e.printStackTrace()
            ShizukuPrivilegeLevel.NONE
        }
    }

    /**
     * 执行 shell 命令 (本地，无权限)
     */
    private fun execLocal(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readText()
            process.waitFor()
            reader.close()
            output
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    /**
     * 执行 shell 命令 (通过 Shizuku)
     */
    fun exec(command: String): String {
        return try {
            shellService?.exec(command) ?: execLocal(command)
        } catch (e: Exception) {
            e.printStackTrace()
            execLocal(command)
        }
    }

    /**
     * 点击屏幕 - 优先使用 A11y 手势
     */
    fun tap(x: Int, y: Int) {
        val service = a11yService
        if (service != null) {
            val success = service.clickAt(x, y)
            if (success) {
                lastExecutionMethod = ExecutionMethod.A11Y
                Log.d(TAG, " tap($x, $y) via A11y")
                return
            }
        }
        // 降级使用 Shizuku
        lastExecutionMethod = ExecutionMethod.SHIZUKU
        exec("input tap $x $y")
        Log.d(TAG, " tap($x, $y) via Shizuku")
    }

    /**
     * 长按 - 优先使用 A11y 手势
     */
    fun longPress(x: Int, y: Int, durationMs: Int = 1000) {
        val service = a11yService
        if (service != null) {
            val success = service.longPressAt(x, y, durationMs.toLong())
            if (success) {
                lastExecutionMethod = ExecutionMethod.A11Y
                Log.d(TAG, " longPress($x, $y, $durationMs) via A11y")
                return
            }
        }
        lastExecutionMethod = ExecutionMethod.SHIZUKU
        exec("input swipe $x $y $x $y $durationMs")
    }

    /**
     * 双击 - suspend 函数，使用协程 delay 替代 Thread.sleep
     */
    suspend fun doubleTap(x: Int, y: Int) {
        val service = a11yService
        if (service != null) {
            // A11y 双击：两次快速点击
            service.clickAt(x, y, 50)
            delay(50)  // 使用协程 delay 替代 Thread.sleep
            val success = service.clickAt(x, y, 50)
            if (success) {
                lastExecutionMethod = ExecutionMethod.A11Y
                Log.d(TAG, " doubleTap($x, $y) via A11y")
                return
            }
        }
        lastExecutionMethod = ExecutionMethod.SHIZUKU
        exec("input tap $x $y && input tap $x $y")
    }

    /**
     * 滑动 - 优先使用 A11y 手势
     */
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 500) {
        val service = a11yService
        if (service != null) {
            val success = service.swipe(x1, y1, x2, y2, durationMs.toLong())
            if (success) {
                lastExecutionMethod = ExecutionMethod.A11Y
                Log.d(TAG, " swipe($x1,$y1 -> $x2,$y2) via A11y")
                return
            }
        }
        lastExecutionMethod = ExecutionMethod.SHIZUKU
        exec("input swipe $x1 $y1 $x2 $y2 $durationMs")
    }

    /**
     * 输入文本 - 优先使用 Shizuku (更可靠)，A11y 作为备选
     * 会先清除现有文本再输入新文本
     */
    fun type(text: String) {
        // 纯 ASCII 字符直接使用 input text (最可靠)
        val hasNonAscii = text.any { it.code > 127 }

        if (!hasNonAscii) {
            // 英文/数字：直接使用 input text
            lastExecutionMethod = ExecutionMethod.SHIZUKU
            val escaped = text.replace("'", "'\\''").replace("\"", "\\\"")
            exec("input text '$escaped'")
            Log.d(TAG, " type('$text') via input text")
            return
        }

        // 中文/特殊字符：优先尝试 A11y，因为更快且不需要剪贴板
        val service = a11yService
        if (service != null) {
            // 方法1: 尝试向焦点输入框输入
            var success = service.inputTextToFocused(text)
            if (success) {
                lastExecutionMethod = ExecutionMethod.A11Y
                Log.d(TAG, " type('$text') via A11y (focused)")
                return
            }

            // 方法2: 如果找不到焦点，尝试找任何可编辑的输入框
            val editableNode = service.findEditableNode()
            if (editableNode != null) {
                success = service.inputText(editableNode, text)
                editableNode.recycle()
                if (success) {
                    lastExecutionMethod = ExecutionMethod.A11Y
                    Log.d(TAG, " type('$text') via A11y (editable)")
                    return
                }
            }
            Log.d(TAG, " A11y 输入失败，降级使用剪贴板")
        }

        // 降级使用剪贴板方式输入中文
        lastExecutionMethod = ExecutionMethod.SHIZUKU
        typeViaClipboard(text)
        Log.d(TAG, " type('$text') via clipboard")
    }

    /**
     * 通过剪贴板方式输入中文
     * 使用 Android ClipboardManager API 设置剪贴板，然后发送粘贴按键
     * 使用 Handler.postDelayed 替代 Thread.sleep 实现非阻塞延迟
     */
    private fun typeViaClipboard(text: String) {
        Log.d(TAG, " 尝试输入中文: $text")

        // 方法1: 使用 Android 剪贴板 API + 粘贴 (最可靠，不需要额外 App)
        if (clipboardManager != null) {
            try {
                // 使用 CountDownLatch 等待整个操作完成（包含延迟）
                val completionLatch = CountDownLatch(1)
                var operationSuccess = false

                // 必须在主线程操作剪贴板
                mainHandler.post {
                    try {
                        val clip = ClipData.newPlainText("baozi_input", text)
                        clipboardManager?.setPrimaryClip(clip)
                        Log.d(TAG, " ✅ 已设置剪贴板: $text")

                        // 使用 Handler.postDelayed 替代 Thread.sleep
                        // 延迟 200ms 后发送粘贴按键
                        mainHandler.postDelayed({
                            try {
                                // 发送粘贴按键 (KEYCODE_PASTE = 279)
                                exec("input keyevent 279")
                                Log.d(TAG, " ✅ 已发送粘贴按键")
                                operationSuccess = true
                            } catch (e: Exception) {
                                Log.d(TAG, " ❌ 发送粘贴按键失败: ${e.message}")
                            } finally {
                                completionLatch.countDown()
                            }
                        }, 200)
                    } catch (e: Exception) {
                        Log.d(TAG, " ❌ 设置剪贴板异常: ${e.message}")
                        completionLatch.countDown()
                    }
                }

                // 等待整个操作完成 (最多等 2 秒，包含 200ms 延迟)
                val success = completionLatch.await(2, TimeUnit.SECONDS)
                if (!success) {
                    Log.d(TAG, " ❌ 等待剪贴板操作超时")
                    return
                }

                if (operationSuccess) {
                    return
                }
            } catch (e: Exception) {
                Log.d(TAG, " ❌ 剪贴板方式失败: ${e.message}")
                e.printStackTrace()
            }
        } else {
            Log.d(TAG, " ❌ ClipboardManager 为 null，Context 未设置")
        }

        // 方法2: 使用 ADB Keyboard 广播 (备选，需要安装 ADBKeyboard)
        val escaped = text.replace("\"", "\\\"")
        val adbKeyboardResult = exec("am broadcast -a ADB_INPUT_TEXT --es msg \"$escaped\"")
        Log.d(TAG, " ADBKeyboard 广播结果: $adbKeyboardResult")

        if (adbKeyboardResult.contains("result=0")) {
            Log.d(TAG, " ✅ ADBKeyboard 输入成功")
            return
        }

        // 方法3: 使用 cmd input text (Android 12+ 可能支持 UTF-8)
        Log.d(TAG, " 尝试 cmd input text...")
        exec("cmd input text '$text'")
    }

    /**
     * 输入文本 (逐字符，兼容性更好)
     */
    fun typeCharByChar(text: String) {
        text.forEach { char ->
            when {
                char == ' ' -> exec("input text %s")
                char == '\n' -> exec("input keyevent 66")
                char.isLetterOrDigit() && char.code <= 127 -> exec("input text $char")
                char in "-.,!?@'/:;()" -> exec("input text \"$char\"")
                else -> {
                    // 非 ASCII 字符使用广播
                    exec("am broadcast -a ADB_INPUT_TEXT --es msg \"$char\"")
                }
            }
        }
    }

    /** 是否使用手势导航 (全屏手势模式) */
    var useGestureNavigation: Boolean = true

    /**
     * 返回键 - 优先使用 A11y，备选使用手势或 keyevent
     */
    fun back() {
        val service = a11yService
        if (service != null) {
            val success = service.performBack()
            if (success) {
                lastExecutionMethod = ExecutionMethod.A11Y
                Log.d(TAG, " back() via A11y")
                return
            }
        }

        lastExecutionMethod = ExecutionMethod.SHIZUKU
        if (useGestureNavigation) {
            // 全屏手势：从左侧边缘往右滑动
            backGesture()
        } else {
            exec("input keyevent 4")
            Log.d(TAG, " back() via keyevent")
        }
    }

    /**
     * Home 键 - 优先使用 A11y，备选使用手势或 keyevent
     */
    fun home() {
        val service = a11yService
        if (service != null) {
            val success = service.performHome()
            if (success) {
                lastExecutionMethod = ExecutionMethod.A11Y
                Log.d(TAG, " home() via A11y")
                return
            }
        }

        lastExecutionMethod = ExecutionMethod.SHIZUKU
        if (useGestureNavigation) {
            // 全屏手势：从底部中间往上滑动
            homeGesture()
        } else {
            exec("input keyevent 3")
            Log.d(TAG, " home() via keyevent")
        }
    }

    /**
     * 最近任务/多任务 - 使用手势
     */
    fun recents() {
        val service = a11yService
        if (service != null) {
            val success = service.performRecents()
            if (success) {
                lastExecutionMethod = ExecutionMethod.A11Y
                Log.d(TAG, " recents() via A11y")
                return
            }
        }

        lastExecutionMethod = ExecutionMethod.SHIZUKU
        if (useGestureNavigation) {
            // 全屏手势：从底部中间往上滑动并停顿
            recentsGesture()
        } else {
            exec("input keyevent 187")  // KEYCODE_APP_SWITCH
            Log.d(TAG, " recents() via keyevent")
        }
    }

    /** Home 手势 - 委托给 GestureController */
    private fun homeGesture() = gestureController.homeGesture()

    /** Back 手势 - 委托给 GestureController */
    private fun backGesture() = gestureController.backGesture()

    /** 最近任务手势 - 委托给 GestureController */
    private fun recentsGesture() = gestureController.recentsGesture()

    /**
     * 回车键
     */
    fun enter() {
        exec("input keyevent 66")
    }

    private var cacheDir: File? = null

    fun setCacheDir(dir: File) {
        cacheDir = dir
    }

    /** 截图结果类型别名，委托给 ScreenshotManager */
    @Suppress("unused")
    val ScreenshotResult = ScreenshotManager.ScreenshotResult::class

    /**
     * 截图 - 委托给 ScreenshotManager
     * 失败时返回黑屏占位图（降级处理）
     */
    suspend fun screenshotWithFallback(): ScreenshotManager.ScreenshotResult {
        return screenshotManager.screenshotWithFallback()
    }

    /**
     * 截图 - 委托给 ScreenshotManager
     * @deprecated 使用 screenshotWithFallback() 代替
     */
    @Suppress("DEPRECATION")
    suspend fun screenshot(): Bitmap? {
        return screenshotManager.screenshot()
    }

    /**
     * 获取屏幕尺寸（考虑屏幕方向）
     */
    fun getScreenSize(): Pair<Int, Int> {
        val output = exec("wm size")
        // 输出格式: Physical size: 1080x2400
        val match = Regex("(\\d+)x(\\d+)").find(output)
        val (physicalWidth, physicalHeight) = if (match != null) {
            val (w, h) = match.destructured
            Pair(w.toInt(), h.toInt())
        } else {
            Pair(1080, 2400)
        }

        // 检测屏幕方向
        val orientation = getScreenOrientation()
        return if (orientation == 1 || orientation == 3) {
            // 横屏：交换宽高
            Pair(physicalHeight, physicalWidth)
        } else {
            // 竖屏
            Pair(physicalWidth, physicalHeight)
        }
    }

    /**
     * 获取屏幕方向
     * @return 0=竖屏, 1=横屏(90°), 2=倒置竖屏, 3=横屏(270°)
     */
    private fun getScreenOrientation(): Int {
        val output = exec("dumpsys window displays | grep mCurrentOrientation")
        // 输出格式: mCurrentOrientation=0 或 mCurrentOrientation=1
        val match = Regex("mCurrentOrientation=(\\d)").find(output)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    /**
     * 打开 App - 支持包名或应用名
     * 优先使用 am start，失败时降级使用 monkey
     */
    fun openApp(packageName: String) {
        // 常见应用名到包名的映射 (作为备选)
        val packageMap = mapOf(
            "settings" to "com.android.settings",
            "设置" to "com.android.settings",
            "chrome" to "com.android.chrome",
            "camera" to "com.android.camera",
            "相机" to "com.android.camera",
            "phone" to "com.android.dialer",
            "电话" to "com.android.dialer",
            "contacts" to "com.android.contacts",
            "联系人" to "com.android.contacts",
            "messages" to "com.android.mms",
            "短信" to "com.android.mms",
            "gallery" to "com.android.gallery3d",
            "相册" to "com.android.gallery3d",
            "clock" to "com.android.deskclock",
            "时钟" to "com.android.deskclock",
            "calculator" to "com.android.calculator2",
            "计算器" to "com.android.calculator2",
            "calendar" to "com.android.calendar",
            "日历" to "com.android.calendar",
            "files" to "com.android.documentsui",
            "文件" to "com.android.documentsui",
            "微信" to "com.tencent.mm",
            "wechat" to "com.tencent.mm"
        )

        // 浏览器备选列表 (按优先级排序)
        val browserPackages = listOf(
            "com.android.chrome",           // Chrome
            "com.microsoft.emmx",           // Edge
            "com.sec.android.app.sbrowser", // Samsung Browser
            "com.huawei.browser",           // 华为浏览器
            "com.android.browser",          // AOSP 浏览器
            "org.mozilla.firefox",          // Firefox
            "com.tencent.mtt",              // QQ 浏览器
            "com.UCMobile"                  // UC 浏览器
        )

        val lowerName = packageName.lowercase().trim()

        // 如果是浏览器相关的请求，尝试多个浏览器包名
        if (lowerName == "浏览器" || lowerName == "browser" || lowerName == "默认浏览器") {
            for (browserPkg in browserPackages) {
                if (launchAppByPackage(browserPkg)) {
                    Log.d(TAG, " ✅ 浏览器启动成功: $browserPkg")
                    return
                }
            }
            Log.d(TAG, " ❌ 所有浏览器包名都启动失败")
            return
        }

        val finalPackage = if (packageName.contains(".")) {
            // 已经是包名格式
            packageName
        } else {
            // 尝试从映射中查找
            packageMap[lowerName] ?: packageName
        }

        // 启动应用
        if (launchAppByPackage(finalPackage)) {
            Log.d(TAG, " ✅ openApp 成功: $packageName -> $finalPackage")
        } else {
            Log.d(TAG, " ❌ openApp 失败: $packageName -> $finalPackage")
        }
    }

    /**
     * 通过包名启动应用
     * 优先使用 am start，失败时降级使用 monkey
     * @return 是否启动成功
     */
    private fun launchAppByPackage(packageName: String): Boolean {
        // 方法1: 使用 am start 启动 launcher activity (最可靠)
        // 先获取应用的 launcher activity
        val dumpsysResult = exec("dumpsys package $packageName | grep -A 1 'android.intent.action.MAIN' | grep -o '$packageName/[^\"]*' | head -1")
        val launcherActivity = dumpsysResult.trim()

        if (launcherActivity.isNotEmpty() && launcherActivity.contains("/")) {
            val amResult = exec("am start -n $launcherActivity")
            Log.d(TAG, " am start -n $launcherActivity, result: $amResult")
            if (!amResult.contains("Error") && !amResult.contains("Exception")) {
                return true
            }
        }

        // 方法2: 使用 am start 配合 category.LAUNCHER
        val amLauncherResult = exec("am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p $packageName")
        Log.d(TAG, " am start launcher: $packageName, result: $amLauncherResult")
        if (!amLauncherResult.contains("Error") && !amLauncherResult.contains("Exception") && amLauncherResult.contains("Starting")) {
            return true
        }

        // 方法3: 使用 monkey 命令作为备选
        val monkeyResult = exec("monkey -p $packageName -c android.intent.category.LAUNCHER 1 2>/dev/null")
        Log.d(TAG, " monkey: $packageName, result: $monkeyResult")
        if (monkeyResult.contains("Events injected: 1")) {
            return true
        }

        return false
    }

    /**
     * 通过 Intent 打开
     */
    fun openIntent(action: String, data: String? = null) {
        val cmd = buildString {
            append("am start -a $action")
            if (data != null) {
                append(" -d \"$data\"")
            }
        }
        exec(cmd)
    }

    /**
     * 打开 DeepLink
     */
    fun openDeepLink(uri: String) {
        exec("am start -a android.intent.action.VIEW -d \"$uri\"")
    }

    // ==================== A11y 辅助方法 ====================

    /**
     * 通过文本查找并点击元素
     * @return true 如果找到并点击成功
     */
    fun clickByText(text: String): Boolean {
        val service = a11yService ?: return false
        val node = service.findClickableByText(text) ?: return false
        val success = service.clickNode(node)
        node.recycle()
        Log.d(TAG, " clickByText('$text') = $success")
        return success
    }

    /**
     * 通过文本查找输入框并输入文字
     * @return true 如果找到并输入成功
     */
    fun typeToFieldByText(fieldText: String, inputText: String): Boolean {
        val service = a11yService ?: return false
        val nodes = service.findByText(fieldText)
        for (node in nodes) {
            if (node.isEditable) {
                val success = service.inputText(node, inputText)
                node.recycle()
                Log.d(TAG, " typeToFieldByText('$fieldText', '$inputText') = $success")
                return success
            }
            node.recycle()
        }
        return false
    }

    /**
     * 获取 UI 树描述 (用于调试或辅助 VLM)
     */
    fun getUITreeDescription(): String? {
        return a11yService?.getUITreeDescription()
    }

    /**
     * 查找可编辑的输入框并输入
     */
    fun typeToFirstEditable(text: String): Boolean {
        val service = a11yService ?: return false
        val node = service.findEditableNode() ?: return false
        val success = service.inputText(node, text)
        node.recycle()
        Log.d(TAG, " typeToFirstEditable('$text') = $success")
        return success
    }
}
